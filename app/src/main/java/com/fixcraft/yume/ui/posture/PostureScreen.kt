package com.fixcraft.yume.ui.posture

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.FactRow
import com.fixcraft.yume.ui.SectionCard
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.VpnRunPolicy
import com.fixcraft.yume.data.AppSettings

/**
 * What protects the connection and what does not. Every value here is either
 * fixed by YUME, reported by the linked library, or a setting of this
 * application. Nothing is shown as protecting that the library did not report.
 */
@Composable
fun PostureScreen(uiState: MainUiState) {
    val strings = appStrings()
    val context = LocalContext.current
    val build = remember { runCatching { YumeLibrary.build() }.getOrNull() }
    val vpnState = uiState.vpnState
    val settings = uiState.appSettings
    val status = vpnState.status?.takeIf {
        vpnState.phase == VpnPhase.Connected && it.session == YumeStatus.SESSION_ACTIVE
    }
    // While a run is up, its own values are shown, not settings changed since.
    val carriesIpv6 = if (vpnState.isActive) vpnState.carriesIpv6 else settings.carryIpv6
    val dnsServers = vpnState.dnsServers.takeIf { vpnState.isActive && it.isNotEmpty() }
        ?: VpnRunPolicy.usableDnsServers(settings.dnsServers, carriesIpv6, AppSettings.DEFAULT_DNS_SERVERS)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SectionCard(title = strings.postureConnectionTitle, detail = strings.postureConnectionDetail) {
                if (build != null) {
                    FactRow(strings.protocolLabel, build.protocolName)
                    FactRow(strings.suiteLabel, build.suite, monospace = true)
                    FactRow(strings.libraryLabel, build.productVersion)
                    FactRow(strings.cryptoBackendLabel, build.cryptoBackend)
                }
                FactRow(
                    strings.serverIdentityLabel,
                    status?.peerFingerprint?.takeIf { it.isNotBlank() }?.chunked(16)?.joinToString("\n")
                        ?: strings.notConnectedValue,
                    monospace = status != null,
                )
                FactRow(
                    strings.keyEpochLabel,
                    status?.epochBytes?.takeIf { it > 0 }?.let(::formatSize) ?: strings.notConnectedValue,
                )
            }
        }
        item {
            SectionCard(title = strings.postureServerTitle, detail = strings.postureServerDetail)
        }
        item {
            SectionCard(title = strings.postureDeviceTitle) {
                FactRow(strings.routesLabel, if (carriesIpv6) strings.routesAllTraffic else strings.routesAllIpv4)
                FactRow(strings.ipv6Label, if (carriesIpv6) strings.ipv6Carried else strings.ipv6Blocked)
                FactRow(strings.nameLookupsLabel, strings.nameLookups(dnsServers.joinToString()))
                FactRow(strings.sessionLossLabel, strings.sessionLossValue)
                FactRow(
                    strings.appDataLabel,
                    if (uiState.appDataPasswordEnabled) strings.appDataPassword else strings.appDataKeystore,
                )
                FactRow(strings.backupsLabel, strings.backupsValue)
            }
        }
        item {
            SectionCard(title = strings.postureStopTitle, detail = strings.postureStopDetail) {
                OutlinedButton(
                    onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(strings.openVpnSettings)
                }
            }
        }
        item {
            SectionCard(title = strings.postureLimitsTitle, detail = strings.postureLimitsDetail)
        }
    }
}

/** A byte count in binary units, which the configuration also uses. */
private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L && bytes % (1024L * 1024L) == 0L -> "${bytes / (1024L * 1024L)} MiB"
    bytes >= 1024L && bytes % 1024L == 0L -> "${bytes / 1024L} KiB"
    else -> "$bytes B"
}
