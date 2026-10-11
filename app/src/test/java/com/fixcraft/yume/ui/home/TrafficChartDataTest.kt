package com.fixcraft.yume.ui.home

import com.fixcraft.yume.vpn.TrafficSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficChartDataTest {
    private fun sample(timestamp: Long) = TrafficSample(timestamp, 0, 0, 0.0, 0.0, 0.0, 0.0)

    @Test
    fun windowUsesElapsedTimeWhenPollingIsDelayed() {
        val samples = listOf(0L, 30_000L, 59_000L, 60_000L, 90_000L).map(::sample)
        assertEquals(listOf(30_000L, 59_000L, 60_000L, 90_000L),
            trafficWindowSamples(samples, TrafficWindow.Minute).map { it.tsMs })
        assertEquals(listOf(90_000L), trafficWindowSamples(samples, TrafficWindow.Short).map { it.tsMs })
        assertTrue(trafficWindowSamples(emptyList(), TrafficWindow.Minute).isEmpty())
    }

    @Test
    fun sparseHistoryUsesOnlyTheRecordedPartOfTheWindow() {
        assertEquals(0.5f, trafficTimeFraction(30_000L, 60_000L, TrafficWindow.Minute), 0.0001f)
        assertEquals(1f, trafficTimeFraction(60_000L, 60_000L, TrafficWindow.Minute), 0f)
        assertEquals(0f, trafficTimeFraction(0L, 60_000L, TrafficWindow.Minute), 0f)
    }

    @Test
    fun backwardsClockStartsANewChartSegment() {
        val samples = listOf(50_000L, 60_000L, 40_000L, 55_000L, 70_000L).map(::sample)
        assertEquals(listOf(40_000L, 55_000L, 70_000L),
            trafficWindowSamples(samples, TrafficWindow.Minute).map { it.tsMs })
    }

    @Test
    fun base1024QuantitiesUseBinaryUnitLabels() {
        assertEquals("1.0 KiB/s", formatTrafficRate(1024.0))
        assertEquals("1.0 MiB/s", formatTrafficRate(1024.0 * 1024.0))
        assertEquals("1.0 GiB", formatTrafficBytes(1024L * 1024L * 1024L))
        assertEquals("512 B/s", formatTrafficRate(512.0))
        assertEquals("0 B/s", formatTrafficRate(0.0))
    }
}
