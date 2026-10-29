// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package meshroute

import (
	"log"
	"net/netip"
	"sync"

	"github.com/tailscale/wireguard-go/tun"
	"tailscale.com/net/tsaddr"
)

// classifyDivert reports whether a packet to dst should bypass WireGuard
// and be forwarded by the mesh stack, and over which network.
func classifyDivert(dst netip.Addr) (netKind, bool) {
	s := cur.Load()
	if !s.Active() || !dst.Is4() {
		return 0, false
	}
	// Tailnet addresses (including MagicDNS at 100.100.100.100) always go
	// through WireGuard, whatever the mesh link claims.
	if tsaddr.CGNATRange().Contains(dst) {
		return 0, false
	}
	if s.Contains(dst) {
		return netMesh, true
	}
	if !s.Rescue {
		return 0, false
	}
	if !dst.IsGlobalUnicast() {
		return 0, false
	}
	if rp := tailnetRoutes.Load(); rp != nil {
		for _, p := range *rp {
			if p.Contains(dst) {
				return 0, false
			}
		}
	}
	return netInternet, true
}

// divertPacket reports whether the IPv4 packet pkt should be diverted to
// the mesh stack, and over which network.
func divertPacket(pkt []byte) (netKind, bool) {
	if len(pkt) < 20 || pkt[0]>>4 != 4 {
		return 0, false
	}
	return classifyDivert(netip.AddrFrom4([4]byte(pkt[16:20])))
}

// TUN wraps the tun.Device given to the WireGuard engine. Packets that
// leave the device for the mesh (or, during rescue, the internet) are
// handed to the mesh stack instead of being returned to the engine; the
// stack writes its replies straight to the wrapped device.
type TUN struct {
	tun.Device

	stackOnce sync.Once
	stack     *meshStack
}

// NewTUN returns a TUN wrapping d.
func NewTUN(d tun.Device) *TUN {
	return &TUN{Device: d}
}

func (t *TUN) meshStack() *meshStack {
	t.stackOnce.Do(func() {
		s, err := newMeshStack(func(pkt []byte) {
			packetsFromStack.Add(1)
			t.Device.Write([][]byte{pkt}, 0)
		})
		if err != nil {
			log.Printf("mesh: creating stack: %v", err)
			return
		}
		t.stack = s
	})
	return t.stack
}

func (t *TUN) Read(slab []byte, packets []tun.ReadPacket) (int, error) {
	for {
		n, err := t.Device.Read(slab, packets)
		if n == 0 || !cur.Load().Active() {
			return n, err
		}
		kept := 0
		for i := range n {
			rp := packets[i]
			pkt := slab[rp.Offset : rp.Offset+rp.Size]
			if kind, ok := divertPacket(pkt); ok {
				if s := t.meshStack(); s != nil {
					if kind == netMesh {
						packetsToMesh.Add(1)
					} else {
						packetsToInternet.Add(1)
					}
					s.inject(pkt, kind)
				}
				continue
			}
			packets[kept] = rp
			kept++
		}
		if kept > 0 || err != nil {
			return kept, err
		}
	}
}
