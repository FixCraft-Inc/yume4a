package com.fixcraft.yume.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.ui.theme.trafficColors
import com.fixcraft.yume.vpn.TrafficSample
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import kotlin.math.max

@Composable
fun TrafficChartCard(vpnPhase: VpnPhase, modifier: Modifier = Modifier) {
    val history by YumeVpnRuntime.traffic.collectAsStateWithLifecycle()
    // Before a run has produced a sample, an empty chart would only take the page's space.
    if (history.samples.isEmpty() && vpnPhase != VpnPhase.Connected && vpnPhase != VpnPhase.Reconnecting) return
    val isConnected = vpnPhase == VpnPhase.Connected
    val strings = appStrings()
    val colors = trafficColors()
    val rateMinWidth = 140.dp * LocalDensity.current.fontScale
    var window by rememberSaveable { mutableStateOf(TrafficWindow.Minute) }
    val samples = remember(history.samples, window) { trafficWindowSamples(history.samples, window) }
    val maxRate = remember(samples) {
        max(samples.maxOfOrNull { max(it.rxRateBps, it.txRateBps) } ?: 0.0, 1024.0)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (!isConnected && samples.isNotEmpty()) strings.trafficHistoryTitle else strings.trafficTitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (vpnPhase == VpnPhase.Reconnecting) strings.reconnectingDetail else strings.trafficRatesDetail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WindowSelector(window, { window = it }, strings)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.background).padding(12.dp),
            ) {
                Text(formatTrafficRate(maxRate), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.fillMaxWidth().height(132.dp)) {
                    if (samples.size >= 2) {
                        TrafficSparkline(samples, window, maxRate, colors.upload, colors.download)
                    } else {
                        Text(
                            if (isConnected || vpnPhase == VpnPhase.Reconnecting) strings.trafficCollectingSamples
                            else strings.trafficConnectToStart,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("0 B/s", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(strings.trafficLatestSample, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2,
            ) {
                RateStat("↑", strings.trafficUpload, if (isConnected) history.latestTxRateBps else null,
                    history.totalTxBytes, colors.upload, dashed = false, strings = strings,
                    modifier = Modifier.weight(1f).widthIn(min = rateMinWidth))
                RateStat("↓", strings.trafficDownload, if (isConnected) history.latestRxRateBps else null,
                    history.totalRxBytes, colors.download, dashed = true, strings = strings,
                    modifier = Modifier.weight(1f).widthIn(min = rateMinWidth))
            }
        }
    }
}

@Composable
private fun WindowSelector(selected: TrafficWindow, onSelect: (TrafficWindow) -> Unit, strings: AppStrings) {
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TrafficWindow.entries.forEach { option ->
            val active = option == selected
            val label = strings.trafficWindow(option.seconds)
            Box(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                    .selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(option) })
                    .semantics { contentDescription = strings.trafficWindowDescription(label) }
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun RateStat(
    arrow: String, label: String, rate: Double?, total: Long, color: Color,
    dashed: Boolean, strings: AppStrings, modifier: Modifier,
) {
    Column(modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(24.dp, 8.dp).clearAndSetSemantics {}) {
                drawLine(color, start = Offset(0f, size.height / 2), end = Offset(size.width, size.height / 2),
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())) else null)
            }
            Text(arrow, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.clearAndSetSemantics {})
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
        Text(rate?.let(::formatTrafficRate) ?: "—", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium, modifier = if (rate == null) Modifier.clearAndSetSemantics {
                contentDescription = strings.trafficRateUnavailable
            } else Modifier)
        Text(strings.trafficTotal(formatTrafficBytes(total)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Overlaid payload rates on one scale. Upload is solid; download is dashed. */
@Composable
private fun TrafficSparkline(samples: List<TrafficSample>, window: TrafficWindow, maxRate: Double, upload: Color, download: Color) {
    Canvas(Modifier.fillMaxWidth().height(132.dp).clearAndSetSemantics {}) {
        val end = samples.last().tsMs
        fun seriesPath(rateOf: (TrafficSample) -> Double): Path = Path().apply {
            samples.forEachIndexed { index, sample ->
                val x = trafficTimeFraction(sample.tsMs, end, window) * size.width
                val y = size.height * (1 - (rateOf(sample) / maxRate).coerceIn(0.0, 1.0)).toFloat()
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        // Straight segments and timestamps do not invent curved peaks or equal sample spacing.
        drawPath(seriesPath { it.txRateBps }, upload, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        drawPath(seriesPath { it.rxRateBps }, download, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }
}
