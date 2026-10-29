// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.tailscale.ipn.App
import com.tailscale.ipn.MainActivity
import com.tailscale.ipn.NetworkChangeCallback
import com.tailscale.ipn.R
import com.tailscale.ipn.util.TSLog
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import libtailscale.Libtailscale
import org.json.JSONObject

/**
 * OpenRoute mesh link manager.
 *
 * Watches for the mesh radio link (USB-C ethernet, a designated Wi-Fi network, or a radio hotspot
 * the app joins itself), works out which subnets to route to it, decides whether internet rescue is
 * needed, and reports all of that to the Go backend with [Libtailscale.setMeshState]. The Go side
 * adds the mesh routes to the VPN and forwards mesh traffic over sockets bound to the mesh network
 * via [bindSocketToMesh].
 */
object MeshRouter {
  private const val TAG = "MeshRouter"

  const val PREFS = "openroute_mesh"
  const val KEY_ENABLED = "enabled"
  const val KEY_LINK_MODE = "link_mode"
  const val KEY_WIFI_SSID = "wifi_ssid"
  const val KEY_HOTSPOT_SSID = "hotspot_ssid"
  const val KEY_HOTSPOT_PASS = "hotspot_pass"
  const val KEY_HOTSPOT_SEC = "hotspot_sec"
  const val KEY_AUTO_SUBNET = "auto_subnet"
  const val KEY_CIDRS = "mesh_cidrs"
  const val KEY_MESH_HOST = "mesh_host"
  const val KEY_WATCHDOG = "watchdog"
  const val KEY_RESCUE = "rescue"
  const val KEY_OFFLINE_MAP = "offline_map"
  private const val KEY_WIFI_LAST_SUBNET = "wifi_last_subnet"

  const val DEFAULT_CIDRS = "10.41.0.0/16" // OpenMANET's mesh subnet

  /** The widest subnet that may be routed to the mesh. Must match meshroute.MinMeshPrefixBits. */
  private const val MIN_MESH_PREFIX = 8
  /** Tailscale's address range; never routed to the mesh. */
  private const val TAILNET_CIDR = "100.64.0.0/10"

  private const val ALERT_CHANNEL_ID = "openroute-mesh-alerts"
  private const val ALERT_NOTIFICATION_ID = 41
  private const val WATCHDOG_INTERVAL_MS = 30_000L
  private const val WIFI_CANDIDATE_DELAY_MS = 8_000L
  private const val RESCUE_EVAL_DELAY_MS = 1_200L

  enum class LinkMode(val key: String) {
    ETHERNET("ethernet"),
    WIFI("wifi"),
    HOTSPOT("hotspot");

    companion object {
      fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ETHERNET
    }
  }

  data class Status(
      val enabled: Boolean = true,
      val linkMode: LinkMode = LinkMode.ETHERNET,
      val linkUp: Boolean = false,
      /** Human-readable link state, e.g. "up (eth0: 10.41.3.7/16)". */
      val linkDetail: String = "not connected",
      /** Subnets currently routed to the mesh. */
      val routes: List<String> = emptyList(),
      val localAddr: String? = null,
      val interfaceName: String? = null,
      val prefixLength: Int = 0,
      /** Where non-tailnet internet traffic goes, e.g. "Cellular". */
      val internet: String = "",
      val rescueActive: Boolean = false,
      val meshHostStatus: String? = null,
      val meshHostAlive: Boolean? = null,
      val error: String? = null,
  )

  data class Counters(
      val toMesh: Long = 0,
      val fromMesh: Long = 0,
      val toInternet: Long = 0,
      val dialErrors: Long = 0,
      val lastDialError: String = "",
  )

  private val _status = MutableStateFlow(Status())
  val status: StateFlow<Status> = _status

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val handler = Handler(Looper.getMainLooper())

  private lateinit var appContext: Context
  private lateinit var prefs: SharedPreferences
  private lateinit var cm: ConnectivityManager

