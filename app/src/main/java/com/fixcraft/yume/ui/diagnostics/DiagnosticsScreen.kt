package com.fixcraft.yume.ui.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.data.supportsUserSelectablePerformanceProfiles
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.transport.YumePerfDebug

@Composable
fun DiagnosticsScreen(
    uiState: MainUiState,
    diagnosticsReport: String,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val profileSummary = if (uiState.appSettings.connectionTechnique.supportsUserSelectablePerformanceProfiles()) {
        strings.performanceProfileName(uiState.appSettings.performanceProfile)
    } else {
        strings.nativeDirectFixedTuningName
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = strings.diagnosticsTitle,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = strings.diagnosticsSubtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = strings.currentPhaseSummary(
                            strings.phaseStatus(
                                phase = uiState.vpnState.phase,
                                protected = uiState.vpnState.trafficRoutedThroughYume,
                                proxyReady = uiState.vpnState.localProxyAddress != null,
                            ),
                            strings.connectionTechniqueName(uiState.appSettings.connectionTechnique),
                            profileSummary,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(diagnosticsReport))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.copyDiagnostics)
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, strings.shareDiagnosticsSubject)
                                        putExtra(Intent.EXTRA_TEXT, diagnosticsReport)
                                    },
                                    strings.shareDiagnosticsChooser,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(strings.shareDiagnostics)
                    }
                }
            }
        }

        item {
            // Throughput-diagnostic toggle. When ON, the active
            // YumeBootstrapSession logs a one-line per-second
            // throughput report covering encrypt CPU, SSL CPU,
            // frame rate, channel backpressure etc. — visible in
            // logcat (tag YumePerf) AND in the VPN log feed below.
            // Use this when troubleshooting slow throughput to
            // identify whether the bottleneck is encryption, SSL,
            // or backpressure, before changing any code.
            var perfOn by remember { mutableStateOf(YumePerfDebug.enabled) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Throughput logging",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Logs a one-line throughput summary every 2 s while the " +
                            "tunnel is up. Covers encrypt/decrypt CPU, SSL write CPU, " +
                            "frame rates, and channel backpressure. Use to find where " +
                            "bandwidth is going on a slow connection. Visible in logcat " +
                            "(tag \"YumePerf\") and in the VPN log feed below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (perfOn) "Enabled" else "Disabled",
                             style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = perfOn,
                            onCheckedChange = {
                                perfOn = it
                                YumePerfDebug.enabled = it
                            },
                        )
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = strings.reportTitle,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = diagnosticsReport,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
