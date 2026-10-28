// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

//go:build !linux

package meshroute

// pingOver is only implemented on Linux (including Android), which allows
// unprivileged ICMP sockets.
func pingOver(kind netKind, pkt []byte, ihl int) []byte { return nil }
