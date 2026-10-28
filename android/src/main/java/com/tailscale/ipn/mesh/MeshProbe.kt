// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mesh

import android.net.LinkProperties
import android.net.Network
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/** Reachability probes over the mesh network, used by Test Mesh, Find Devices and the watchdog. */
object MeshProbe {
  private const val TCP_PROBE_TIMEOUT_MS = 1500
  private const val SCAN_PING_TIMEOUT_MS = 300
  private const val SCAN_PARALLELISM = 256
  private const val MIN_SCAN_PREFIX = 16

  /** Pings host over net with an unprivileged ICMP socket; returns the RTT in ms, or null. */
  fun ping(net: Network, host: String, timeoutMs: Int): Long? {
    return try {
      val addr = InetAddress.getByName(host)
      val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
      try {
        net.bindSocket(fd)
        Os.setsockoptTimeval(
            fd,
            OsConstants.SOL_SOCKET,
            OsConstants.SO_RCVTIMEO,
            StructTimeval.fromMillis(timeoutMs.toLong()),
        )
        Os.connect(fd, addr, 0)
        // Echo request; the kernel fills in the identifier and checksum.
        val msg = ByteArray(16)
        msg[0] = 8
        val t0 = SystemClock.elapsedRealtime()
        Os.write(fd, msg, 0, msg.size)
        val buf = ByteArray(1500)
        val n = Os.read(fd, buf, 0, buf.size)
        if (n >= 8 && buf[0].toInt() == 0) SystemClock.elapsedRealtime() - t0 else null
      } finally {
        try {
          Os.close(fd)
        } catch (_: Exception) {}
      }
    } catch (_: Exception) {
      null
    }
  }

  /** Tries a TCP connection over net; returns "open", "refused", "timeout" or "unreachable". */
  fun tcpProbe(
      net: Network,
      host: String,
      port: Int,
      timeoutMs: Int = TCP_PROBE_TIMEOUT_MS,
  ): String {
    return try {
      Socket().use { s ->
        net.bindSocket(s)
        s.connect(InetSocketAddress(InetAddress.getByName(host), port), timeoutMs)
        "open"
      }
    } catch (e: ConnectException) {
      if (e.message?.contains("refused", ignoreCase = true) == true) "refused" else "unreachable"
    } catch (_: SocketTimeoutException) {
      "timeout"
    } catch (_: Exception) {
      "unreachable"
    }
  }

  /** Runs the field diagnostic, reporting each line as it is produced. */
  suspend fun testMesh(host: String, report: (String) -> Unit) =
      withContext(Dispatchers.IO) {
        val net = MeshRouter.meshNetwork()
        val lp = MeshRouter.meshLinkProperties()
        if (net == null) {
          report("✗ No mesh link — connect the cable or join the radio's Wi-Fi")
          return@withContext
        }
        val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }
        report("✓ Link up (${lp?.interfaceName ?: "?"}: ${v4 ?: "no IPv4 address"})")
        if (v4 == null) {
          report("✗ No IPv4 on the link — check the phone's IP config")
          return@withContext
        }
        val routes = MeshRouter.status.value.routes
        report(
            if (routes.isEmpty()) "✗ No mesh routes active"
            else "✓ Routing ${routes.joinToString()}"
        )
        if (host.isBlank()) {
          report("• Set a mesh host IP above for reachability tests")
          return@withContext
        }
        val rtts = (1..3).mapNotNull { ping(net, host, 2000) }
        report(
            if (rtts.isNotEmpty()) "✓ Ping $host: ${rtts.min()} ms (${rtts.size}/3 replies)"
            else "• Ping $host: no reply"
        )
        val tcp = tcpProbe(net, host, 80)
        report("• TCP $host:80 → $tcp")
        report(
            when {
              rtts.isNotEmpty() -> "VERDICT: mesh link healthy"
              tcp == "open" || tcp == "refused" -> "VERDICT: host reachable (blocks ping)"
              else -> "VERDICT: nothing answering at $host — check radio power and IP"
            }
        )
      }

  data class Device(val address: String, val rttMs: Long)

  /**
   * Pings every address on the mesh link's subnet (at most a /16) and reports each live device.
   * Returns the devices found, sorted by address.
   */
  @OptIn(ExperimentalCoroutinesApi::class)
  suspend fun discover(
      onProgress: (done: Int, total: Int, found: Int) -> Unit,
      onFound: (Device) -> Unit,
  ): List<Device> =
      withContext(Dispatchers.IO) {
        val net = MeshRouter.meshNetwork() ?: return@withContext emptyList()
        val lp: LinkProperties = MeshRouter.meshLinkProperties() ?: return@withContext emptyList()
        val la =
            lp.linkAddresses.firstOrNull { it.address is Inet4Address }
                ?: return@withContext emptyList()
        val prefix = maxOf(la.prefixLength, MIN_SCAN_PREFIX)
        val self = toLong(la.address.address)
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val base = self and mask
        val total = ((1L shl (32 - prefix)) - 2).toInt()
        val found = java.util.Collections.synchronizedList(mutableListOf<Device>())
        val done = AtomicInteger()
        val sem = Semaphore(SCAN_PARALLELISM)
        coroutineScope {
          for (i in 1..total) {
            ensureActive()
            val ip = base + i
            if (ip == self) {
              done.incrementAndGet()
              continue
            }
            sem.acquire()
            launch(Dispatchers.IO.limitedParallelism(SCAN_PARALLELISM)) {
              try {
                val host = fromLong(ip)
                ping(net, host, SCAN_PING_TIMEOUT_MS)?.let { rtt ->
                  val d = Device(host, rtt)
                  found.add(d)
                  onFound(d)
                }
                val n = done.incrementAndGet()
                if (n % 64 == 0 || n == total) onProgress(n, total, found.size)
              } finally {
                sem.release()
              }
            }
          }
        }
        onProgress(total, total, found.size)
        found.sortedBy { toLong(InetAddress.getByName(it.address).address) }
      }

  private fun toLong(b: ByteArray): Long {
    var a = 0L
    for (x in b) a = (a shl 8) or (x.toLong() and 0xff)
    return a
  }

  private fun fromLong(a: Long) =
      "${(a shr 24) and 0xff}.${(a shr 16) and 0xff}.${(a shr 8) and 0xff}.${a and 0xff}"
}
