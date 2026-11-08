package com.fixcraft.yume.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Admission and owned-exchange lifetime seam; actual browser/store wiring needs Android evidence. */
class ProviderAuthorizationAccessTest {
    private val began = ProviderClock.Reading(100, 1000)
    private fun exchange() = ProviderAuthorization.Exchange("https://provider.example", "epoch",
        "https://issuer.example/token", byteArrayOf(1, 2, 3), 7, ProviderAuthorizationDeadline(began))

    private suspend fun refusal(code: String, operation: suspend () -> Unit) {
        try { operation(); fail("Admission was allowed") }
        catch (error: ProviderCodec.Refusal) { assertEquals(code, error.code) }
    }

    @Test fun accessMustCompleteBeforeSecretOperations() = runBlocking {
        val unlock = CompletableDeferred<Unit>()
        val exchange = exchange()
        var entered = false
        var reads = 0
        val callback = async(start = CoroutineStart.UNDISPATCHED) {
            exchange.use {
                withAuthorizationAccess(it.deadline, { began }, { entered = true; unlock.await() }) { ++reads }
            }
        }
        try {
            assertTrue(entered)
            assertFalse(callback.isCompleted)
            assertEquals(0, reads)
            assertArrayEquals(byteArrayOf(1, 2, 3), exchange.body)
            unlock.complete(Unit)
            assertEquals(1, withTimeout(5000) { callback.await() })
            assertEquals(1, reads)
            assertArrayEquals(ByteArray(3), exchange.body)
        } finally { callback.cancelAndJoin() }
    }

    @Test fun waitingUsesOnlyTheOriginalRemainingBudgetAndWipesTheExchange() = runBlocking {
        val exchange = exchange()
        var reads = 0
        refusal("timeout") {
            exchange.use {
                withAuthorizationAccess(it.deadline, { ProviderClock.Reading(399, 300_999) },
                    { CompletableDeferred<Unit>().await() }) { ++reads }
            }
        }
        assertEquals(0, reads)
        assertArrayEquals(ByteArray(3), exchange.body)
    }

    @Test fun callerCancellationStopsTheWaitWithoutSecretAccessAndWipesTheExchange() = runBlocking {
        val exchange = exchange()
        var reads = 0
        val callback = async(start = CoroutineStart.UNDISPATCHED) {
            exchange.use {
                withAuthorizationAccess(it.deadline, { began }, { CompletableDeferred<Unit>().await() }) { ++reads }
            }
        }
        callback.cancelAndJoin()
        assertTrue(callback.isCancelled)
        assertEquals(0, reads)
        assertArrayEquals(ByteArray(3), exchange.body)
    }

    @Test fun refusedAccessCannotReachSecretsAndKeepsItsTypedFailure() = runBlocking {
        val exchange = exchange()
        var reads = 0
        refusal("locked") {
            exchange.use {
                withAuthorizationAccess(it.deadline, { began }, { throw ProviderCodec.Refusal("locked") }) { ++reads }
            }
        }
        assertEquals(0, reads)
        assertArrayEquals(ByteArray(3), exchange.body)
    }

    @Test fun admissionRechecksOriginalExpiryAndClockRegressionBeforeSecrets() = runBlocking {
        for (invalid in listOf(ProviderClock.Reading(99, 1000), ProviderClock.Reading(100, 999),
            ProviderClock.Reading(400, 1000), ProviderClock.Reading(100, 301_000))) {
            val exchange = exchange()
            var now = began
            var reads = 0
            refusal("stale") {
                exchange.use {
                    withAuthorizationAccess(it.deadline, { now }, { now = invalid }) { ++reads }
                }
            }
            assertEquals(0, reads)
            assertArrayEquals(ByteArray(3), exchange.body)
        }
    }

    @Test fun originalDeadlineHasNoGraceOrRegressionWindow() {
        val deadline = ProviderAuthorizationDeadline(began)
        assertEquals(300_000L, deadline.remainingMillis(began))
        assertEquals(1L, deadline.remainingMillis(ProviderClock.Reading(399, 300_999)))
        assertEquals(0L, deadline.remainingMillis(ProviderClock.Reading(400, 300_999)))
        assertEquals(0L, deadline.remainingMillis(ProviderClock.Reading(399, 301_000)))
        assertEquals(0L, deadline.remainingMillis(ProviderClock.Reading(99, 1000)))
        assertEquals(0L, deadline.remainingMillis(ProviderClock.Reading(100, 999)))
        assertEquals(0L, deadline.remainingMillis(ProviderClock.Reading(Long.MAX_VALUE, Long.MAX_VALUE)))
    }

    @Test fun expiredAdmissionCannotClaimAWaitOrReachSecrets() = runBlocking {
        val exchange = exchange()
        var waits = 0
        var reads = 0
        refusal("stale") {
            exchange.use {
                withAuthorizationAccess(it.deadline, { ProviderClock.Reading(400, 301_000) }, { ++waits }) { ++reads }
            }
        }
        assertEquals(0, waits)
        assertEquals(0, reads)
        assertArrayEquals(ByteArray(3), exchange.body)
    }

    @Test fun outerTimeoutRemainsCoroutineCancellation() = runBlocking {
        val exchange = exchange()
        try {
            withTimeout(50) {
                exchange.use {
                    withAuthorizationAccess(it.deadline, { began }, { CompletableDeferred<Unit>().await() }) {
                        fail("Secret operation ran")
                    }
                }
            }
            fail("Outer timeout was lost")
        } catch (_: TimeoutCancellationException) {
            assertArrayEquals(ByteArray(3), exchange.body)
        }
    }
}
