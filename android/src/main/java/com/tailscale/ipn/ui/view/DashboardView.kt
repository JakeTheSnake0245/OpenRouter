// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tailscale.ipn.R
import com.tailscale.ipn.mesh.MeshRouter
import com.tailscale.ipn.ui.model.Ipn
import com.tailscale.ipn.ui.viewModel.MainViewModel

/**
 * OpenRoute's main screen while connected: one status tile per link. Tailnet devices (and their
 * addresses) are one tap away on [DevicesView] rather than listed on the front page.
 */
@Composable
fun OpsDashboard(
    viewModel: MainViewModel,
    onNavigateToDevices: () -> Unit,
    onNavigateToMesh: () -> Unit,
) {
  val netmap by viewModel.netmap.collectAsState()
  val mesh by MeshRouter.status.collectAsState()
  val state by viewModel.ipnState.collectAsState()
  val reachingServer = state != Ipn.State.Running

  Column(
      modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    SectionLabel(stringResource(R.string.dash_links))

    val peers = netmap?.Peers.orEmpty().filter { !it.isMullvadNode }
    val online = peers.count { it.Online == true }
    StatusTile(
        label = stringResource(R.string.dash_tailnet),
        value = stringResource(if (reachingServer) R.string.dash_offline else R.string.dash_secure),
        detail =
            if (reachingServer) stringResource(R.string.dash_last_known, peers.size)
            else stringResource(R.string.dash_devices_online, online, peers.size),
        color = if (reachingServer) statusAmber else statusGreen,
        onClick = onNavigateToDevices,
    )

    val meshValue =
        when {
          !mesh.enabled -> R.string.dash_off
          mesh.routes.isNotEmpty() && mesh.meshHostAlive == false -> R.string.dash_degraded
          mesh.routes.isNotEmpty() -> R.string.dash_linked
          mesh.linkUp -> R.string.dash_link_up
          else -> R.string.dash_no_link
        }
    StatusTile(
        label = stringResource(R.string.dash_mesh),
        value = stringResource(meshValue),
        detail =
            if (mesh.routes.isNotEmpty())
                "${linkModeLabel(mesh.linkMode)} · ${mesh.routes.joinToString()}"
            else mesh.linkDetail.replaceFirstChar { it.uppercase() },
        color = meshColor(mesh),
        onClick = onNavigateToMesh,
    )

    val internetDown = mesh.internet.startsWith("none") || mesh.internet.contains("no internet")
    StatusTile(
        label = stringResource(R.string.dash_internet),
        value =
            when {
              mesh.internet.isEmpty() -> stringResource(R.string.dash_device_default)
              internetDown -> stringResource(R.string.dash_down)
              else -> mesh.internet.substringBefore(" (")
            },
        detail =
            when {
              mesh.rescueActive -> stringResource(R.string.dash_rescue_active)
              mesh.internet == "none" -> stringResource(R.string.dash_no_internet)
              internetDown -> mesh.internet.replaceFirstChar { it.uppercase() }
              else -> stringResource(R.string.dash_internet_direct)
            },
        color =
            when {
              internetDown -> statusRed
              mesh.rescueActive -> statusAmber
              else -> statusGreen
            },
        onClick = onNavigateToMesh,
    )
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
      text = text.uppercase(),
      style = MaterialTheme.typography.labelMedium,
      letterSpacing = 1.5.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(top = 4.dp, start = 4.dp),
  )
}

/** A status panel: colored edge, small caps label, a one-word state and a detail line. */
@Composable
fun StatusTile(
    label: String,
    value: String,
    detail: String,
    color: Color,
    onClick: (() -> Unit)? = null,
) {
  val shape = RoundedCornerShape(10.dp)
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .height(IntrinsicSize.Min)
              .clip(shape)
              .background(MaterialTheme.colorScheme.surfaceContainerHighest)
              .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
              .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.width(5.dp).fillMaxHeight().background(color))
    Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp)) {
      Text(
          text = label.uppercase(),
          style = MaterialTheme.typography.labelSmall,
          letterSpacing = 1.5.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Spacer(Modifier.height(2.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(
            text = value.uppercase(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
      }
      Spacer(Modifier.height(2.dp))
      Text(
          text = detail,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )
    }
    if (onClick != null) {
      Icon(
          Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(end = 12.dp),
      )
    }
  }
}

/** The tailnet device list, moved off the main screen. Addresses are not shown in the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesView(
    viewModel: MainViewModel,
    onNavigateToPeerDetails: (com.tailscale.ipn.ui.model.Tailcfg.Node) -> Unit,
    onNavigateToSearch: () -> Unit,
    onBack: () -> Unit,
) {
  Scaffold(topBar = { Header(titleRes = R.string.dash_devices_title, onBack = onBack) }) {
      innerPadding ->
    Box(modifier = Modifier.padding(innerPadding)) {
      PeerList(
          viewModel = viewModel,
          onNavigateToPeerDetails = onNavigateToPeerDetails,
          onSearchBarClick = onNavigateToSearch,
          onSearch = { viewModel.searchPeers(it) },
      )
    }
  }
}
