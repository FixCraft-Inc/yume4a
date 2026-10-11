package com.fixcraft.yume.ui.home

import com.fixcraft.yume.vpn.TrafficSample
import java.util.Locale

internal enum class TrafficWindow(val seconds: Int) {
    Short(15), Minute(60), FiveMin(300), FifteenMin(900),
}

/** The selected duration ends at the latest recorded sample, including paused history. */
internal fun trafficWindowSamples(samples: List<TrafficSample>, window: TrafficWindow): List<TrafficSample> {
    val end = samples.lastOrNull()?.tsMs ?: return emptyList()
    val start = end - window.seconds * 1000L
    // Discard the segment before a backwards wall-clock adjustment.
    val segmentStart = (1 until samples.size).lastOrNull { samples[it].tsMs < samples[it - 1].tsMs } ?: 0
    return samples.drop(segmentStart).filter { it.tsMs in start..end }
}

internal fun trafficTimeFraction(timestampMs: Long, endMs: Long, window: TrafficWindow): Float =
    (1.0 - (endMs - timestampMs).toDouble() / (window.seconds * 1000L)).coerceIn(0.0, 1.0).toFloat()

internal fun formatTrafficRate(bytesPerSecond: Double): String =
    formatTrafficBytes(bytesPerSecond.takeIf { it.isFinite() && it > 0.0 } ?: 0.0) + "/s"

internal fun formatTrafficBytes(bytes: Long): String = formatTrafficBytes(bytes.coerceAtLeast(0L).toDouble())

private fun formatTrafficBytes(bytes: Double): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
    var value = bytes
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return String.format(Locale.ROOT, if (unit == 0) "%.0f %s" else "%.1f %s", value, units[unit])
}
