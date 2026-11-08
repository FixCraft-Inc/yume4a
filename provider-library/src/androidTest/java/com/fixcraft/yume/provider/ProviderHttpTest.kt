package com.fixcraft.yume.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.CookieHandler
import java.net.CookieManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises actual request ownership with workers stopped before any network operation. */
@RunWith(AndroidJUnit4::class)
class ProviderHttpTest {
    private suspend fun outcome(http: ProviderHttp, origin: String = ORIGIN, endpoint: String = ENDPOINT,
                                bearer: ProviderBearer? = null, body: ByteArray? = null): String = try {
        http.request(origin, endpoint, body = body, bearer = bearer).fill(0)
        "unexpected-success"
    } catch (refusal: ProviderCodec.Refusal) {
        refusal.code
    }

    @Test
    fun bearerAndEndpointOriginsAreRejectedBeforeAWorkerCanStart() = runBlocking {
        val calls = AtomicInteger()
        ProviderHttp { calls.incrementAndGet(); throw ProviderCodec.Refusal("unavailable") }.use { http ->
            assertEquals("binding", outcome(http, endpoint = "https://other.example/provider/v1/catalog"))
            assertEquals("binding", outcome(http, bearer = ProviderBearer("https://other.example", "SYNTHETIC-TOKEN")))
            assertEquals("binding", outcome(http, bearer = ProviderBearer(ORIGIN, "SYNTHETIC\r\nTOKEN")))
            assertEquals("binding", outcome(http, endpoint = "http://provider.example/provider/v1/catalog"))
            assertEquals("malformed", outcome(http, bearer = ProviderBearer(ORIGIN, "SYNTHETIC-TOKEN", "bad\r\nheader")))
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun processGlobalCookiePolicyIsRefusedAndNeverMutated() {
        synchronized(COOKIE_MONITOR) {
            val previous = CookieHandler.getDefault()
            val installed = CookieManager()
            CookieHandler.setDefault(installed)
            try {
                runBlocking {
                    val calls = AtomicInteger()
                    ProviderHttp { calls.incrementAndGet(); throw ProviderCodec.Refusal("unavailable") }.use { http ->
                        assertEquals("state", outcome(http))
                        assertEquals(0, calls.get())
                        assertTrue(CookieHandler.getDefault() === installed)
                    }
                }
            } finally {
                CookieHandler.setDefault(previous)
            }
        }
    }

    @Test
    fun closeSettlesBothRunningAndQueuedRequestsBeforeAnyCanConnect() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(2)
        val released = CountDownLatch(1)
        val exited = CountDownLatch(2)
        val http = ProviderHttp {
            calls.incrementAndGet()
            entered.countDown()
            try { released.await() } finally { exited.countDown() }
            throw ProviderCodec.Refusal("unavailable")
        }
        try {
            val requests = (0 until 6).map { async(start = CoroutineStart.UNDISPATCHED) { outcome(http) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            http.close()
            assertEquals(List(6) { "state" }, withTimeout(5_000) { requests.awaitAll() })
            assertTrue(exited.await(5, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
            assertEquals("state", outcome(http))
            http.close()
        } finally {
            released.countDown()
            http.close()
        }
    }

    @Test
    fun theThirtySecondDeadlineSettlesQueuedWorkWithoutStartingIt() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(2)
        val released = CountDownLatch(1)
        val http = ProviderHttp {
            calls.incrementAndGet()
            entered.countDown()
            released.await()
            throw ProviderCodec.Refusal("unavailable")
        }
        try {
            val started = System.nanoTime()
            val requests = (0 until 3).map { async(start = CoroutineStart.UNDISPATCHED) { outcome(http) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals(List(3) { "unavailable" }, withTimeout(35_000) { requests.awaitAll() })
            val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("the real total deadline must fire", elapsedMillis >= 29_000)
            assertTrue("queued work must share the total deadline", elapsedMillis < 35_000)
            assertEquals(2, calls.get())
        } finally {
            http.close()
            released.countDown()
        }
    }

    @Test
    fun cancellingQueuedWorkNeverStartsItAndDoesNotCancelOtherRequests() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(2)
        val released = CountDownLatch(1)
        val http = ProviderHttp {
            calls.incrementAndGet()
            entered.countDown()
            released.await()
            throw ProviderCodec.Refusal("unavailable")
        }
        try {
            val running = (0 until 2).map { async(start = CoroutineStart.UNDISPATCHED) { outcome(http) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val queued = launch(start = CoroutineStart.UNDISPATCHED) { outcome(http) }
            withTimeout(5_000) { queued.cancelAndJoin() }
            assertTrue(queued.isCancelled)
            assertTrue(running.none { it.isCompleted })
            http.close()
            assertEquals(List(2) { "state" }, withTimeout(5_000) { running.awaitAll() })
            assertEquals(2, calls.get())
        } finally {
            http.close()
            released.countDown()
        }
    }

    @Test
    fun anAlreadyCancelledCallerDoesNotEnqueueNetworkWork() = runBlocking {
        val calls = AtomicInteger()
        ProviderHttp { calls.incrementAndGet(); throw ProviderCodec.Refusal("unavailable") }.use { http ->
            val request = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                outcome(http)
            }
            withTimeout(5_000) { request.join() }
            assertTrue(request.isCancelled)
            assertEquals(0, calls.get())
        }
    }

    @Test
    fun saturatedQueueFailsBoundedlyWithoutStartingAnExtraWorker() = runBlocking {
        val calls = AtomicInteger()
        val entered = CountDownLatch(2)
        val released = CountDownLatch(1)
        val http = ProviderHttp {
            calls.incrementAndGet()
            entered.countDown()
            released.await()
            throw ProviderCodec.Refusal("unavailable")
        }
        try {
            val requests = (0 until 18).map { async(start = CoroutineStart.UNDISPATCHED) { outcome(http) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertEquals("unavailable", withTimeout(5_000) { outcome(http) })
            assertEquals(2, calls.get())
            http.close()
            assertEquals(List(18) { "state" }, withTimeout(5_000) { requests.awaitAll() })
        } finally {
            http.close()
            released.countDown()
        }
    }

    private companion object {
        val COOKIE_MONITOR = Any()
        const val ORIGIN = "https://provider.example"
        const val ENDPOINT = "$ORIGIN/provider/v1/catalog"
    }
}
