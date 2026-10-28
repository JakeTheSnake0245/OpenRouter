// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package meshroute

import (
	"context"
	"errors"
	"log"
	"net"
	"net/netip"
	"os"
	"sync"
	"time"

	"tailscale.com/types/nettype"
)

// ListenPacket is installed as magicsock.ListenPacketHook so Tailscale can
// reach peers directly over the mesh radio.
//
// magicsock's UDP socket is bound to the internet network, so packets to a
// peer's mesh address would go out the wrong interface. While the mesh is
// up, the IPv4 socket is paired with a second socket bound to the mesh
// network; writes to mesh addresses use it, and reads come from both.
// The device's mesh address is reported to peers as a local endpoint by the
// normal interface enumeration, so peers on the mesh find each other with
// disco just as they would on a LAN.
func ListenPacket(ctx context.Context, network, addr string, lc *net.ListenConfig) (nettype.PacketConn, error) {
	pc, err := lc.ListenPacket(ctx, network, addr)
	if err != nil {
		return nil, err
	}
	primary := pc.(*net.UDPConn)
	s := cur.Load()
	if network != "udp4" || !s.Active() || !s.LocalAddr.IsValid() {
		return primary, nil
	}
	mlc := net.ListenConfig{Control: bindControl(netMesh)}
	mpc, err := mlc.ListenPacket(ctx, "udp4", net.JoinHostPort(s.LocalAddr.String(), "0"))
	if err != nil {
		log.Printf("mesh: magicsock mesh socket: %v", err)
		return primary, nil
	}
	log.Printf("mesh: magicsock using mesh socket %v alongside %v", mpc.LocalAddr(), primary.LocalAddr())
	return newMeshDualConn(primary, mpc.(*net.UDPConn)), nil
}

type meshDatagram struct {
	b    []byte
	from netip.AddrPort
	err  error
}

// meshDualConn is a nettype.PacketConn over two UDP sockets: the regular
// one and one bound to the mesh network.
type meshDualConn struct {
	primary *net.UDPConn
	mesh    *net.UDPConn

	pkts      chan meshDatagram
	closed    chan struct{}
	closeOnce sync.Once

	mu              sync.Mutex
	readDeadline    time.Time
	deadlineChanged chan struct{} // closed and replaced when readDeadline changes
}

func newMeshDualConn(primary, mesh *net.UDPConn) *meshDualConn {
	c := &meshDualConn{
		primary:         primary,
		mesh:            mesh,
		pkts:            make(chan meshDatagram, 256),
		closed:          make(chan struct{}),
		deadlineChanged: make(chan struct{}),
	}
	go c.readLoop(primary)
	go c.readLoop(mesh)
	return c
}

func (c *meshDualConn) readLoop(uc *net.UDPConn) {
	buf := make([]byte, 65535)
	for {
		n, from, err := uc.ReadFromUDPAddrPort(buf)
		if errors.Is(err, net.ErrClosed) {
			return
		}
		d := meshDatagram{from: from, err: err}
		if n > 0 {
			d.b = append([]byte(nil), buf[:n]...)
		}
		select {
		case c.pkts <- d:
		case <-c.closed:
			return
		}
	}
}

func (c *meshDualConn) ReadFromUDPAddrPort(b []byte) (int, netip.AddrPort, error) {
	for {
		select {
		case <-c.closed:
			return 0, netip.AddrPort{}, net.ErrClosed
		default:
		}
		c.mu.Lock()
		dl, changed := c.readDeadline, c.deadlineChanged
		c.mu.Unlock()

		var timer *time.Timer
		var timeout <-chan time.Time
		if !dl.IsZero() {
			d := time.Until(dl)
			if d <= 0 {
				return 0, netip.AddrPort{}, os.ErrDeadlineExceeded
			}
			timer = time.NewTimer(d)
			timeout = timer.C
		}
		n, from, err, again := c.waitRead(b, timeout, changed)
		if timer != nil {
			timer.Stop()
		}
		if !again {
			return n, from, err
		}
	}
}

func (c *meshDualConn) waitRead(b []byte, timeout <-chan time.Time, changed <-chan struct{}) (n int, from netip.AddrPort, err error, again bool) {
	select {
	case p := <-c.pkts:
		return copy(b, p.b), p.from, p.err, false
	case <-timeout:
		return 0, netip.AddrPort{}, os.ErrDeadlineExceeded, false
	case <-changed:
		return 0, netip.AddrPort{}, nil, true
	case <-c.closed:
		return 0, netip.AddrPort{}, net.ErrClosed, false
	}
}

func (c *meshDualConn) WriteToUDPAddrPort(b []byte, addr netip.AddrPort) (int, error) {
	if cur.Load().Contains(addr.Addr().Unmap()) {
		return c.mesh.WriteToUDPAddrPort(b, addr)
	}
	return c.primary.WriteToUDPAddrPort(b, addr)
}

func (c *meshDualConn) ReadFrom(b []byte) (int, net.Addr, error) {
	n, ap, err := c.ReadFromUDPAddrPort(b)
	if !ap.IsValid() {
		return n, nil, err
	}
	return n, net.UDPAddrFromAddrPort(ap), err
}

func (c *meshDualConn) WriteTo(b []byte, addr net.Addr) (int, error) {
	ua, ok := addr.(*net.UDPAddr)
	if !ok {
		return 0, errors.New("meshDualConn: not a UDP address")
	}
	return c.WriteToUDPAddrPort(b, ua.AddrPort())
}

func (c *meshDualConn) LocalAddr() net.Addr { return c.primary.LocalAddr() }

func (c *meshDualConn) Close() error {
	var err error
	c.closeOnce.Do(func() {
		close(c.closed)
		err = errors.Join(c.primary.Close(), c.mesh.Close())
	})
	return err
}

func (c *meshDualConn) SetDeadline(t time.Time) error {
	c.SetReadDeadline(t)
	return c.SetWriteDeadline(t)
}

func (c *meshDualConn) SetReadDeadline(t time.Time) error {
	c.mu.Lock()
	c.readDeadline = t
	close(c.deadlineChanged)
	c.deadlineChanged = make(chan struct{})
	c.mu.Unlock()
	return nil
}

func (c *meshDualConn) SetWriteDeadline(t time.Time) error {
	return errors.Join(c.primary.SetWriteDeadline(t), c.mesh.SetWriteDeadline(t))
}

var _ nettype.PacketConn = (*meshDualConn)(nil)
