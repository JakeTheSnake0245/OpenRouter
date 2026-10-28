// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

// OpenRoute mesh routing glue; see package meshroute.

package libtailscale

import (
	"github.com/tailscale/tailscale-android/libtailscale/meshroute"
	"tailscale.com/wgengine/magicsock"
)

func init() {
	magicsock.ListenPacketHook = meshroute.ListenPacket
}

// onMeshStateChanged is notified when SetMeshState changes the mesh state,
// so the backend can rebuild the TUN.
var onMeshStateChanged = make(chan struct{}, 1)

// SetMeshState is called by the Android app whenever the mesh link or the
// mesh settings change. prefixes is a comma-separated list of IPv4 CIDRs to
// route to the mesh (empty when the link is down), localAddr is the device's
// IPv4 address on the mesh link (may be empty), and rescue reports whether
// internet traffic should be carried over the internet network because the
// mesh link has become Android's default network.
func SetMeshState(prefixes, localAddr string, rescue bool) {
	if !meshroute.SetState(prefixes, localAddr, rescue) {
		return
	}
	select {
	case onMeshStateChanged <- struct{}{}:
	default:
	}
}

// MeshStatsJSON returns mesh packet counters for the app's status display.
func MeshStatsJSON() string {
	return meshroute.StatsJSON()
}
