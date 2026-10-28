// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package meshroute

import (
	"encoding/binary"
	"log"
	"syscall"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip/header"
)

// pingOver sends the echo request in pkt (an IPv4 packet whose header is
// ihl bytes long) with an unprivileged ICMP socket bound to the given
// network, and returns the echo reply packet to write to the TUN, or nil.
func pingOver(kind netKind, pkt []byte, ihl int) []byte {
	var src, dst [4]byte
	copy(src[:], pkt[12:16])
	copy(dst[:], pkt[16:20])
	icmpReq := pkt[ihl:]
	id := binary.BigEndian.Uint16(icmpReq[4:6])
	seq := binary.BigEndian.Uint16(icmpReq[6:8])
	payload := icmpReq[8:]

	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_DGRAM|syscall.SOCK_CLOEXEC, syscall.IPPROTO_ICMP)
	if err != nil {
		log.Printf("mesh: ping socket: %v", err)
		return nil
	}
	defer syscall.Close(fd)
	if !bindFD(kind, int32(fd)) {
		noteDialError(kind, errNoBind)
		return nil
	}
	tv := syscall.NsecToTimeval(meshPingTimeout.Nanoseconds())
	syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &tv)

	// The kernel replaces the echo identifier with the socket's port and
	// fills in the checksum, so send our own copy of the message.
	out := append([]byte(nil), icmpReq...)
	if err := syscall.Sendto(fd, out, 0, &syscall.SockaddrInet4{Addr: dst}); err != nil {
		return nil
	}
	deadline := time.Now().Add(meshPingTimeout)
	buf := make([]byte, 65535)
	for time.Now().Before(deadline) {
		n, from, err := syscall.Recvfrom(fd, buf, 0)
		if err != nil {
			return nil
		}
		sa, ok := from.(*syscall.SockaddrInet4)
		if !ok || sa.Addr != dst || n < header.ICMPv4MinimumSize {
			continue
		}
		if buf[0] != byte(header.ICMPv4EchoReply) || binary.BigEndian.Uint16(buf[6:8]) != seq {
			continue
		}
		return buildEchoReply(dst, src, id, seq, payload)
	}
	return nil
}
