package com.fixcraft.yume.agent

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Blocking socket work has one worker, one queued request and close-on-cancel. */
internal object BenchmarkSocket {
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1)) { runnable ->
        Thread(runnable, "yume-agent-download").apply { isDaemon = true }
    }

    suspend fun fetch(
        socket: Socket, address: InetAddress, uri: URI, maxBytes: Long,
        deadline: Long, now: () -> Long,
    ): BenchmarkHttp.Result = suspendCancellableCoroutine { continuation ->
        // Close the raw socket first, so TLS close cannot wait on its peer.
        continuation.invokeOnCancellation { runCatching { socket.close() } }
        if (!continuation.isActive) {
            socket.close()
            return@suspendCancellableCoroutine
        }
        try {
            worker.execute {
                try {
                    val result = socket.use { raw ->
                        fun remaining(): Int = (deadline - now()).also {
                            if (it <= 0) throw SocketTimeoutException("Benchmark deadline exceeded")
                        }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        val host = uri.host.removePrefix("[").removeSuffix("]")
                        val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
                        raw.connect(InetSocketAddress(address, port), remaining())
                        raw.soTimeout = remaining()
                        val transport = if (uri.scheme == "https") {
                            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                                .createSocket(raw, host, port, true).let { it as SSLSocket }.apply {
                                    sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                                    soTimeout = remaining()
                                    startHandshake()
                                }
                        } else raw
                        try {
                            val path = uri.rawPath.takeIf { it.isNotEmpty() } ?: "/"
                            val target = path + (uri.rawQuery?.let { query -> "?$query" } ?: "")
                            val hostHeader = (if (host.contains(':')) "[$host]" else host) +
                                (if (uri.port >= 0) ":${uri.port}" else "")
                            val request = "GET $target HTTP/1.1\r\nHost: $hostHeader\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n"
                            transport.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                            transport.soTimeout = remaining()
                            BenchmarkHttp.read(transport.getInputStream(), maxBytes, deadline, now)
                        } finally {
                            raw.close()
                            transport.close()
                        }
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } catch (failure: Throwable) {
            socket.close()
            if (continuation.isActive) continuation.resumeWithException(failure)
        }
    }
}
