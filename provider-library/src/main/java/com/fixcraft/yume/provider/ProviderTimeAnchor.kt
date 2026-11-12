package com.fixcraft.yume.provider

import java.time.DateTimeException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Trusted time anchored to provider responses, for Android releases without a
 * network clock. [observe] takes a response's HTTP `Date` as the time when the
 * response arrived. The provider wrote that `Date` during the round trip and
 * truncated it to the second, so the anchor never runs ahead of the provider's
 * clock. A reading is the elapsed clock plus one offset, and a later response
 * can raise the offset, never lower it, whatever order responses report in.
 * An anchor belongs to one boot: [storage] keeps it across process restarts
 * only while [boot] is unchanged.
 *
 * No Android types: the caller supplies the boot count and elapsed clock.
 * docs/PROVIDER_CLIENT.md, "Trusted time", owns the rule.
 */
internal class ProviderTimeAnchor(private val boot: Long?, private val storage: Storage?) {
    /** UTC milliseconds at one instant of the elapsed clock of [boot]. */
    data class Saved(val boot: Long, val utcMillis: Long, val elapsedMillis: Long)

    /** Best-effort persistence. Implementations contain their own I/O failures. */
    interface Storage {
        fun read(): Saved?
        fun write(saved: Saved)
    }

    /** UTC minus elapsed milliseconds, or null before this boot has an anchor. */
    private var offsetMillis: Long? = null
    private var restored = false

    @Synchronized
    fun now(elapsedNow: Long): ProviderClock.Reading? {
        restore(elapsedNow)
        val offset = offsetMillis ?: return null
        return ProviderClock.Reading(Math.floorDiv(elapsedNow + offset, 1000L), elapsedNow)
    }

    @Synchronized
    fun observe(date: String?, sentElapsedMillis: Long, receivedElapsedMillis: Long) {
        if (receivedElapsedMillis - sentElapsedMillis !in 0..MAX_ROUND_TRIP_MILLIS) return
        val provided = date?.let(::parse) ?: return
        restore(receivedElapsedMillis)
        val offset = provided - receivedElapsedMillis
        if (offsetMillis?.let { offset <= it } == true) return
        offsetMillis = offset
        boot?.let { storage?.write(Saved(it, provided, receivedElapsedMillis)) }
    }

    /** Adopts the saved anchor once, if it was taken earlier in this boot. */
    private fun restore(elapsedNow: Long) {
        if (restored) return
        restored = true
        // An elapsed clock behind the saved instant also means another boot.
        storage?.read()?.takeIf { it.boot == boot && it.elapsedMillis <= elapsedNow }?.let {
            offsetMillis = it.utcMillis - it.elapsedMillis
        }
    }

    companion object {
        /** Bounds the anchor's lag, well inside the issuer's 60-second DPoP window. */
        const val MAX_ROUND_TRIP_MILLIS = 10_000L

        private val IMF_FIXDATE = Regex(
            "(Mon|Tue|Wed|Thu|Fri|Sat|Sun), [0-9]{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) [0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")

        /** RFC 9110 IMF-fixdate only, as UTC milliseconds. Anything else is no time. */
        fun parse(date: String): Long? {
            if (!IMF_FIXDATE.matches(date)) return null
            return try {
                ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli().takeIf { it >= 0 }
            } catch (_: DateTimeException) { null }
        }
    }
}
