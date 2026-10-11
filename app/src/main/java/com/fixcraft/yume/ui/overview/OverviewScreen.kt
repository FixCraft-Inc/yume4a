package com.fixcraft.yume.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.R
import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.FactRow
import com.fixcraft.yume.ui.StatusMessage
import com.fixcraft.yume.ui.StatusPill
import com.fixcraft.yume.ui.home.TrafficChartCard
import com.fixcraft.yume.ui.phaseTone
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnPhase

/** The selected kit, what the connection is doing, and the one button that changes it. */
@Composable
fun OverviewScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenKits: () -> Unit,
) {
    val strings = appStrings()
    val vpnState = uiState.vpnState
    val kit = uiState.selectedKit

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (kit == null && !vpnState.isActive) {
                        StatusMessage(headline = strings.noKitTitle, detail = strings.noKitDetail)
                        Button(onClick = onOpenKits, modifier = Modifier.fillMaxWidth()) {
                            Text(strings.addKit)
                        }
                    } else {
                        // The phase moves under the kit rather than splitting its name or address.
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = (if (vpnState.isActive) vpnState.kitName else null) ?: kit?.name.orEmpty(),
                                    style = MaterialTheme.typography.titleLarge,
                                )
                                Text(
                                    text = (if (vpnState.isActive) vpnState.endpointLabel else null)
                                        ?: kit?.endpointLabel.orEmpty(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            val tone = phaseTone(vpnState.phase)
                            StatusPill(
                                text = strings.phaseName(vpnState.phase),
                                containerColor = tone.container,
                                contentColor = tone.content,
                            )
                        }
                        StatusMessage(
                            headline = statusHeadline(strings, vpnState),
                            detail = statusDetail(strings, vpnState),
                        )
                        if (vpnState.failureDetail.isNotBlank() && vpnState.phase == VpnPhase.Error) {
                            Text(
                                text = vpnState.failureDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SessionFacts(strings, vpnState)
                        if (vpnState.isActive) {
                            OutlinedButton(
                                onClick = onDisconnect,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = vpnState.phase != VpnPhase.Disconnecting,
                            ) {
                                Icon(painter = painterResource(R.drawable.ic_logout_action), contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.disconnect)
                            }
                        } else {
                            Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) {
                                Icon(painter = painterResource(R.drawable.ic_login_action), contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(strings.connect)
                            }
                        }
                    }
                }
            }
        }
        if (kit != null || vpnState.isActive) {
            item {
                TrafficChartCard(vpnPhase = vpnState.phase)
            }
        }
    }
}

@Composable
private fun SessionFacts(strings: AppStrings, state: VpnConnectionState) {
    if (state.phase != VpnPhase.Connected && state.phase != VpnPhase.Reconnecting) return
    val status = state.status
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (status != null && status.session == YumeStatus.SESSION_ACTIVE) {
            FactRow(strings.connectedForLabel, formatDuration(status.connectedMs))
            FactRow(strings.openConnectionsLabel, (status.tcpConnections + status.udpDestinations).toString())
        }
        state.upstream?.let { FactRow(strings.networkLabel, strings.upstreamName(it)) }
        state.serverAddress?.let { FactRow(strings.serverAddressLabel, it, monospace = true) }
    }
}

private fun statusHeadline(strings: AppStrings, state: VpnConnectionState): String = when {
    state.phase == VpnPhase.Error -> strings.failureTitle(state.failure)
    state.waitingForNetwork && state.isActive -> strings.waitingForNetworkTitle
    else -> strings.phaseName(state.phase)
}

private fun statusDetail(strings: AppStrings, state: VpnConnectionState): String = when {
    state.phase == VpnPhase.Error -> strings.failureDetail(state.failure)
    state.waitingForNetwork && state.isActive -> strings.waitingForNetworkDetail
    else -> when (state.phase) {
        VpnPhase.Idle -> strings.idleDetail
        VpnPhase.PermissionRequired -> strings.permissionDetail
        VpnPhase.Connecting -> strings.connectingDetail
        VpnPhase.Connected -> strings.connectedDetail
        VpnPhase.Reconnecting -> {
            val wait = state.status?.retryMs ?: 0L
            if (wait > 0L) {
                strings.reconnectingDetail + " " + strings.nextAttempt((wait + 999L) / 1000L)
            } else {
                strings.reconnectingDetail
            }
        }

        VpnPhase.Disconnecting -> strings.disconnectingDetail
        VpnPhase.Error -> ""
    }
}

/** Hours, minutes and seconds as digits, which read the same in every language. */
internal fun formatDuration(milliseconds: Long): String {
    val seconds = milliseconds / 1000L
    val hours = seconds / 3600L
    val minutes = (seconds % 3600L) / 60L
    val rest = seconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(java.util.Locale.ROOT, hours, minutes, rest)
    } else {
        "%d:%02d".format(java.util.Locale.ROOT, minutes, rest)
    }
}
