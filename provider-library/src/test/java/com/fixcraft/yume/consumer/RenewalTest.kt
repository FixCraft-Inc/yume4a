package com.fixcraft.yume.consumer

import com.fixcraft.yume.provider.ProviderClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Renewal timing only, on a clock this test sets. */
class RenewalTest {
    private class Clock(var seconds: Long?, var elapsed: Long) : ProviderClock {
        override fun now() = seconds?.let { ProviderClock.Reading(it, elapsed) }
        override fun elapsedMillis() = elapsed
    }

    @Test
    fun aRenewalIsDueByTrustedTimeWhateverTheWallClockSays() {
        // Trusted time far behind the device's wall clock, as on a phone set ahead.
        val clock = Clock(seconds = 1_000, elapsed = 50_000)
        val renewal = Renewal(renewAt = 1_100, notBeforeElapsed = Long.MIN_VALUE, clock = clock)
        assertFalse(renewal.due())
        clock.seconds = 1_099
        assertFalse(renewal.due())
        clock.seconds = 1_100
        assertTrue(renewal.due())
    }

    @Test
    fun withoutTrustedTimeARenewalIsDueAtOnce() {
        val renewal = Renewal(renewAt = 1_100, notBeforeElapsed = Long.MIN_VALUE, clock = Clock(seconds = null, elapsed = 50_000))
        assertTrue(renewal.due())
    }

    @Test
    fun aBackoffHoldsOnTheMonotonicClockWithOrWithoutTrustedTime() {
        val clock = Clock(seconds = 2_000, elapsed = 49_999)
        val renewal = Renewal(renewAt = 1_100, notBeforeElapsed = 50_000, clock = clock)
        assertFalse(renewal.due())
        clock.seconds = null
        assertFalse(renewal.due())
        clock.elapsed = 50_000
        assertTrue(renewal.due())
        clock.seconds = 2_000
        assertTrue(renewal.due())
    }

    @Test
    fun theBackoffIsAQuarterOfTheLeaseLeftWithinItsBounds() {
        assertEquals(100_000L, Renewal.backoffMillis(expiresAt = 1_400, now = 1_000))
        assertEquals(300_000L, Renewal.backoffMillis(expiresAt = 4_600, now = 1_000))
        assertEquals(30_000L, Renewal.backoffMillis(expiresAt = 1_060, now = 1_000))
        assertEquals(30_000L, Renewal.backoffMillis(expiresAt = 900, now = 1_000))
        assertEquals(30_000L, Renewal.backoffMillis(expiresAt = 4_600, now = null))
    }
}
