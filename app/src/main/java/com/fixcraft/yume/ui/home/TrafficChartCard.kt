package com.fixcraft.yume.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fixcraft.yume.vpn.TrafficHistory
import com.fixcraft.yume.vpn.TrafficSample
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import java.util.Locale
import kotlin.math.max

/**
 * Live throughput card. Listens to YumeVpnRuntime.traffic via a lifecycle-
 * scoped collector so we only redraw while the user is looking; the
 * VpnService keeps the ring buffer fresh in the background regardless.
 */
@Composable
fun TrafficChartCard(
    vpnPhase: VpnPhase,
    modifier: Modifier = Modifier,
) {
    val history by YumeVpnRuntime.traffic.collectAsStateWithLifecycle()
    val isConnected = vpnPhase == VpnPhase.Connected

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            TrafficChartHeader(
                history = history,
                isConnected = isConnected,
            )
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                if (history.samples.size >= 2) {
                    TrafficSparkline(
                        samples = history.samples,
                        upload = MaterialTheme.colorScheme.primary,
                        download = MaterialTheme.colorScheme.tertiary,
                    )
                } else {
                    Text(
                        text = if (isConnected) {
                            "Collecting samples…"
                        } else {
                            "Connect to start measuring traffic"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Legend()
        }
    }
}

@Composable
private fun TrafficChartHeader(
    history: TrafficHistory,
    isConnected: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Live traffic",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (isConnected) {
                    "Updated every second while protected"
                } else {
                    "Idle"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            RateLine(
                label = "↑",
                rate = history.latestTxRateBps,
                total = history.totalTxBytes,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(2.dp))
            RateLine(
                label = "↓",
                rate = history.latestRxRateBps,
                total = history.totalRxBytes,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

@Composable
private fun RateLine(
    label: String,
    rate: Double,
    total: Long,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = color,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "${formatRate(rate)}  ·  ${formatBytes(total)}",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun Legend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LegendDot(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(6.dp))
        Text(
            "Upload",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(18.dp))
        LegendDot(color = MaterialTheme.colorScheme.tertiary)
        Spacer(Modifier.size(6.dp))
        Text(
            "Download",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LegendDot(color: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * Hand-rolled sparkline. Cubic-smoothed line for each series, soft
 * gradient fill below. ImPlot/AnyChart-equivalent visuals with no
 * dependencies.
 */
@Composable
private fun TrafficSparkline(
    samples: List<TrafficSample>,
    upload: Color,
    download: Color,
) {
    val maxRate = remember(samples) {
        val peak = samples.maxOf { max(it.rxRateBps, it.txRateBps) }
        // Avoid a tiny y-range with a small floor; round up to a nice value.
        max(peak, 1024.0)
    }
    Canvas(modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 8.dp, vertical = 8.dp)) {
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
            val first = samples[0]
            path.moveTo(0f, pointY(rateOf(first)))
            for (i in 1 until n) {
                val xPrev = (i - 1) * xStep
                val xCur = i * xStep
                val yPrev = pointY(rateOf(samples[i - 1]))
                val yCur = pointY(rateOf(samples[i]))
                val cx1 = xPrev + (xCur - xPrev) / 2f
                val cx2 = xPrev + (xCur - xPrev) / 2f
                path.cubicTo(cx1, yPrev, cx2, yCur, xCur, yCur)
            }
            if (fill) {
                path.lineTo(w, h)
                path.lineTo(0f, h)
                path.close()
            }
            return path
        }

        // Upload — fill + line on top.
        drawPath(
            path = seriesPath({ it.txRateBps }, fill = true),
            brush = Brush.verticalGradient(
                colors = listOf(
                    upload.copy(alpha = 0.32f),
                    upload.copy(alpha = 0.02f),
                ),
            ),
        )
        drawPath(
            path = seriesPath({ it.txRateBps }, fill = false),
            color = upload,
            style = Stroke(width = 4f, cap = StrokeCap.Round),
        )

        // Download — fill + line on top.
        drawPath(
            path = seriesPath({ it.rxRateBps }, fill = true),
            brush = Brush.verticalGradient(
                colors = listOf(
                    download.copy(alpha = 0.32f),
                    download.copy(alpha = 0.02f),
                ),
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
    return if (i == 0) {
        "${bytes} B"
    } else if (value >= 100.0) {
        String.format(Locale.ROOT, "%.0f %s", value, units[i])
    } else if (value >= 10.0) {
        String.format(Locale.ROOT, "%.1f %s", value, units[i])
    } else {
        String.format(Locale.ROOT, "%.2f %s", value, units[i])
    }
}
