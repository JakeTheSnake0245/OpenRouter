// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package meshroute

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"os"
	"testing"
	"time"

	"gvisor.dev/gvisor/pkg/buffer"
	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/channel"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
)

// stubBinder binds every socket successfully without touching Android.
type stubBinder struct{}

func (stubBinder) BindSocketToMesh(int32) bool     { return true }
func (stubBinder) BindSocketToInternet(int32) bool { return true }

func setMeshForTest(t *testing.T, prefixes string, rescue bool) {
	t.Helper()
	SetBinder(stubBinder{})
	SetState(prefixes, "10.41.0.2", rescue)
	t.Cleanup(func() {
		cur.Store(nil)
		SetBinder(nil)
		tailnetRoutes.Store(nil)
	})
}

func TestClassifyDivert(t *testing.T) {
	setMeshForTest(t, "10.41.0.0/16", false)
	tailnet := []netip.Prefix{netip.MustParsePrefix("100.64.0.0/10"), netip.MustParsePrefix("192.168.50.0/24")}
	tailnetRoutes.Store(&tailnet)

	tests := []struct {
		dst    string
		rescue bool
		want   bool
		kind   netKind
	}{
		{"10.41.3.4", false, true, netMesh},
		{"10.42.0.1", false, false, 0},
		{"1.1.1.1", false, false, 0},
		{"1.1.1.1", true, true, netInternet},
		{"100.101.102.103", true, false, 0}, // tailnet IP
		{"192.168.50.9", true, false, 0},    // tailnet subnet route
		{"10.41.3.4", true, true, netMesh},  // mesh wins during rescue
		{"224.0.0.251", true, false, 0},     // multicast stays native
	}
	for _, tt := range tests {
		SetState("10.41.0.0/16", "10.41.0.2", tt.rescue)
		kind, ok := classifyDivert(netip.MustParseAddr(tt.dst))
		if ok != tt.want || (ok && kind != tt.kind) {
			t.Errorf("classifyDivert(%s, rescue=%v) = %v, %v; want %v, %v", tt.dst, tt.rescue, kind, ok, tt.kind, tt.want)
		}
	}

	SetState("", "", true)
	if _, ok := classifyDivert(netip.MustParseAddr("1.1.1.1")); ok {
		t.Error("rescue must be off while the mesh is down")
	}
}

func TestBuildEchoReply(t *testing.T) {
	src := [4]byte{10, 41, 0, 9}
	dst := [4]byte{10, 41, 0, 2}
	b := buildEchoReply(src, dst, 0x1234, 7, []byte("hello mesh"))
	ip := header.IPv4(b)
	if !ip.IsValid(len(b)) {
		t.Fatal("invalid IPv4 header")
	}
	if ip.CalculateChecksum() != 0xffff {
		t.Errorf("bad IPv4 checksum")
	}
	icmp := header.ICMPv4(ip.Payload())
	if icmp.Type() != header.ICMPv4EchoReply || icmp.Ident() != 0x1234 || icmp.Sequence() != 7 {
		t.Errorf("bad ICMP header: type=%v id=%x seq=%d", icmp.Type(), icmp.Ident(), icmp.Sequence())
	}
	if checksum(icmp) != 0xffff {
		t.Errorf("bad ICMP checksum")
	}
	if string(icmp.Payload()) != "hello mesh" {
		t.Errorf("payload = %q", icmp.Payload())
	}
}

// deviceStack is a gVisor stack standing in for the apps on the phone:
// what it sends goes to the mesh stack, and what the mesh stack writes
// to the TUN comes back to it.
func newDeviceStack(t *testing.T) (*stack.Stack, *meshStack) {
	t.Helper()
	dev := stack.New(stack.Options{
		NetworkProtocols:   []stack.NetworkProtocolFactory{ipv4.NewProtocol},
		TransportProtocols: []stack.TransportProtocolFactory{tcp.NewProtocol, udp.NewProtocol},
	})
	ep := channel.New(256, stackMTU, "")
	if err := dev.CreateNIC(1, ep); err != nil {
		t.Fatal(err)
	}
	dev.AddProtocolAddress(1, tcpip.ProtocolAddress{
		Protocol:          ipv4.ProtocolNumber,
		AddressWithPrefix: tcpip.AddrFrom4([4]byte{192, 0, 0, 8}).WithPrefix(),
	}, stack.AddressProperties{})
	dev.SetRouteTable([]tcpip.Route{{Destination: header.IPv4EmptySubnet, NIC: 1}})

	ms, err := newMeshStack(func(pkt []byte) {
		pb := stack.NewPacketBuffer(stack.PacketBufferOptions{Payload: buffer.MakeWithData(pkt)})
		ep.InjectInbound(header.IPv4ProtocolNumber, pb)
		pb.DecRef()
	})
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	go func() {
		for {
			pb := ep.ReadContext(ctx)
			if pb == nil {
				return
			}
			view := pb.ToView()
			pkt := append([]byte(nil), view.AsSlice()...)
			view.Release()
			pb.DecRef()
			if kind, ok := divertPacket(pkt); ok {
				ms.inject(pkt, kind)
			}
		}
	}()
	t.Cleanup(func() {
		cancel()
		ms.close()
		dev.Close()
	})
	return dev, ms
}

