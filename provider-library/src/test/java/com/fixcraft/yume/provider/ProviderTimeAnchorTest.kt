package com.fixcraft.yume.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The provider-response clock of docs/PROVIDER_CLIENT.md, "Trusted time". No Android clock is involved. */
class ProviderTimeAnchorTest {
    private class MemoryStorage : ProviderTimeAnchor.Storage {
        var saved: ProviderTimeAnchor.Saved? = null
        var writes = 0
        override fun read() = saved
        override fun write(saved: ProviderTimeAnchor.Saved) { this.saved = saved; writes++ }
    }

    private val date = "Wed, 11 Nov 2026 23:02:38 GMT"
    private val dateSeconds = 1_794_438_158L

    @Test
    fun noResponseMeansNoTrustedTime() {
        assertNull(ProviderTimeAnchor(boot = 7, storage = MemoryStorage()).now(5_000))
    }

    @Test
    fun aDateIsTheTimeTheResponseArrivedAndReadingsFollowTheElapsedClock() {
        val anchor = ProviderTimeAnchor(boot = null, storage = null)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_400)
        assertEquals(ProviderClock.Reading(dateSeconds, 1_400), anchor.now(1_400))
        assertEquals(dateSeconds, anchor.now(2_399)!!.seconds)
        assertEquals(dateSeconds + 61, anchor.now(62_400)!!.seconds)
    }

    @Test
    fun aRoundTripLongerThanTheBoundSetsNoTime() {
        val anchor = ProviderTimeAnchor(boot = null, storage = null)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 11_001)
        assertNull(anchor.now(11_001))
        anchor.observe(date, sentElapsedMillis = 20_000, receivedElapsedMillis = 19_999)
        assertNull(anchor.now(20_000))
        anchor.observe(date, sentElapsedMillis = 30_000, receivedElapsedMillis = 40_000)
        assertEquals(dateSeconds, anchor.now(40_000)!!.seconds)
    }

    @Test
    fun onlyAnImfFixdateSetsTime() {
        val anchor = ProviderTimeAnchor(boot = null, storage = null)
        for (refused in listOf(null, "", "Wednesday, 11-Nov-26 23:02:38 GMT", "Wed Nov 11 23:02:38 2026",
                "Wed, 11 Nov 2026 23:02:38 +0000", "Thu, 11 Nov 2026 23:02:38 GMT", "Wed, 11 Nov 2026 24:02:38 GMT",
                "Wed,  11 Nov 2026 23:02:38 GMT", "Wed, 11 Nov 2026 23:02:38 GMT ", "Thu, 01 Jan 1970 00:00:00 GMT x")) {
            anchor.observe(refused, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_100)
        }
        assertNull(anchor.now(1_100))
        anchor.observe("Thu, 01 Jan 1970 00:00:00 GMT", sentElapsedMillis = 1_000, receivedElapsedMillis = 1_100)
        assertEquals(0L, anchor.now(1_100)!!.seconds)
    }

    @Test
    fun anEarlierDateNeverMovesTheReadingBack() {
        val anchor = ProviderTimeAnchor(boot = null, storage = null)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        anchor.observe("Wed, 11 Nov 2026 23:02:33 GMT", sentElapsedMillis = 2_000, receivedElapsedMillis = 2_500)
        assertEquals(dateSeconds + 1, anchor.now(2_500)!!.seconds)
    }

    @Test
    fun aLaterDateMovesTheReadingForward() {
        val anchor = ProviderTimeAnchor(boot = null, storage = null)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        anchor.observe("Wed, 11 Nov 2026 23:02:45 GMT", sentElapsedMillis = 2_000, receivedElapsedMillis = 2_500)
        assertEquals(dateSeconds + 7, anchor.now(2_500)!!.seconds)
    }

    @Test
    fun responsesReportedOutOfOrderKeepTheLaterAnchor() {
        val anchor = ProviderTimeAnchor(boot = null, storage = null)
        // The second response arrived first: 23:02:39 at 2_000 outranks 23:02:38 at 1_500.
        anchor.observe("Wed, 11 Nov 2026 23:02:39 GMT", sentElapsedMillis = 1_800, receivedElapsedMillis = 2_000)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        assertEquals(dateSeconds + 1, anchor.now(2_000)!!.seconds)
        assertEquals(dateSeconds + 2, anchor.now(3_000)!!.seconds)
    }

    @Test
    fun aRestartedProcessKeepsTheAnchorOfItsBoot() {
        val storage = MemoryStorage()
        ProviderTimeAnchor(boot = 7, storage = storage).observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        assertEquals(ProviderTimeAnchor.Saved(7, dateSeconds * 1000, 1_500), storage.saved)
        assertEquals(dateSeconds + 100, ProviderTimeAnchor(boot = 7, storage = storage).now(101_500)!!.seconds)
    }

    @Test
    fun anAnchorOfAnotherBootIsDiscarded() {
        val storage = MemoryStorage()
        ProviderTimeAnchor(boot = 7, storage = storage).observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        assertNull(ProviderTimeAnchor(boot = 8, storage = storage).now(101_500))
        // An elapsed clock behind the saved instant means the device restarted too.
        assertNull(ProviderTimeAnchor(boot = 7, storage = storage).now(1_499))
    }

    @Test
    fun anUnknownBootIsNeitherSavedNorRestored() {
        val storage = MemoryStorage()
        val anchor = ProviderTimeAnchor(boot = null, storage = storage)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        assertEquals(0, storage.writes)
        storage.saved = ProviderTimeAnchor.Saved(7, dateSeconds * 1000, 1_000)
        assertNull(ProviderTimeAnchor(boot = null, storage = storage).now(2_000))
    }

    @Test
    fun onlyAForwardCorrectionIsWritten() {
        val storage = MemoryStorage()
        val anchor = ProviderTimeAnchor(boot = 7, storage = storage)
        anchor.observe(date, sentElapsedMillis = 1_000, receivedElapsedMillis = 1_500)
        anchor.observe(date, sentElapsedMillis = 2_000, receivedElapsedMillis = 2_500)
        assertEquals(1, storage.writes)
        anchor.observe("Wed, 11 Nov 2026 23:02:45 GMT", sentElapsedMillis = 3_000, receivedElapsedMillis = 3_500)
        assertEquals(2, storage.writes)
        assertTrue(storage.saved!!.utcMillis > dateSeconds * 1000)
    }
}
