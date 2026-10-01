package com.fixcraft.yume.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.SectionCard
import com.fixcraft.yume.vpn.VpnLogEntry
import com.fixcraft.yume.vpn.VpnLogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the connection did, and a report of it to copy or share. */
@Composable
fun LogsScreen(
    uiState: MainUiState,
    onBuildReport: () -> String,
    onClearLog: () -> Unit,
    onReportNotice: (String) -> Unit,
) {
    val strings = appStrings()
    val context = LocalContext.current
    val entries = uiState.vpnState.eventLog
    val clock = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            SectionCard(title = strings.sessionLogTitle, detail = strings.sessionLogSubtitle) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FilledTonalButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Yume report", onBuildReport()))
                            onReportNotice(strings.reportCopied)
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(strings.copyReport)
                    }
                    OutlinedButton(
                        onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_SUBJECT, strings.shareReportSubject)
                                        putExtra(Intent.EXTRA_TEXT, onBuildReport())
                                    },
                                    strings.shareReport,
                                ),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(strings.shareReport)
                    }
                }
                if (entries.isNotEmpty()) {
                    TextButton(onClick = onClearLog) { Text(strings.clearLog) }
                }
            }
        }
        if (entries.isEmpty()) {
            item {
                Text(
                    text = strings.noSessionEventsYet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(items = entries) { _, entry ->
            LogLine(entry, clock)
        }
    }
}

@Composable
private fun LogLine(entry: VpnLogEntry, clock: SimpleDateFormat) {
    Column {
        Text(
            text = "${clock.format(Date(entry.timestampEpochMs))}  ${entry.message}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when (entry.level) {
                VpnLogLevel.Info -> MaterialTheme.colorScheme.onSurface
                VpnLogLevel.Warn -> MaterialTheme.colorScheme.tertiary
                VpnLogLevel.Error -> MaterialTheme.colorScheme.error
            },
        )
    }
}