  // All mutable link state below is touched only on the main thread.
  @Volatile private var meshNetwork: Network? = null
  private var meshLinkProps: LinkProperties? = null
  private var meshValidated = false
  private var underlyingDefault: Network? = null
  private var underlyingValidated = false
  @Volatile private var internetNetwork: Network? = null
  private var linkCallback: ConnectivityManager.NetworkCallback? = null
  private var wifiHoldCallback: ConnectivityManager.NetworkCallback? = null
  private var cellCallback: ConnectivityManager.NetworkCallback? = null
  private var defaultCallback: ConnectivityManager.NetworkCallback? = null
  private var targetSsid = ""
  private var watchdogJob: Job? = null
  private var initialized = false

  private val rescueEval = Runnable { evaluate() }

  /** Starts watching for the mesh link. Safe to call more than once. */
  fun init(context: Context) {
    handler.post {
      if (initialized) return@post
      initialized = true
      appContext = context.applicationContext
      prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
      createAlertChannel()
      watchDefaultNetwork()
      startLinkWatch()
      startWatchdog()
    }
  }

  fun prefs(context: Context): SharedPreferences =
      context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  /** Re-reads the settings after the user changes them. */
  fun settingsChanged() {
    handler.post {
      if (!initialized) return@post
      stopLinkWatch()
      startLinkWatch()
      evaluate()
    }
  }

  /** The mesh network, for probes run from the UI. */
  fun meshNetwork(): Network? = meshNetwork

  fun meshLinkProperties(): LinkProperties? = meshLinkProps

  fun counters(): Counters {
    return try {
      val o = JSONObject(Libtailscale.meshStatsJSON())
      Counters(
          toMesh = o.optLong("toMesh"),
          fromMesh = o.optLong("fromMesh"),
          toInternet = o.optLong("toInternet"),
          dialErrors = o.optLong("dialErrors"),
          lastDialError = o.optString("lastDialErr"),
      )
    } catch (e: Exception) {
      Counters()
    }
  }

  // Socket binding, called from Go (via App's AppContext implementation).

  fun bindSocketToMesh(fd: Int): Boolean = bindTo(meshNetwork, fd)

  fun bindSocketToInternet(fd: Int): Boolean {
    val mesh = meshNetwork
    val net = internetNetwork ?: NetworkChangeCallback.cachedDefaultNetwork?.takeIf { it != mesh }
    return bindTo(net, fd)
  }

  private fun bindTo(net: Network?, fd: Int): Boolean {
    if (net == null) return false
    return try {
      ParcelFileDescriptor.fromFd(fd).use { pfd -> net.bindSocket(pfd.fileDescriptor) }
      true
    } catch (e: Exception) {
      TSLog.w(TAG, "bindSocket fd=$fd to $net failed: $e")
      false
    }
  }

  // Default-network tracking, for internet rescue.

  private fun watchDefaultNetwork() {
    val cb =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            handler.post {
              underlyingDefault = network
              scheduleEvaluate()
            }
          }

