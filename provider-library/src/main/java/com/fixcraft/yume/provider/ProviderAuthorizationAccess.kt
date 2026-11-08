package com.fixcraft.yume.provider

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** The browser attempt's original wall and monotonic budgets also bound local admission. */
internal class ProviderAuthorizationDeadline(private val began: ProviderClock.Reading) {
    fun remainingMillis(now: ProviderClock.Reading): Long {
        if (now.seconds < began.seconds || now.elapsedMillis < began.elapsedMillis) return 0
        val seconds = now.seconds - began.seconds
        val elapsed = now.elapsedMillis - began.elapsedMillis
        if (seconds !in 0L until 300L || elapsed !in 0L until 300_000L) return 0
        return minOf((300L - seconds) * 1000L, 300_000L - elapsed)
    }

    fun requireLive(now: ProviderClock.Reading) {
        if (remainingMillis(now) == 0L) throw ProviderCodec.Refusal("stale")
    }

}

/** Runs inside the client's cancellable operation lane, before any secret-store access. */
internal suspend fun <T> withAuthorizationAccess(
    deadline: ProviderAuthorizationDeadline,
    now: () -> ProviderClock.Reading,
    access: suspend () -> Unit,
    operation: suspend () -> T,
): T {
    val remaining = deadline.remainingMillis(now())
    if (remaining == 0L) throw ProviderCodec.Refusal("stale")
    if (withTimeoutOrNull(remaining) { access(); true } != true) throw ProviderCodec.Refusal("timeout")
    deadline.requireLive(now())
    currentCoroutineContext().ensureActive()
    return operation()
}
