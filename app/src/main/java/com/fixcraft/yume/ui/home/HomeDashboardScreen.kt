package com.fixcraft.yume.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.vpn.VpnPhase

@Composable
fun HomeDashboardScreen(
    uiState: MainUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenServers: () -> Unit,
) {
    val profile = uiState.selectedProfile
    val vpnState = uiState.vpnState
    val hasAuthKey = uiState.selectedAuthKeyMaterial != null
    val liveSession = vpnState.phase == VpnPhase.Verifying || vpnState.phase == VpnPhase.Connected
    val showDisconnect = vpnState.phase == VpnPhase.Connecting ||
        vpnState.phase == VpnPhase.Verifying ||
        vpnState.phase == VpnPhase.Connected ||
        vpnState.phase == VpnPhase.Disconnecting
    val statusLabel = when {
        vpnState.phase == VpnPhase.Idle && hasAuthKey -> "Ready"
        vpnState.phase == VpnPhase.Idle -> "Needs key"
        else -> vpnState.briefStatus
    }
    val statusHeadline = when {
        vpnState.phase == VpnPhase.Idle && hasAuthKey -> "Ready"
        vpnState.phase == VpnPhase.Idle -> "Auth key needed"
        else -> vpnState.headline
    }
    val statusDetail = when {
        vpnState.phase == VpnPhase.Idle && hasAuthKey -> "Tap Connect to start."
        vpnState.phase == VpnPhase.Idle -> "Assign an auth key to connect."
        else -> vpnState.detail
    }
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.10f)
    val glowColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
    val sessionFacts = buildList {
        if (liveSession && vpnState.veritySummary.isNotBlank()) {
            add("Anonym ${vpnState.veritySummary}")
        }
        if (liveSession && vpnState.innerSummary.isNotBlank()) {
            add(vpnState.innerSummary)
        }
        if (liveSession) {
            conciseHop(vpnState.hopSummary)?.let(::add)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val spacing = 42.dp.toPx()
            var x = 0f
            while (x <= size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += spacing
            }
            var y = 0f
            while (y <= size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += spacing
            }
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(glowColor, Color.Transparent),
                    center = Offset(size.width * 0.18f, size.height * 0.1f),
                    radius = size.minDimension * 0.75f,
                ),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (profile == null) {
                            Text(
                                text = "No server selected",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                text = "Add a server, then come back here to connect.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onOpenServers, modifier = Modifier.fillMaxWidth()) {
                                Text("Open servers")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = profile.name,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        text = profile.endpointLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                StatusPill(
                                    text = statusLabel,
                                    containerColor = if (vpnState.phase == VpnPhase.Connected || vpnState.phase == VpnPhase.Verifying) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    },
                                    contentColor = if (vpnState.phase == VpnPhase.Connected || vpnState.phase == VpnPhase.Verifying) {
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.onSecondaryContainer
                                    },
                                )
                            }
                            StatusMessage(
                                headline = statusHeadline,
                                detail = statusDetail,
                            )
                            if (sessionFacts.isNotEmpty()) {
                                Text(
                                    text = sessionFacts.joinToString(" • "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    text = "Anonym: ${if (profile.requireAnonymProof) "Required" else "Optional"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = "Auth key: ${uiState.selectedAuthKeyMaterial?.displayName ?: "Not assigned"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (uiState.selectedAuthKeyMaterial == null) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                            Text(
                                text = "Mode: ${uiState.appSettings.connectionTechnique.displayName} • ${uiState.appSettings.performanceProfile.displayName}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (vpnState.exitIp != null || vpnState.upstreamNetworkLabel != null) {
                                Text(
                                    text = buildString {
                                        append("Exit IP: ${vpnState.exitIp ?: "Unknown"}")
                                        append(" • Upstream: ${vpnState.upstreamNetworkLabel ?: "Unavailable"}")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (showDisconnect) {
                                    OutlinedButton(
                                        onClick = onDisconnect,
                                        modifier = Modifier.weight(1f),
                                    ) {
                                        Text("Disconnect")
                                    }
                                } else {
                                    Button(
                                        onClick = onConnect,
                                        modifier = Modifier.weight(1f),
                                        enabled = uiState.selectedAuthKeyMaterial != null,
                                    ) {
                                        Text("Connect")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun conciseHop(summary: String): String? {
    return if (summary.startsWith("Hop ", ignoreCase = true) && !summary.equals("Hop off", ignoreCase = true)) {
        summary
    } else {
        null
    }
}
