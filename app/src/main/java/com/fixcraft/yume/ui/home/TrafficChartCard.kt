package com.fixcraft.yume.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.vpn.TrafficSample
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import java.util.Locale
import kotlin.math.max

private enum class TrafficWindow(val label: String, val seconds: Int) {
    Short("15s", 15),
    Minute("60s", 60),
    FiveMin("5m", 300),
    FifteenMin("15m", 900),
}

@Composable
fun TrafficChartCard(
    vpnPhase: VpnPhase,
    modifier: Modifier = Modifier,
) {
    val history by YumeVpnRuntime.traffic.collectAsStateWithLifecycle()
    val isConnected = vpnPhase == VpnPhase.Connected
    val strings = appStrings()

    var window by rememberSaveable { mutableStateOf(TrafficWindow.Minute) }
    val visibleSamples = remember(history.samples, window) {
        val keep = window.seconds
        if (history.samples.size <= keep) history.samples
        else history.samples.takeLast(keep)
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Top row: title left, compact time-window selector right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = strings.liveTrafficTitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
                WindowSelector(
                    selected = window,
                    onSelect = { window = it },
                )
            }
            Spacer(Modifier.height(12.dp))

            // Plot — fills the recovered vertical space.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                if (visibleSamples.size >= 2) {
                    TrafficSparkline(
                        samples = visibleSamples,
                        upload = MaterialTheme.colorScheme.primary,
                        download = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Text(
                        text = if (isConnected) {
                            strings.trafficCollectingSamples
                        } else {
                            strings.trafficConnectToStart
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Bottom row: rates flow left-to-right with a fixed gap.
            // No SpaceBetween — that would push them to opposite edges
            // and leave a void between them.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                RateStat(
                    arrow = "↑",
                    rate = history.latestTxRateBps,
                    total = history.totalTxBytes,
                    color = MaterialTheme.colorScheme.primary,
                )
                RateStat(
                    arrow = "↓",
                    rate = history.latestRxRateBps,
                    total = history.totalRxBytes,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun WindowSelector(
    selected: TrafficWindow,
    onSelect: (TrafficWindow) -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TrafficWindow.values().forEach { opt ->
            val active = opt == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent,
                    )
                    .clickable { onSelect(opt) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = opt.label,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun RateStat(
    arrow: String,
    rate: Double,
    total: Long,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = arrow,
            color = color,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = formatRate(rate),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "· ${formatBytes(total)}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * Cubic-smoothed sparkline. Two stacked series: upload (primary) on
 * top, download (tertiary) below. Soft gradient fill under each line.
 * Zero external chart deps.
 */
@Composable
private fun TrafficSparkline(
    samples: List<TrafficSample>,
    upload: Color,
    download: Color,
) {
    val maxRate = remember(samples) {
        val peak = samples.maxOf { max(it.rxRateBps, it.txRateBps) }
        max(peak, 1024.0)
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        val w = size.width
        val h = size.height
        val n = samples.size
        if (n < 2) return@Canvas

        val xStep = w / (n - 1).toFloat()
        fun pointY(rate: Double): Float {
            val r = (rate / maxRate).coerceIn(0.0, 1.0)
            return (h - r.toFloat() * h).coerceIn(0f, h)
        }

        fun seriesPath(rateOf: (TrafficSample) -> Double, fill: Boolean): Path {
            val path = Path()
            path.moveTo(0f, pointY(rateOf(samples[0])))
            for (i in 1 until n) {
                val xPrev = (i - 1) * xStep
                val xCur = i * xStep
                val yPrev = pointY(rateOf(samples[i - 1]))
                val yCur = pointY(rateOf(samples[i]))
                val midX = xPrev + (xCur - xPrev) / 2f
                path.cubicTo(midX, yPrev, midX, yCur, xCur, yCur)
            }
            if (fill) {
                path.lineTo(w, h)
                path.lineTo(0f, h)
                path.close()
            }
            return path
        }

        drawPath(
            path = seriesPath({ it.txRateBps }, fill = true),
            brush = Brush.verticalGradient(
                colors = listOf(upload.copy(alpha = 0.32f), upload.copy(alpha = 0.02f)),
            ),
        )
        drawPath(
            path = seriesPath({ it.txRateBps }, fill = false),
            color = upload,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )

        drawPath(
            path = seriesPath({ it.rxRateBps }, fill = true),
            brush = Brush.verticalGradient(
                colors = listOf(download.copy(alpha = 0.32f), download.copy(alpha = 0.02f)),
            ),
        )
        drawPath(
            path = seriesPath({ it.rxRateBps }, fill = false),
            color = download,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )
    }
}

private fun formatRate(bps: Double): String {
    if (bps < 1.0) return "0 B/s"
    if (bps < 1024.0) return String.format(Locale.ROOT, "%.0f B/s", bps)
    if (bps < 1024.0 * 1024.0) return String.format(Locale.ROOT, "%.1f KB/s", bps / 1024.0)
    if (bps < 1024.0 * 1024.0 * 1024.0) {
        return String.format(Locale.ROOT, "%.2f MB/s", bps / (1024.0 * 1024.0))
    }
    return String.format(Locale.ROOT, "%.2f GB/s", bps / (1024.0 * 1024.0 * 1024.0))
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) { value /= 1024.0; i++ }
    return when {
        i == 0 -> "${bytes} B"
        value >= 100.0 -> String.format(Locale.ROOT, "%.0f %s", value, units[i])
        value >= 10.0 -> String.format(Locale.ROOT, "%.1f %s", value, units[i])
        else -> String.format(Locale.ROOT, "%.2f %s", value, units[i])
    }
}