// redirectMeshDials sends forwarded flows for meshIP to the loopback server.
func redirectMeshDials(t *testing.T, meshIP netip.Addr, to netip.AddrPort) {
	testHookMeshDial = func(ap netip.AddrPort) netip.AddrPort {
		if ap.Addr() == meshIP {
			return to
		}
		return ap
	}
	t.Cleanup(func() { testHookMeshDial = nil })
}

func TestMeshStackTCP(t *testing.T) {
	setMeshForTest(t, "10.41.0.0/16", false)
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() { io.Copy(c, c); c.Close() }()
		}
	}()
	meshIP := netip.MustParseAddr("10.41.7.7")
	redirectMeshDials(t, meshIP, ln.Addr().(*net.TCPAddr).AddrPort())
	dev, _ := newDeviceStack(t)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	c, err := gonet.DialContextTCP(ctx, dev, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(meshIP.As4()), Port: 8080}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatalf("dial through mesh stack: %v", err)
	}
	defer c.Close()
	msg := make([]byte, 200_000) // several windows' worth
	for i := range msg {
		msg[i] = byte(i)
	}
	go c.Write(msg)
	got := make([]byte, len(msg))
	c.SetReadDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.ReadFull(c, got); err != nil {
		t.Fatalf("read echo: %v", err)
	}
	for i := range got {
		if got[i] != msg[i] {
			t.Fatalf("echo mismatch at byte %d", i)
		}
	}
}

func TestMeshStackTCPRefused(t *testing.T) {
	setMeshForTest(t, "10.41.0.0/16", false)
	ln, _ := net.Listen("tcp4", "127.0.0.1:0")
	addr := ln.Addr().(*net.TCPAddr).AddrPort()
	ln.Close() // nothing listening: the dial must be refused
	meshIP := netip.MustParseAddr("10.41.7.8")
	redirectMeshDials(t, meshIP, addr)
	dev, _ := newDeviceStack(t)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_, err := gonet.DialContextTCP(ctx, dev, tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(meshIP.As4()), Port: 81}, ipv4.ProtocolNumber)
	if err == nil {
		t.Fatal("dial succeeded; want connection refused")
	}
}

func TestMeshStackUDP(t *testing.T) {
	setMeshForTest(t, "10.41.0.0/16", false)
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	go func() {
		buf := make([]byte, 2048)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			pc.WriteTo(append([]byte("echo:"), buf[:n]...), from)
		}
	}()
	meshIP := netip.MustParseAddr("10.41.9.9")
	redirectMeshDials(t, meshIP, pc.LocalAddr().(*net.UDPAddr).AddrPort())
	dev, _ := newDeviceStack(t)

	c, err := gonet.DialUDP(dev, nil, &tcpip.FullAddress{NIC: 1, Addr: tcpip.AddrFrom4(meshIP.As4()), Port: 6969}, ipv4.ProtocolNumber)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	for i, msg := range []string{"one", "two"} {
		if _, err := c.Write([]byte(msg)); err != nil {
			t.Fatal(err)
		}
		c.SetReadDeadline(time.Now().Add(5 * time.Second))
		buf := make([]byte, 2048)
		n, err := c.Read(buf)
		if err != nil {
			t.Fatalf("datagram %d: %v", i, err)
		}
		if got := string(buf[:n]); got != "echo:"+msg {
			t.Errorf("datagram %d = %q", i, got)
		}
	}
}

func TestMeshDualConn(t *testing.T) {
	setMeshForTest(t, "127.0.0.0/8", false)
	listen := func() *net.UDPConn {
		c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)})
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	primary, mesh, peer := listen(), listen(), listen()
	defer peer.Close()
	dc := newMeshDualConn(primary, mesh)
	defer dc.Close()

	// Writes to mesh addresses leave from the mesh socket.
	peerAddr := peer.LocalAddr().(*net.UDPAddr).AddrPort()
	if _, err := dc.WriteToUDPAddrPort([]byte("hi"), peerAddr); err != nil {
		t.Fatal(err)
	}
	buf := make([]byte, 100)
	peer.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, from, err := peer.ReadFromUDPAddrPort(buf)
	if err != nil {
		t.Fatal(err)
	}
	if from != mesh.LocalAddr().(*net.UDPAddr).AddrPort() {
		t.Errorf("packet came from %v, want the mesh socket %v", from, mesh.LocalAddr())
	}

	// Reads see packets arriving on either socket.
	for _, dst := range []*net.UDPConn{primary, mesh} {
		peer.WriteToUDPAddrPort([]byte("x"), dst.LocalAddr().(*net.UDPAddr).AddrPort())
		dc.SetReadDeadline(time.Now().Add(5 * time.Second))
		if _, _, err := dc.ReadFromUDPAddrPort(buf); err != nil {
			t.Fatalf("read via %v: %v", dst.LocalAddr(), err)
		}
	}

	// A past read deadline interrupts a blocked read.
	done := make(chan error, 1)
	dc.SetReadDeadline(time.Time{})
	go func() {
		_, _, err := dc.ReadFromUDPAddrPort(buf)
		done <- err
	}()
	time.Sleep(50 * time.Millisecond)
	dc.SetReadDeadline(time.Unix(1, 0))
	select {
	case err := <-done:
		if !errors.Is(err, os.ErrDeadlineExceeded) {
			t.Errorf("err = %v, want deadline exceeded", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("read not interrupted by deadline")
	}
	dc.Close()
	if _, _, err := dc.ReadFromUDPAddrPort(buf); !errors.Is(err, net.ErrClosed) {
		t.Errorf("read after close: %v", err)
	}
}
