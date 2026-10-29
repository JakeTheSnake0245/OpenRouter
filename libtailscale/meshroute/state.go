// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// Package meshroute implements OpenRoute's mesh routing.
//
// The Android app watches for the mesh radio link (USB ethernet or a
// designated Wi-Fi network) and reports it with SetState. While a mesh link
// is up, its subnets are added to the VPN's routes; packets for those
// subnets are diverted out of the TUN before they reach WireGuard (see
// TUN) and handed to a small userspace stack that forwards each flow over a
// socket bound to the mesh network. When internet rescue is active,
// non-tailnet internet traffic is forwarded the same way over the internet
// (cellular) network.
package meshroute

import (
	"encoding/json"
	"errors"
	"log"
	"net/netip"
	"strings"
	"sync/atomic"
	"syscall"

	"tailscale.com/net/tsaddr"
)

// State is the mesh configuration most recently reported by the app.
type State struct {
	// Prefixes are the IPv4 subnets routed to the mesh. Empty when the
	// mesh link is down.
	Prefixes []netip.Prefix
	// LocalAddr is this device's IPv4 address on the mesh link, if known.
	LocalAddr netip.Addr
	// Rescue reports whether non-tailnet internet traffic should be routed
	// through the VPN and out the internet network, because the mesh link
	// has taken over Android's default network.
	Rescue bool
}

// Active reports whether the mesh link is up.
func (s *State) Active() bool { return s != nil && len(s.Prefixes) > 0 }

// Contains reports whether ip is on the mesh.
func (s *State) Contains(ip netip.Addr) bool {
	if s == nil {
		return false
	}
	for _, p := range s.Prefixes {
		if p.Contains(ip) {
			return true
		}
	}
	return false
}

func (s *State) equal(o *State) bool {
	if s == nil || o == nil {
		return s == o
	}
	if s.LocalAddr != o.LocalAddr || s.Rescue != o.Rescue || len(s.Prefixes) != len(o.Prefixes) {
		return false
	}
	for i := range s.Prefixes {
		if s.Prefixes[i] != o.Prefixes[i] {
			return false
		}
	}
	return true
}

var cur atomic.Pointer[State]

// Current returns the current mesh state. It may be nil.
func Current() *State { return cur.Load() }

// SetState records the mesh state reported by the app and reports whether
// it changed. prefixes is a comma-separated list of IPv4 CIDRs to route to
// the mesh (empty when the link is down), localAddr is the device's IPv4
// address on the mesh link (may be empty), and rescue reports whether
// internet traffic should be carried over the internet network.
func SetState(prefixes, localAddr string, rescue bool) bool {
	ns := &State{Rescue: rescue}
	for _, s := range strings.Split(prefixes, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		p, err := netip.ParsePrefix(s)
		if err != nil || !p.Addr().Is4() {
			log.Printf("mesh: ignoring invalid prefix %q: %v", s, err)
			continue
		}
		if why := unsafeMeshPrefix(p); why != "" {
			log.Printf("mesh: refusing prefix %v: %s", p, why)
			continue
		}
		ns.Prefixes = append(ns.Prefixes, p.Masked())
	}
	if a, err := netip.ParseAddr(strings.TrimSpace(localAddr)); err == nil && a.Is4() {
		ns.LocalAddr = a
	}
	if !ns.Active() {
		// Rescue only makes sense while the mesh link holds the default
		// network; never leave it on with no mesh.
		ns.Rescue = false
	}
	if old := cur.Swap(ns); ns.equal(old) {
		return false
	}
	log.Printf("mesh: state changed: prefixes=%v localAddr=%v rescue=%v", ns.Prefixes, ns.LocalAddr, ns.Rescue)
	return true
}

// MinMeshPrefixBits is the widest mesh subnet accepted (a /8).
const MinMeshPrefixBits = 8

