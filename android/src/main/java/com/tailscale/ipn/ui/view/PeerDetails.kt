// Copyright (c) Tailscale Inc & AUTHORS
// SPDX-License-Identifier: BSD-3-Clause

package com.tailscale.ipn.ui.view

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tailscale.ipn.ui.util.DisplayAddress
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.TextButton
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tailscale.ipn.R
import com.tailscale.ipn.ui.theme.listItem
import com.tailscale.ipn.ui.theme.short
import com.tailscale.ipn.ui.util.AndroidTVUtil.isAndroidTV
import com.tailscale.ipn.ui.util.Lists
import com.tailscale.ipn.ui.util.itemsWithDividers
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModel
import com.tailscale.ipn.ui.viewModel.PeerDetailsViewModelFactory
import com.tailscale.ipn.ui.viewModel.PingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetails(
    onNavigateBack: () -> Unit,
    nodeId: String,
    pingViewModel: PingViewModel,
    model: PeerDetailsViewModel =
        viewModel(
            factory =
                PeerDetailsViewModelFactory(nodeId, LocalContext.current.filesDir, pingViewModel)
        ),
) {
  val isPinging by model.isPinging.collectAsState()

  model.netmap.collectAsState().value?.let { netmap ->
    model.node.collectAsState().value?.let { node ->
      var revealAddresses by rememberSaveable { mutableStateOf(false) }
      Scaffold(
          topBar = {
            Header(
                title = {
                  Column {
                    Text(
                        text = node.displayName,
                        style = MaterialTheme.typography.titleMedium.short,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Box(
                          modifier =
                              Modifier.size(8.dp)
                                  .background(
                                      color = node.connectedColor(netmap),
                                      shape = RoundedCornerShape(percent = 50),
                                  )
                      ) {}
                      Spacer(modifier = Modifier.size(8.dp))
                      Text(
                          text = stringResource(id = node.connectedStrRes(netmap)),
                          style = MaterialTheme.typography.bodyMedium.short,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                },
                actions = {
                  IconButton(onClick = { model.startPing() }) {
                    Icon(
                        painter = painterResource(R.drawable.sensors_24),
                        contentDescription = "Ping device",
                    )
                  }

                  val favorites by model.favorites.collectAsState()
                  val isWriting by model.isWritingFavorites.collectAsState()
                  val isPinned = favorites?.isFavoriteDevice(node.StableID) == true

                  IconButton(enabled = !isWriting, onClick = { model.togglePin() }) {
                    Icon(
                        painterResource(if (isPinned) R.drawable.unpin_24 else R.drawable.pin_24),
                        contentDescription =
                            stringResource(
                                if (isPinned) R.string.unpin_device else R.string.pin_device
                            ),
                    )
                  }
                },
                onBack = onNavigateBack,
            )
          },
      ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.padding(innerPadding),
        ) {
          // OpenRoute: addresses stay masked until the user asks to see them. Tapping a row
          // still copies the real address.
          item(key = "tailscaleAddresses") {
            Row(
                modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                verticalAlignment = Alignment.CenterVertically,
            ) {
              Box(modifier = Modifier.weight(1f)) {
                Lists.MutedHeader(stringResource(R.string.tailscale_addresses))
              }
              TextButton(
                  modifier = Modifier.padding(top = 8.dp, end = 8.dp),
                  onClick = { revealAddresses = !revealAddresses },
              ) {
                Text(
                    stringResource(
                        if (revealAddresses) R.string.dash_hide_addresses
                        else R.string.dash_show_addresses
                    )
                )
              }
            }
          }

          itemsWithDividers(node.displayAddresses, key = { it.address }) {
            AddressRow(
                address = it.address,
                type = it.typeString,
                masked = !revealAddresses && it.type != DisplayAddress.addrType.MagicDNS,
            )
          }

          item(key = "infoDivider") { Lists.SectionDivider() }

          itemsWithDividers(node.info, key = { "info_${it.titleRes}" }) {
            ValueRow(title = stringResource(id = it.titleRes), value = it.value.getString())
          }
        }
        if (isPinging) {
          ModalBottomSheet(onDismissRequest = { model.onPingDismissal() }) {
            PingView(model = model.pingViewModel)
          }
        }
      }
    }
  }
}

@Composable
fun AddressRow(address: String, type: String, masked: Boolean = false) {
  val localClipboardManager = LocalClipboardManager.current

  // Android TV doesn't have a clipboard, nor any way to use the values, so visible only.
  val modifier =
      if (isAndroidTV()) {
        Modifier.focusable(false)
      } else {
        Modifier.clickable { localClipboardManager.setText(AnnotatedString(address)) }
      }

  ListItem(
      modifier = modifier,
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = {
        Text(
            text = if (masked) maskAddress(address) else address,
            fontFamily = if (masked) FontFamily.Monospace else null,
        )
      },
      supportingContent = { Text(text = type) },
      trailingContent = {
        // TODO: there is some overlap with other uses of clipboard, DRY
        if (!isAndroidTV()) {
          Icon(painter = painterResource(id = R.drawable.clipboard), null)
        }
      },
  )
}

@Composable
fun ValueRow(title: String, value: String) {
  ListItem(
      colors = MaterialTheme.colorScheme.listItem,
      headlineContent = { Text(text = title) },
      supportingContent = { Text(text = value) },
  )
}

/** A fixed placeholder for an IP address, so the mask doesn't reveal the address's length. */
private fun maskAddress(address: String) =
    if (address.contains(':')) "••••:••••:••••::••••" else "•••.•••.•••.•••"
