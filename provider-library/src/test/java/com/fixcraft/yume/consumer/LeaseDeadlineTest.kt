package com.fixcraft.yume.consumer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lease timing only. No network clock or native transport is simulated here. */
class LeaseDeadlineTest {
    @Test
    fun utcExpiryHasNoGracePeriod() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_099, elapsed = 199_000))
        assertFalse(deadline.valid(seconds = 1_100, elapsed = 199_001))
    }

    @Test
    fun monotonicExpiryWinsEvenWhileUtcStillAllowsTheLease() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_050, elapsed = 199_999))
        assertFalse(deadline.valid(seconds = 1_050, elapsed = 200_000))
    }

    @Test
    fun withoutATrustedReadingOnlyTheMonotonicDeadlineApplies() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = null, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = null, elapsed = 199_999))
        assertFalse(deadline.valid(seconds = null, elapsed = 200_000))
    }

    @Test
    fun readingsWithoutTrustedTimeKeepTheElapsedHighWaterMarkAndUtcExpiryReturnsWithTime() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = null, elapsed = 150_000))
        assertFalse(deadline.valid(seconds = null, elapsed = 149_999))
        assertTrue(deadline.valid(seconds = 1_050, elapsed = 150_001))
        assertFalse(deadline.valid(seconds = 1_100, elapsed = 150_002))
    }

    @Test
    fun utcRollbackDoesNotLowerTheAcceptedHighWaterMark() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_010, elapsed = 110_000))
        assertFalse(deadline.valid(seconds = 1_009, elapsed = 110_001))
        assertFalse(deadline.valid(seconds = 1_005, elapsed = 110_002))
        assertTrue(deadline.valid(seconds = 1_010, elapsed = 110_003))
    }

    @Test
    fun elapsedRollbackDoesNotLowerTheAcceptedHighWaterMark() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_001, elapsed = 101_000))
        assertFalse(deadline.valid(seconds = 1_002, elapsed = 100_999))
        assertFalse(deadline.valid(seconds = 1_002, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_002, elapsed = 102_000))
    }

    @Test
    fun frequentSamplesCannotKeepAStoppedUtcClockTrusted() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 101_000))
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 102_000))
        assertFalse(deadline.valid(seconds = 1_000, elapsed = 102_001))
    }

    @Test
    fun gradualUtcLagCannotResetTheDriftAllowanceAtEachSample() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_001, elapsed = 102_000))
        assertTrue(deadline.valid(seconds = 1_002, elapsed = 104_000))
        assertFalse(deadline.valid(seconds = 1_003, elapsed = 106_000))
    }

    @Test
    fun ordinaryWholeSecondClockReadingsRemainUsable() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_900))
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_999))
        assertTrue(deadline.valid(seconds = 1_001, elapsed = 101_000))
        assertTrue(deadline.valid(seconds = 1_009, elapsed = 109_999))
        assertTrue(deadline.valid(seconds = 1_010, elapsed = 110_000))
    }

    @Test
    fun aForwardUtcCorrectionDoesNotExtendTheMonotonicDeadline() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 105_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_090, elapsed = 104_999))
        assertFalse(deadline.valid(seconds = 1_090, elapsed = 105_000))
    }

    @Test
    fun aForwardCorrectionCannotHideASubsequentlyStoppedUtcClock() {
        val deadline = LeaseDeadline(expiresAt = 1_100, elapsedDeadline = 200_000)
        assertTrue(deadline.valid(seconds = 1_000, elapsed = 100_000))
        assertTrue(deadline.valid(seconds = 1_010, elapsed = 100_001))
        assertTrue(deadline.valid(seconds = 1_010, elapsed = 101_001))
        assertTrue(deadline.valid(seconds = 1_010, elapsed = 102_001))
        assertFalse(deadline.valid(seconds = 1_010, elapsed = 102_002))
    }
}