          override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            handler.post {
              if (network == underlyingDefault) {
                underlyingValidated =
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
              }
              if (network == meshNetwork) {
                meshValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
              }
              scheduleEvaluate()
            }
          }

          override fun onLost(network: Network) {
            handler.post {
              if (network == underlyingDefault) {
                underlyingDefault = null
                underlyingValidated = false
              }
              scheduleEvaluate()
            }
          }
        }
    defaultCallback = cb
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // The best non-VPN internet network is the "real" default underneath our VPN.
        val req =
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        cm.registerBestMatchingNetworkCallback(req, cb, handler)
      } else {
        cm.registerDefaultNetworkCallback(cb)
      }
    } catch (e: Exception) {
      TSLog.w(TAG, "default network watch failed: $e")
    }
  }

  // Mesh link watching.

  private fun startLinkWatch() {
    val mode = LinkMode.from(prefs.getString(KEY_LINK_MODE, LinkMode.ETHERNET.key))
    var detail = "not connected"
    var error: String? = null
    if (!prefs.getBoolean(KEY_ENABLED, true)) {
      publish(linkDetail = "mesh routing off")
      return
    }
    when (mode) {
      LinkMode.ETHERNET -> {
        val req =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        val cb = directCallback()
        linkCallback = cb
        cm.requestNetwork(req, cb, handler)
        detail = "waiting for ethernet"
      }
      LinkMode.WIFI -> {
        targetSsid = prefs.getString(KEY_WIFI_SSID, "")!!.trim()
        detail =
            if (targetSsid.isEmpty()) "no Wi-Fi network chosen"
            else "waiting for Wi-Fi “$targetSsid”"
        val req =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        val cb = wifiWatchCallback()
        linkCallback = cb
        cm.registerNetworkCallback(req, cb, handler)
        // Keep the Wi-Fi network up even if Android would drop it for having no internet.
        val hold = object : ConnectivityManager.NetworkCallback() {}
        wifiHoldCallback = hold
        try {
          cm.requestNetwork(req, hold, handler)
        } catch (e: Exception) {
          TSLog.w(TAG, "wifi hold request failed: $e")
        }
      }
      LinkMode.HOTSPOT -> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
          publish(linkDetail = "not connected", error = "Joining a radio hotspot needs Android 10+")
          return
        }
        val ssid = prefs.getString(KEY_HOTSPOT_SSID, "")!!.trim()
        val pass = hotspotPassword(appContext)
        if (ssid.isEmpty()) {
          publish(linkDetail = "no radio hotspot chosen")
          return
        }
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (pass.isNotEmpty()) {
          if (prefs.getString(KEY_HOTSPOT_SEC, "wpa2") == "wpa3") spec.setWpa3Passphrase(pass)
          else spec.setWpa2Passphrase(pass)
        }
        // A local-only request: the hotspot never competes for the internet slot.
        val req =
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec.build())
                .build()
        val cb = directCallback()
        linkCallback = cb
        try {
          cm.requestNetwork(req, cb, handler)
          detail = "searching for “$ssid”…"
        } catch (e: Exception) {
          TSLog.w(TAG, "hotspot request rejected: $e")
          error = "Couldn't request the hotspot — open OpenRoute and try again"
        }
      }
    }
    publish(linkDetail = detail, error = error)
  }

  private fun stopLinkWatch() {
    for (cb in listOfNotNull(linkCallback, wifiHoldCallback)) {
      try {
        cm.unregisterNetworkCallback(cb)
      } catch (_: Exception) {}
    }
    linkCallback = null
    wifiHoldCallback = null
    meshNetwork = null
    meshLinkProps = null
    meshValidated = false
  }

  private fun directCallback() =
      object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
          adoptMesh(network)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
          if (network == meshNetwork) {
            meshValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            scheduleEvaluate()
          }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
          if (network == meshNetwork) {
            meshLinkProps = lp
            evaluate()
          }
        }

        override fun onLost(network: Network) {
          meshLost(network)
        }

        override fun onUnavailable() {
          publish(
              linkDetail = "not connected",
              error =
                  "Couldn't join the hotspot — check password and range. Static-IP radio? Join it in Android Wi-Fi settings and use “Wi-Fi network” mode.",
          )
        }
      }

  private fun wifiWatchCallback(): ConnectivityManager.NetworkCallback =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Without this flag, Android redacts the SSID from the callback's WifiInfo.
        WifiWatchCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
      } else {
        WifiWatchCallback()
      }

  private class WifiWatchCallback : ConnectivityManager.NetworkCallback {
    constructor() : super()

    @RequiresApi(Build.VERSION_CODES.S) constructor(flags: Int) : super(flags)

    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
      if (network == meshNetwork) {
        meshValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        scheduleEvaluate()
      } else {
        wifiCandidate(network, caps)
      }
    }

    override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
      if (network == meshNetwork) {
        meshLinkProps = lp
        evaluate()
      }
    }

    override fun onLost(network: Network) {
      meshLost(network)
    }
  }

  /**
   * Decides whether a Wi-Fi network is the designated mesh network. Android hides the SSID from
   * background apps without location access, so fall back to the last mesh subnet seen, and finally
   * to "the Wi-Fi network without internet".
   */
  private fun wifiCandidate(network: Network, caps: NetworkCapabilities) {
    if (meshNetwork != null || targetSsid.isEmpty()) return
    var ssid =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          (caps.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
        } else {
          null
        }
    if (ssid == null || ssid == WifiManager.UNKNOWN_SSID) {
      @Suppress("DEPRECATION")
      ssid =
          try {
            (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .connectionInfo
                ?.ssid
                ?.removeSurrounding("\"")
          } catch (_: Exception) {
            null
          }
    }
    if (ssid != null && ssid != WifiManager.UNKNOWN_SSID) {
      if (ssid == targetSsid) {
        TSLog.d(TAG, "designated Wi-Fi “$ssid” present — adopting as mesh link")
        adoptMesh(network)
      }
      return
    }
    // The name is hidden (Android does this for apps in the background). Only a network on the
    // same subnet the designated Wi-Fi had last time, inside the mesh ranges, is taken on trust;
    // anything else waits until OpenRoute is opened and can read the name.
    val lastSubnet = prefs.getString(KEY_WIFI_LAST_SUBNET, "")
    val subnet = cm.getLinkProperties(network)?.let { subnetOf(it) }
    if (
        !subnet.isNullOrEmpty() &&
            subnet == lastSubnet &&
            meshRanges().any { intersect(subnet, it) != null }
    ) {
      TSLog.d(TAG, "Wi-Fi matches last mesh subnet $subnet — adopting as mesh link")
      adoptMesh(network)
      return
    }
    publish(linkDetail = "Wi-Fi name hidden — open OpenRoute to check for “$targetSsid”")
  }

  /**
   * Looks again for the designated Wi-Fi network. Called when OpenRoute comes to the foreground,
   * where Android lets it read Wi-Fi names.
   */
  fun recheckWifi() {
    handler.post {
      if (!initialized || meshNetwork != null || targetSsid.isEmpty()) return@post
      if (LinkMode.from(prefs.getString(KEY_LINK_MODE, null)) != LinkMode.WIFI) return@post
      @Suppress("DEPRECATION") val networks = cm.allNetworks
      for (network in networks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) wifiCandidate(network, caps)
        if (meshNetwork != null) return@post
      }
    }
  }

  private fun adoptMesh(network: Network) {
    meshNetwork = network
    meshLinkProps = cm.getLinkProperties(network)
    meshValidated =
        cm.getNetworkCapabilities(network)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    TSLog.d(TAG, "mesh link up: ${meshLinkProps?.interfaceName}")
    evaluate()
  }

  private fun meshLost(network: Network) {
    if (network != meshNetwork) return
    TSLog.d(TAG, "mesh link lost")
    meshNetwork = null
    meshLinkProps = null
    meshValidated = false
    evaluate()
  }

  // Evaluation: work out routes and rescue, and report them to Go.

  private fun scheduleEvaluate() {
    handler.removeCallbacks(rescueEval)
    handler.postDelayed(rescueEval, RESCUE_EVAL_DELAY_MS)
  }

  private fun evaluate() {
    handler.removeCallbacks(rescueEval)
    val mesh = meshNetwork
    val lp = meshLinkProps
    val enabled = prefs.getBoolean(KEY_ENABLED, true)
    val v4 = lp?.linkAddresses?.firstOrNull { it.address is Inet4Address }

    // The mesh ranges bound what may be routed: in auto mode, the link's own subnet is routed only
    // where it falls inside them, so a radio (or a network mistaken for one) can't claim other
    // addresses by handing out a wide subnet.
    val ranges = meshRanges()
    var refused: String? = null
    val routes =
        if (!enabled || mesh == null) {
          emptyList()
        } else if (prefs.getBoolean(KEY_AUTO_SUBNET, true)) {
          val sub = lp?.let { subnetOf(it) }
          val inRange = sub?.let { s -> ranges.mapNotNull { intersect(s, it) }.distinct() }.orEmpty()
          if (sub != null && inRange.isEmpty()) {
            refused = "Link subnet $sub is outside the mesh ranges (${ranges.joinToString()}) — not routed"
            TSLog.w(TAG, refused)
          }
          inRange
        } else {
          ranges
        }
    if (
        routes.isNotEmpty() && LinkMode.from(prefs.getString(KEY_LINK_MODE, null)) == LinkMode.WIFI
    ) {
      lp?.let { subnetOf(it) }?.let { prefs.edit().putString(KEY_WIFI_LAST_SUBNET, it).apply() }
    }

    // Rescue when the mesh is up but Android's default network has no working internet — most
    // often because a static-IP radio's Wi-Fi took the default slot.
    val meshIsDefault = mesh != null && underlyingDefault == mesh
    val wantRescue =
        enabled &&
            routes.isNotEmpty() &&
            prefs.getBoolean(KEY_RESCUE, true) &&
            (underlyingDefault == null ||
                (meshIsDefault && !meshValidated) ||
                (!meshIsDefault && !underlyingValidated))
    if (wantRescue) acquireCellular() else releaseCellular()
    val rescueActive = wantRescue && internetNetwork != null

    Libtailscale.setMeshState(
        routes.joinToString(","),
        v4?.address?.hostAddress ?: "",
        rescueActive,
    )

    val iface = lp?.interfaceName
    val linkDetail =
        when {
          !enabled -> "mesh routing off"
          mesh == null ->
              _status.value.linkDetail.takeIf { !_status.value.linkUp } ?: "not connected"
          else -> "up (${iface ?: "?"}: ${v4?.toString() ?: "no IPv4 address"})"
        }
    publish(
        linkDetail = linkDetail,
        routes = routes,
        localAddr = v4?.address?.hostAddress,
        interfaceName = iface,
        prefixLength = v4?.prefixLength ?: 0,
        rescueActive = rescueActive,
        error = refused ?: if (mesh != null && v4 == null) "No IPv4 address on the mesh link" else null,
    )
  }

  private fun acquireCellular() {
    if (cellCallback != null) return
    val req =
        NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    val cb =
        object : ConnectivityManager.NetworkCallback() {
          override fun onAvailable(network: Network) {
            handler.post {
              internetNetwork = network
              evaluate()
            }
          }

          override fun onLost(network: Network) {
            handler.post {
              if (internetNetwork == network) internetNetwork = null
              evaluate()
            }
          }
        }
    cellCallback = cb
    try {
      cm.requestNetwork(req, cb, handler)
    } catch (e: Exception) {
      TSLog.w(TAG, "cellular request failed: $e")
    }
  }

  private fun releaseCellular() {
    cellCallback?.let {
      try {
        cm.unregisterNetworkCallback(it)
      } catch (_: Exception) {}
    }
    cellCallback = null
    internetNetwork = null
  }

  private fun internetDescription(rescueActive: Boolean): String {
    if (rescueActive) return "Cellular (rescue)"
    val def = underlyingDefault ?: return "none"
    if (def == meshNetwork) return if (meshValidated) "Mesh gateway" else "none (radio took over)"
    val caps = cm.getNetworkCapabilities(def) ?: return "unknown"
    val kind =
        when {
          caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
          caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
          caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
          else -> "Other"
        }
    return if (underlyingValidated) kind else "$kind (no internet)"
  }

  private fun publish(
      linkDetail: String = _status.value.linkDetail,
      routes: List<String> = _status.value.routes,
      localAddr: String? = _status.value.localAddr,
      interfaceName: String? = _status.value.interfaceName,
      prefixLength: Int = _status.value.prefixLength,
      rescueActive: Boolean = _status.value.rescueActive,
      error: String? = null,
  ) {
    _status.value =
        _status.value.copy(
            enabled = prefs.getBoolean(KEY_ENABLED, true),
            linkMode = LinkMode.from(prefs.getString(KEY_LINK_MODE, null)),
            linkUp = meshNetwork != null,
            linkDetail = linkDetail,
            routes = routes,
            localAddr = localAddr,
            interfaceName = interfaceName,
            prefixLength = prefixLength,
            internet = internetDescription(rescueActive),
            rescueActive = rescueActive,
            error = error,
        )
  }

  // Watchdog: ping the mesh host and alert when it goes silent.

  private fun startWatchdog() {
    watchdogJob?.cancel()
    watchdogJob = scope.launch {
      var alerted = false
      while (isActive) {
        val enabled = prefs.getBoolean(KEY_WATCHDOG, false)
        val host = prefs.getString(KEY_MESH_HOST, "")!!.trim()
        val net = meshNetwork
        if (!enabled || host.isEmpty()) {
          setHostStatus(null, null)
          if (alerted) cancelAlert()
          alerted = false
          delay(5_000)
          continue
        }
        if (net == null) {
          setHostStatus("link down", null)
          delay(10_000)
          continue
        }
        val rtt = MeshProbe.ping(net, host, 2000) ?: MeshProbe.ping(net, host, 2000)
        if (rtt != null) {
          setHostStatus("responding ($rtt ms)", true)
          if (alerted) cancelAlert()
          alerted = false
        } else {
          setHostStatus("NOT responding", false)
          if (!alerted) postAlert(host)
          alerted = true
        }
        delay(WATCHDOG_INTERVAL_MS)
      }
    }
  }

  private fun setHostStatus(text: String?, alive: Boolean?) {
    val s = _status.value
    if (s.meshHostStatus != text || s.meshHostAlive != alive) {
      _status.value = s.copy(meshHostStatus = text, meshHostAlive = alive)
    }
  }

  private fun createAlertChannel() {
    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.createNotificationChannel(
        NotificationChannel(
            ALERT_CHANNEL_ID,
            appContext.getString(R.string.mesh_alerts_channel),
            NotificationManager.IMPORTANCE_HIGH,
        )
    )
  }

  private fun postAlert(host: String) {
    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val pi =
        PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
    val n =
        NotificationCompat.Builder(appContext, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.mesh_host_alert_title))
            .setContentText(appContext.getString(R.string.mesh_host_alert_text, host))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
    try {
      nm.notify(ALERT_NOTIFICATION_ID, n)
    } catch (e: SecurityException) {
      TSLog.w(TAG, "can't post mesh alert: $e")
    }
  }

  private fun cancelAlert() {
    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.cancel(ALERT_NOTIFICATION_ID)
  }

  // Subnet helpers.

  /** The IPv4 subnet of the link's first IPv4 address, e.g. "10.41.0.0/16". */
  fun subnetOf(lp: LinkProperties): String? {
    val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
    return cidrOf(la.address.address, la.prefixLength)
  }

  private fun cidrOf(addr: ByteArray, prefix: Int): String {
    var a = 0L
    for (b in addr) a = (a shl 8) or (b.toLong() and 0xff)
    val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val net = a and mask
    return "${(net shr 24) and 0xff}.${(net shr 16) and 0xff}.${(net shr 8) and 0xff}.${net and 0xff}/$prefix"
  }

  /** The configured mesh ranges, the outer bound for anything routed to the mesh. */
  private fun meshRanges(): List<String> = parseCidrs(prefs.getString(KEY_CIDRS, DEFAULT_CIDRS)!!)

  /**
   * Parses comma-separated IPv4 CIDRs, normalizing each; invalid or unsafe (see [unsafeReason])
   * entries are dropped.
   */
  fun parseCidrs(text: String): List<String> =
      text.split(',').mapNotNull { normalizeCidr(it.trim()) }.filter { unsafeReason(it) == null }

  /**
   * Why a normalized CIDR can't be routed to the mesh, or null if it can. Mesh traffic leaves the
   * phone unencrypted, so subnets wider than /8 or covering the tailnet's addresses are refused.
   */
  fun unsafeReason(cidr: String): String? {
    val (_, bits) = split(cidr) ?: return "not a CIDR"
    if (bits < MIN_MESH_PREFIX) return "wider than /$MIN_MESH_PREFIX"
    if (overlaps(cidr, TAILNET_CIDR)) return "overlaps the tailnet range $TAILNET_CIDR"
    return null
  }

  /** The narrower of a and b if one contains the other, else null. */
  private fun intersect(a: String, b: String): String? {
    val (na, ba) = split(a) ?: return null
    val (nb, bb) = split(b) ?: return null
    val bits = minOf(ba, bb)
    return if (na and maskOf(bits) == nb and maskOf(bits)) (if (ba >= bb) a else b) else null
  }

  private fun overlaps(a: String, b: String) = intersect(a, b) != null

  private fun split(cidr: String): Pair<Long, Int>? {
    val norm = normalizeCidr(cidr) ?: return null
    val (addr, bits) = norm.split('/')
    var a = 0L
    for (o in addr.split('.')) a = (a shl 8) or o.toLong()
    return a to bits.toInt()
  }

  private fun maskOf(bits: Int) = if (bits == 0) 0L else (0xffffffffL shl (32 - bits)) and 0xffffffffL

  // Secrets and the offline tailnet map.

  /** The radio hotspot password, kept in encrypted preferences. */
  fun hotspotPassword(context: Context): String {
    val plain = prefs(context)
    val secret = App.get().getEncryptedPrefs()
    // Move a password saved in the clear by OpenRoute 1.1.1 or earlier.
    plain.getString(KEY_HOTSPOT_PASS, null)?.let {
      secret.edit().putString(KEY_HOTSPOT_PASS, it).commit()
      plain.edit().remove(KEY_HOTSPOT_PASS).commit()
    }
    return secret.getString(KEY_HOTSPOT_PASS, "")!!
  }

  fun setHotspotPassword(context: Context, pass: String) {
    prefs(context).edit().remove(KEY_HOTSPOT_PASS).commit()
    App.get().getEncryptedPrefs().edit().putString(KEY_HOTSPOT_PASS, pass).commit()
  }

  fun offlineMapEnabled(context: Context) = prefs(context).getBoolean(KEY_OFFLINE_MAP, true)

  /** Turns the saved tailnet map on or off; off also deletes it. */
  fun setOfflineMapEnabled(context: Context, on: Boolean) {
    prefs(context).edit().putBoolean(KEY_OFFLINE_MAP, on).apply()
    scope.launch { Libtailscale.setOfflineMapEnabled(on) }
  }

  /** Deletes the saved tailnet map now. */
  fun wipeOfflineMap() {
    scope.launch { Libtailscale.clearOfflineMap() }
  }

  /** Returns the normalized form of an IPv4 CIDR, or null if it isn't one. */
  fun normalizeCidr(s: String): String? {
    val parts = s.split('/')
    if (parts.size != 2) return null
    val prefix = parts[1].toIntOrNull()?.takeIf { it in 1..32 } ?: return null
    val octets = parts[0].split('.')
    if (octets.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, o) in octets.withIndex()) {
      val v = o.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
      bytes[i] = v.toByte()
    }
    return cidrOf(bytes, prefix)
  }
}
