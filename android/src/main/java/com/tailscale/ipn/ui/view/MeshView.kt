// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tailscale.ipn.R
import com.tailscale.ipn.mesh.MeshProbe
import com.tailscale.ipn.mesh.MeshRouter
import com.tailscale.ipn.mesh.MeshRouter.LinkMode
import com.tailscale.ipn.ui.util.Lists
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val statusGreen = Color(0xFF3FB950)
internal val statusAmber = Color(0xFFD29922)
internal val statusRed = Color(0xFFE5534B)
internal val statusGrey = Color(0xFF8B949E)

internal fun meshColor(s: MeshRouter.Status) =
    when {
      !s.enabled -> statusGrey
      s.routes.isNotEmpty() && s.meshHostAlive != false -> statusGreen
      s.linkUp -> statusAmber
      else -> statusGrey
    }

@Composable
private fun StatusDot(color: Color) {
  Box(Modifier.size(12.dp).background(color, CircleShape))
}

@Composable
fun MeshView(onBack: () -> Unit) {
  val context = LocalContext.current
  val prefs = remember { MeshRouter.prefs(context) }
  val status by MeshRouter.status.collectAsState()
  val scope = rememberCoroutineScope()

  var enabled by remember { mutableStateOf(prefs.getBoolean(MeshRouter.KEY_ENABLED, true)) }
  var linkMode by remember {
    mutableStateOf(LinkMode.from(prefs.getString(MeshRouter.KEY_LINK_MODE, null)))
  }
  var wifiSsid by remember { mutableStateOf(prefs.getString(MeshRouter.KEY_WIFI_SSID, "")!!) }
  var hotspotSsid by remember { mutableStateOf(prefs.getString(MeshRouter.KEY_HOTSPOT_SSID, "")!!) }
  var hotspotPass by remember { mutableStateOf(prefs.getString(MeshRouter.KEY_HOTSPOT_PASS, "")!!) }
  var hotspotSec by remember {
    mutableStateOf(prefs.getString(MeshRouter.KEY_HOTSPOT_SEC, "wpa2")!!)
  }
  var autoSubnet by remember { mutableStateOf(prefs.getBoolean(MeshRouter.KEY_AUTO_SUBNET, true)) }
  var cidrs by remember {
    mutableStateOf(prefs.getString(MeshRouter.KEY_CIDRS, MeshRouter.DEFAULT_CIDRS)!!)
  }
  var meshHost by remember { mutableStateOf(prefs.getString(MeshRouter.KEY_MESH_HOST, "")!!) }
  var watchdog by remember { mutableStateOf(prefs.getBoolean(MeshRouter.KEY_WATCHDOG, false)) }
  var rescue by remember { mutableStateOf(prefs.getBoolean(MeshRouter.KEY_RESCUE, true)) }

  var counters by remember { mutableStateOf(MeshRouter.Counters()) }
  LaunchedEffect(Unit) {
    while (true) {
      counters = MeshRouter.counters()
      delay(1000)
    }
  }

  val testLines = remember { mutableStateListOf<String>() }
  var testJob by remember { mutableStateOf<Job?>(null) }
  var scanJob by remember { mutableStateOf<Job?>(null) }
  var scanProgress by remember { mutableStateOf("") }
  var devices by remember { mutableStateOf<List<MeshProbe.Device>?>(null) }
  var wifiChoices by remember { mutableStateOf<List<WifiChoice>?>(null) }

  fun save(apply: android.content.SharedPreferences.Editor.() -> Unit) {
    prefs.edit().apply(apply).apply()
    MeshRouter.settingsChanged()
  }

  val locationPermission =
      rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) wifiChoices = scanWifi(context)
      }

  Scaffold(topBar = { Header(R.string.mesh_title, onBack = onBack) }) { innerPadding ->
    LazyColumn(modifier = Modifier.padding(innerPadding)) {
      item("status") {
        Lists.MutedHeader(stringResource(R.string.mesh_status_header))
        StatusRow(
            stringResource(R.string.mesh_status_router),
            if (status.routes.isNotEmpty()) "Routing ${status.routes.joinToString()}"
            else if (status.enabled) "waiting for mesh link" else "off",
            if (status.routes.isNotEmpty()) statusGreen else statusGrey,
        )
        StatusRow(
            stringResource(R.string.mesh_status_link, linkModeLabel(status.linkMode)),
            status.linkDetail,
            if (status.linkUp) statusGreen else statusGrey,
        )
        StatusRow(
            stringResource(R.string.mesh_status_internet),
            status.internet,
            when {
              status.rescueActive -> statusAmber
              status.internet.startsWith("none") -> statusRed
              else -> statusGreen
            },
        )
        status.meshHostStatus?.let {
          StatusRow(
              stringResource(R.string.mesh_status_host),
              it,
              when (status.meshHostAlive) {
                true -> statusGreen
                false -> statusRed
                null -> statusGrey
              },
          )
        }
        Text(
            "→ mesh ${counters.toMesh}    ← mesh ${counters.fromMesh}" +
                if (counters.toInternet > 0) "    → cellular ${counters.toInternet}" else "",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        status.error?.let { ErrorText(it) }
        if (counters.dialErrors > 0 && counters.lastDialError.isNotEmpty()) {
          Text(
              stringResource(R.string.mesh_last_error, counters.lastDialError),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(horizontal = 16.dp),
          )
        }
      }

      item("enabled") {
        Lists.SectionDivider()
        Setting.Switch(
            R.string.mesh_enabled,
            subtitle = stringResource(R.string.mesh_enabled_subtitle),
            isOn = enabled,
            onToggle = {
              enabled = it
              save { putBoolean(MeshRouter.KEY_ENABLED, it) }
            },
        )
      }

      item("link") {
        Lists.SectionDivider()
        Lists.MutedHeader(stringResource(R.string.mesh_link_header))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
          for (mode in LinkMode.entries) {
            FilterChip(
                selected = linkMode == mode,
                onClick = {
                  linkMode = mode
                  save { putString(MeshRouter.KEY_LINK_MODE, mode.key) }
                },
                label = { Text(linkModeLabel(mode)) },
            )
          }
        }
        Text(
            stringResource(
                when (linkMode) {
                  LinkMode.ETHERNET -> R.string.mesh_link_ethernet_help
                  LinkMode.WIFI -> R.string.mesh_link_wifi_help
                  LinkMode.HOTSPOT -> R.string.mesh_link_hotspot_help
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        when (linkMode) {
          LinkMode.ETHERNET -> {}
          LinkMode.WIFI -> {
            FieldRow {
              OutlinedTextField(
                  value = wifiSsid,
                  onValueChange = { wifiSsid = it },
                  label = { Text(stringResource(R.string.mesh_wifi_ssid)) },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
              )
            }
            ButtonRow {
              OutlinedButton(
                  onClick = {
                    currentSsid(context)?.let { wifiSsid = it }
                        ?: locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                  }
              ) {
                Text(stringResource(R.string.mesh_use_current_wifi))
              }
              Button(onClick = { save { putString(MeshRouter.KEY_WIFI_SSID, wifiSsid.trim()) } }) {
                Text(stringResource(R.string.save))
              }
            }
          }
          LinkMode.HOTSPOT -> {
            FieldRow {
              OutlinedTextField(
                  value = hotspotSsid,
                  onValueChange = { hotspotSsid = it },
                  label = { Text(stringResource(R.string.mesh_hotspot_ssid)) },
                  singleLine = true,
                  modifier = Modifier.weight(1f),
              )
            }
            FieldRow {
              OutlinedTextField(
                  value = hotspotPass,
                  onValueChange = { hotspotPass = it },
                  label = {
                    Text(stringResource(R.string.mesh_hotspot_password, hotspotSec.uppercase()))
                  },
                  singleLine = true,
                  visualTransformation = PasswordVisualTransformation(),
                  keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                  modifier = Modifier.weight(1f),
              )
            }
            ButtonRow {
              OutlinedButton(
                  onClick = {
                    if (hasLocationPermission(context)) wifiChoices = scanWifi(context)
                    else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                  }
              ) {
                Text(stringResource(R.string.mesh_scan))
              }
              Button(
                  onClick = {
                    save {
                      putString(MeshRouter.KEY_HOTSPOT_SSID, hotspotSsid.trim())
                      putString(MeshRouter.KEY_HOTSPOT_PASS, hotspotPass)
                      putString(MeshRouter.KEY_HOTSPOT_SEC, hotspotSec)
                    }
                  }
              ) {
                Text(stringResource(R.string.mesh_connect))
              }
            }
          }
        }
      }

      item("subnets") {
        Lists.SectionDivider()
        Lists.MutedHeader(stringResource(R.string.mesh_subnets_header))
        Setting.Switch(
            R.string.mesh_auto_subnet,
            subtitle = stringResource(R.string.mesh_auto_subnet_subtitle),
            isOn = autoSubnet,
            onToggle = {
              autoSubnet = it
              save { putBoolean(MeshRouter.KEY_AUTO_SUBNET, it) }
            },
        )
        if (!autoSubnet) {
          val valid = cidrs.split(',').all { MeshRouter.normalizeCidr(it.trim()) != null }
          FieldRow {
            OutlinedTextField(
                value = cidrs,
                onValueChange = { cidrs = it },
                label = { Text(stringResource(R.string.mesh_cidrs)) },
                isError = !valid,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
          }
          ButtonRow {
            Button(
                enabled = valid,
                onClick = {
                  cidrs = MeshRouter.parseCidrs(cidrs).joinToString(", ")
                  save { putString(MeshRouter.KEY_CIDRS, cidrs) }
                },
            ) {
              Text(stringResource(R.string.save))
            }
          }
        }
      }

      item("host") {
        Lists.SectionDivider()
        Lists.MutedHeader(stringResource(R.string.mesh_host_header))
        FieldRow {
          OutlinedTextField(
              value = meshHost,
              onValueChange = {
                meshHost = it
                prefs.edit().putString(MeshRouter.KEY_MESH_HOST, it.trim()).apply()
              },
              label = { Text(stringResource(R.string.mesh_host_hint)) },
              singleLine = true,
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
              modifier = Modifier.weight(1f),
          )
        }
        Setting.Switch(
            R.string.mesh_watchdog,
            isOn = watchdog,
            onToggle = {
              watchdog = it
              prefs.edit().putBoolean(MeshRouter.KEY_WATCHDOG, it).apply()
            },
        )
        ButtonRow {
          OutlinedButton(
              enabled = testJob?.isActive != true,
              onClick = {
                testLines.clear()
                testJob = scope.launch { MeshProbe.testMesh(meshHost.trim()) { testLines.add(it) } }
              },
          ) {
            Text(stringResource(R.string.mesh_test))
          }
          OutlinedButton(
              onClick = {
                if (scanJob?.isActive == true) {
                  scanJob?.cancel()
                  return@OutlinedButton
                }
                devices = null
                scanProgress = ""
                scanJob = scope.launch {
                  val found =
                      MeshProbe.discover(
                          onProgress = { done, total, n ->
                            scanProgress = "scanned $done / $total · $n found"
                          },
                          onFound = {},
                      )
                  devices = found
                }
              }
          ) {
            Text(
                stringResource(
                    if (scanJob?.isActive == true) R.string.mesh_cancel_scan
                    else R.string.mesh_find_devices
                )
            )
          }
        }
        if (scanProgress.isNotEmpty()) {
          Text(
              scanProgress,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(horizontal = 16.dp),
          )
        }
        if (testLines.isNotEmpty()) {
          Card(
              colors = CardDefaults.cardColors(),
              modifier = Modifier.fillMaxWidth().padding(16.dp),
          ) {
            Column(Modifier.padding(12.dp)) {
              for (line in testLines) {
                Text(
                    line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          }
        }
      }

      item("rescue") {
        Lists.SectionDivider()
        Lists.MutedHeader(stringResource(R.string.mesh_internet_header))
        Setting.Switch(
            R.string.mesh_rescue,
            subtitle = stringResource(R.string.mesh_rescue_subtitle),
            isOn = rescue,
            onToggle = {
              rescue = it
              save { putBoolean(MeshRouter.KEY_RESCUE, it) }
            },
        )
      }

      item("alwaysOn") {
        Lists.SectionDivider()
        Setting.Text(
            R.string.mesh_always_on,
            subtitle = stringResource(R.string.mesh_always_on_subtitle),
            onClick = { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) },
        )
        Spacer(Modifier.size(24.dp))
      }
    }
  }

  devices?.let { list ->
    AlertDialog(
        onDismissRequest = { devices = null },
        title = { Text(stringResource(R.string.mesh_devices_title, list.size)) },
        text = {
          LazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(list, key = { it.address }) { d ->
              Text(
                  "${d.address}   (${d.rttMs} ms)",
                  fontFamily = FontFamily.Monospace,
                  modifier =
                      Modifier.fillMaxWidth()
                          .clickable {
                            meshHost = d.address
                            prefs.edit().putString(MeshRouter.KEY_MESH_HOST, d.address).apply()
                            devices = null
                          }
                          .padding(vertical = 10.dp),
              )
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { devices = null }) { Text(stringResource(R.string.ok)) }
        },
    )
  }

  wifiChoices?.let { list ->
    AlertDialog(
        onDismissRequest = { wifiChoices = null },
        title = { Text(stringResource(R.string.mesh_scan_title)) },
        text = {
          if (list.isEmpty()) {
            Text(stringResource(R.string.mesh_scan_empty))
          } else {
            LazyColumn(Modifier.heightIn(max = 400.dp)) {
              items(list, key = { it.ssid }) { w ->
                Text(
                    "${w.ssid}   ${w.security.uppercase()}",
                    modifier =
                        Modifier.fillMaxWidth()
                            .clickable {
                              hotspotSsid = w.ssid
                              hotspotSec = w.security
                              if (w.security == "open") hotspotPass = ""
                              wifiChoices = null
                            }
                            .padding(vertical = 10.dp),
                )
              }
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { wifiChoices = null }) { Text(stringResource(R.string.cancel)) }
        },
    )
  }
}

@Composable
internal fun linkModeLabel(mode: LinkMode) =
    stringResource(
        when (mode) {
          LinkMode.ETHERNET -> R.string.mesh_link_ethernet
          LinkMode.WIFI -> R.string.mesh_link_wifi
          LinkMode.HOTSPOT -> R.string.mesh_link_hotspot
        }
    )

@Composable
private fun StatusRow(label: String, value: String, color: Color) {
  Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
  ) {
    StatusDot(color)
    Spacer(Modifier.size(12.dp))
    Text(label, modifier = Modifier.weight(1f))
    Text(value, style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun ErrorText(text: String) {
  Text(
      text,
      color = MaterialTheme.colorScheme.error,
      style = MaterialTheme.typography.bodySmall,
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
  )
}

@Composable
private fun FieldRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
  Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { content() }
}

@Composable
private fun ButtonRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
  Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
  ) {
    content()
  }
}

private data class WifiChoice(val ssid: String, val security: String, val level: Int)

private fun hasLocationPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun scanWifi(context: Context): List<WifiChoice> {
  val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  // startScan is throttled; the cached results from the last scan are usually fresh enough.
  wm.startScan()
  return wm.scanResults
      .filter { it.SSID.isNotBlank() }
      .groupBy { it.SSID }
      .map { (ssid, results) ->
        val best = results.maxBy { it.level }
        val caps = best.capabilities
        val sec =
            when {
              caps.contains("SAE") -> "wpa3"
              caps.contains("PSK") || caps.contains("WPA") -> "wpa2"
              else -> "open"
            }
        WifiChoice(ssid, sec, best.level)
      }
      .sortedByDescending { it.level }
}

@Suppress("DEPRECATION")
private fun currentSsid(context: Context): String? {
  if (!hasLocationPermission(context)) return null
  val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
  return wm.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it != WifiManager.UNKNOWN_SSID }
}