// unsafeMeshPrefix reports why p can't be routed to the mesh, or "" if it
// can. Mesh traffic leaves the device unencrypted, so a subnet that covers
// the tailnet's addresses, or a huge slice of the internet, is refused.
func unsafeMeshPrefix(p netip.Prefix) string {
	if p.Bits() < MinMeshPrefixBits {
		return "wider than /8"
	}
	if p.Overlaps(tsaddr.CGNATRange()) {
		return "overlaps the tailnet range 100.64.0.0/10"
	}
	return ""
}

// tailnetRoutes holds the routes Tailscale itself wants in the VPN (from
// the last router config). Destinations covered by them always go to
// WireGuard, even during internet rescue; this also means rescue steps
// aside when an exit node is in use.
var tailnetRoutes atomic.Pointer[[]netip.Prefix]

// SetTailnetRoutes records the routes from Tailscale's router config.
func SetTailnetRoutes(routes []netip.Prefix) {
	tailnetRoutes.Store(&routes)
}

// Binder binds sockets to Android networks. It is implemented by the app.
type Binder interface {
	// BindSocketToMesh binds the socket to the mesh network (the radio
	// link). It returns false if there is no mesh network.
	BindSocketToMesh(fd int32) bool
	// BindSocketToInternet binds the socket to the best internet-capable
	// network other than the mesh link.
	BindSocketToInternet(fd int32) bool
}

var (
	binder  atomic.Pointer[Binder]
	protect atomic.Pointer[func(fd int32) bool]
)

// SetBinder sets the app's socket binder.
func SetBinder(b Binder) {
	if b == nil {
		binder.Store(nil)
		return
	}
	binder.Store(&b)
}

// SetProtect sets the active VPN service's protect function, used so that
// forwarding sockets never loop back into the TUN. Pass nil when the VPN
// service goes away.
func SetProtect(f func(fd int32) bool) {
	if f == nil {
		protect.Store(nil)
		return
	}
	protect.Store(&f)
}

var (
	packetsToMesh     atomic.Int64
	packetsFromStack  atomic.Int64
	packetsToInternet atomic.Int64
	dialErrors        atomic.Int64
	lastDialErr       atomic.Pointer[string]
)

// StatsJSON returns packet counters for the app's status display.
func StatsJSON() string {
	errText := ""
	if p := lastDialErr.Load(); p != nil {
		errText = *p
	}
	b, _ := json.Marshal(map[string]any{
		"toMesh":      packetsToMesh.Load(),
		"fromMesh":    packetsFromStack.Load(),
		"toInternet":  packetsToInternet.Load(),
		"dialErrors":  dialErrors.Load(),
		"lastDialErr": errText,
	})
	return string(b)
}

// netKind identifies which Android network a forwarding socket is bound to.
type netKind int

const (
	netMesh netKind = iota
	netInternet
)

func (k netKind) String() string {
	if k == netMesh {
		return "mesh"
	}
	return "internet"
}

var errNoBind = errors.New("could not bind socket to network")

// bindControl returns a net.Dialer/ListenConfig Control function that
// protects the socket from the VPN and binds it to the given network.
func bindControl(kind netKind) func(network, address string, c syscall.RawConn) error {
	return func(_, _ string, c syscall.RawConn) error {
		var ok bool
		err := c.Control(func(fd uintptr) {
			ok = bindFD(kind, int32(fd))
		})
		if err != nil {
			return err
		}
		if !ok {
			return errNoBind
		}
		return nil
	}
}

func bindFD(kind netKind, fd int32) bool {
	if p := protect.Load(); p != nil {
		(*p)(fd)
	}
	b := binder.Load()
	if b == nil {
		return false
	}
	if kind == netMesh {
		return (*b).BindSocketToMesh(fd)
	}
	return (*b).BindSocketToInternet(fd)
}

func noteDialError(kind netKind, err error) {
	dialErrors.Add(1)
	s := kind.String() + ": " + err.Error()
	lastDialErr.Store(&s)
}
