package com.fixcraft.yume.provider

import android.os.SystemClock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.CookieHandler
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resumeWithException

/**
 * The production transport has no DNS, CA, hostname, cookie or redirect override.
 * Every response that passes TLS and the header checks reports its `Date` to
 * [timeFeed] before its body is read, so the body's own time checks run against
 * that response's time (docs/PROVIDER_CLIENT.md, "Trusted time").
 */
class ProviderHttp internal constructor(
    private val timeFeed: ProviderTimeFeed = AndroidProviderClock,
    private val beforeConnect: () -> Unit,
) : ProviderTransport {
    constructor() : this(beforeConnect = {})

    private val monitor = Any()
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val pending = mutableSetOf<Request>()
    private var closed = false

    override suspend fun request(
        origin: String,
        endpoint: String,
        body: ByteArray?,
        bearer: ProviderBearer?,
        limit: Int,
        responseType: String,
        requestType: String,
        proof: String?,
    ): ByteArray {
        ProviderCodec.endpoint(endpoint, ProviderCodec.origin(origin))
        if (proof != null && (proof.length !in 1..2048 || proof.any { it.code !in 0x21..0x7e })) throw ProviderCodec.Refusal("malformed")
        if (bearer != null && (bearer.origin != origin || bearer.value.length !in 1..4096 ||
            bearer.value.any { it.code !in 0x21..0x7e })) throw ProviderCodec.Refusal("binding")
        bearer?.deviceId?.let { ProviderCodec.identifier(it) }
        if (body != null && body.size > ProviderCodec.SMALL_LIMIT || limit !in 1..1_049_648 ||
            responseType !in setOf("application/json", "application/octet-stream") ||
            requestType !in setOf("application/json", "application/x-www-form-urlencoded")) {
            throw ProviderCodec.Refusal("malformed")
        }
        // HttpURLConnection consults a process-global CookieHandler. Refuse that
        // environment rather than changing another component's cookie policy.
        if (CookieHandler.getDefault() != null) throw ProviderCodec.Refusal("state")
        return suspendCancellableCoroutine { continuation ->
            val request = Request(continuation, endpoint, body?.copyOf(), bearer, limit, responseType, requestType, proof)
            continuation.invokeOnCancellation { request.finish(null, null) }
            var refusal: String? = null
            synchronized(monitor) {
                if (closed) {
                    refusal = "state"
                } else if (!request.finished) {
                    pending += request
                    try {
                        request.timeout = deadlines.schedule({ request.finish(null, ProviderCodec.Refusal("unavailable")) },
                            TOTAL_SECONDS, TimeUnit.SECONDS)
                        workers.execute(request)
                    } catch (_: RejectedExecutionException) {
                        refusal = "unavailable"
                    }
                }
            }
            // A request no worker accepted was never sent.
            refusal?.let { request.finish(null, ProviderCodec.Refusal(it, unsent = it == "unavailable")) }
        }
    }

    private inner class Request(
        private val continuation: CancellableContinuation<ByteArray>,
        private val endpoint: String,
        private val body: ByteArray?,
        private val bearer: ProviderBearer?,
        private val limit: Int,
        private val responseType: String,
        private val requestType: String,
        private val proof: String?,
    ) : Runnable {
        // All publication, cancellation and ownership transitions use monitor.
        var finished = false
        var timeout: ScheduledFuture<*>? = null
        private var started = false
        private var connection: HttpsURLConnection? = null
        private val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOTAL_SECONDS)

        private fun live(): Boolean {
            if (System.nanoTime() - deadlineNanos >= 0) {
                finish(null, ProviderCodec.Refusal("unavailable"))
                return false
            }
            return synchronized(monitor) { !finished && continuation.isActive }
        }

        /** Exactly one terminal result; queued work cannot reconnect after it. */
        fun finish(bytes: ByteArray?, failure: ProviderCodec.Refusal?) {
            var connectionToClose: HttpsURLConnection? = null
            val accepted = synchronized(monitor) {
                if (finished) false else {
                    finished = true
                    pending.remove(this)
                    timeout?.cancel(false)
                    workers.remove(this)
                    if (!started) body?.fill(0)
                    connectionToClose = connection
                    true
                }
            }
            if (!accepted) {
                bytes?.fill(0)
                return
            }
            if (bytes != null) continuation.resume(bytes) { _, value, _ -> value.fill(0) }
            else if (failure != null) continuation.resumeWithException(failure)
            connectionToClose?.disconnect()
        }

        override fun run() {
            synchronized(monitor) {
                if (finished) return
                started = true
            }
            var received: ByteArray? = null
            var opened: HttpsURLConnection? = null
            try {
                // A test checkpoint can block workers before networking; it never
                // supplies a connection, CA, resolver or successful HTTP result.
                beforeConnect()
                if (!live()) return
                if (CookieHandler.getDefault() != null) throw ProviderCodec.Refusal("state")
                val current = URL(endpoint).openConnection() as HttpsURLConnection
                opened = current
                synchronized(monitor) {
                    if (finished) return
                    connection = current
                }
                current.instanceFollowRedirects = false
                current.connectTimeout = 10_000
                current.readTimeout = 30_000
                current.useCaches = false
                current.requestMethod = if (body == null) "GET" else "POST"
                current.setRequestProperty("Accept", responseType)
                current.setRequestProperty("Accept-Encoding", "identity")
                current.setRequestProperty("Cache-Control", "no-store")
                // Each request opens its own connection. A pooled one can outlive the
                // network or VPN tunnel it was opened through, and then fails on reuse.
                current.setRequestProperty("Connection", "close")
                bearer?.let {
                    current.setRequestProperty("Authorization", (if (proof != null) "DPoP " else "Bearer ") + it.value)
                    it.deviceId?.let { device -> current.setRequestProperty("X-Device-ID", device) }
                }
                proof?.let { current.setRequestProperty("DPoP", it) }
                if (!live()) return
                if (body != null) {
                    current.doOutput = true
                    current.setRequestProperty("Content-Type", requestType)
                    current.setFixedLengthStreamingMode(body.size)
                }
                // Nothing is sent before the connection and its TLS session exist, so a
                // failure here is the one case known not to have reached the peer.
                try { current.connect() }
                catch (_: IOException) { throw ProviderCodec.Refusal("unavailable", unsent = true) }
                if (!live()) return
                // TLS is established. The provider writes its Date after this instant.
                val sentElapsed = SystemClock.elapsedRealtime()
                body?.let { bytes -> current.outputStream.use { it.write(bytes) } }
                if (!live()) return
                val status = current.responseCode
                val receivedElapsed = SystemClock.elapsedRealtime()
                if (status in 300..399) throw ProviderCodec.Refusal("binding")
                val encoding = current.getHeaderField("Content-Encoding")
                if (encoding != null && encoding != "identity") throw ProviderCodec.Refusal("malformed")
                if (current.getHeaderField("Set-Cookie") != null ||
                    current.getHeaderField("Cache-Control")?.lowercase() != "no-store") {
                    throw ProviderCodec.Refusal("malformed")
                }
                val expectedType = if (status == 200) responseType else "application/json"
                val contentType = current.contentType?.lowercase()
                if (contentType != expectedType && contentType != "$expectedType; charset=utf-8") {
                    throw ProviderCodec.Refusal("malformed")
                }
                timeFeed.observe(current.getHeaderField("Date"), sentElapsed, receivedElapsed)
                val bound = if (status == 200) limit else ProviderCodec.SMALL_LIMIT
                if (current.contentLengthLong > bound) throw ProviderCodec.Refusal("malformed")
                val stream = if (status == 200) current.inputStream else current.errorStream
                    ?: throw ProviderCodec.Refusal("malformed")
                received = stream.use { input ->
                    val buffer = ByteArray(bound + 1)
                    var size = 0
                    try {
                        while (true) {
                            if (!live()) return
                            val count = input.read(buffer, size, minOf(4096, buffer.size - size))
                            if (count < 0) break
                            size += count
                            if (size > bound) throw ProviderCodec.Refusal("malformed")
                        }
                        buffer.copyOf(size)
                    } finally { buffer.fill(0) }
                }
                if (!live()) return
                if (status != 200) {
                    // Issuer endpoints answer in OAuth's format, not the provider's.
                    if (status == 400 && requestType == "application/x-www-form-urlencoded" &&
                        ProviderCodec.refusedGrant(received!!)) throw ProviderCodec.Refusal("unauthorized")
                    val code = ProviderCodec.error(received!!)
                    if (ERROR_STATUSES[code] != status) throw ProviderCodec.Refusal("malformed")
                    throw ProviderCodec.Refusal(code)
                }
                val result = received!!
                received = null
                finish(result, null)
            } catch (error: Exception) {
                finish(null, if (error is ProviderCodec.Refusal) error else ProviderCodec.Refusal("unavailable"))
            } finally {
                received?.fill(0)
                body?.fill(0)
                opened?.disconnect()
            }
        }
    }

    override fun close() {
        val active = synchronized(monitor) {
            if (closed) return
            closed = true
            pending.toList()
        }
        active.forEach { it.finish(null, ProviderCodec.Refusal("state")) }
        workers.shutdownNow()
        deadlines.shutdownNow()
    }

    private companion object {
        const val TOTAL_SECONDS = 30L
        val ERROR_STATUSES = mapOf("malformed" to 400, "binding" to 400, "unsupported" to 422,
            "unauthorized" to 401, "not_entitled" to 403, "conflict" to 409, "stale" to 409,
            "revoked" to 410, "rate_limited" to 429, "unavailable" to 503)
    }
}
