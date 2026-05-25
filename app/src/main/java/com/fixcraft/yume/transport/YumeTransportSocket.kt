/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.transport

import java.io.InputStream
import java.io.OutputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * Abstraction over the post-TLS-handshake byte pipe that
 * [YumeBootstrapSession] consumes.
 *
 * Two implementations:
 *
 * - [JavaTlsTransportSocket] wraps a Kotlin/Conscrypt [SSLSocket] —
 *   the legacy path. `outputStream.write()` blocks on kernel TCP buffer
 *   fill, which caps Android upload throughput at ~28 Mbps on Wi-Fi /
 *   180 ms RTT.
 *
 * - [NativeTlsTransportSocket] wraps a libyumetransport.so handle. The
 *   `outputStream.write()` call enqueues bytes into a native ring
 *   buffer and returns immediately; an epoll worker drives the
 *   non-blocking SSL_write loop. Matches the desktop CLI's
 *   boost::asio strand + async_write design.
 *
 * The interface only exposes what [YumeBootstrapSession] actually
 * needs from the underlying socket. Extending it has a real cost
 * (both backends must implement) so resist the temptation.
 */
interface YumeTransportSocket {
    val inputStream: InputStream
    val outputStream: OutputStream

    var soTimeout: Int

    val isConnected: Boolean
    val isClosed: Boolean

    fun close()

    // Handshake metadata. All immutable once the handshake completes.
    val tlsProtocol: String
    val tlsCipherSuite: String
    val peerCertificates: List<X509Certificate>

    // Diagnostic label for the perf reporter. `"java-sslsocket"` or
    // `"native-tls"`.
    val backendLabel: String
}

class JavaTlsTransportSocket(
    val socket: SSLSocket,
) : YumeTransportSocket {

    override val inputStream: InputStream get() = socket.inputStream
    override val outputStream: OutputStream get() = socket.outputStream

    override var soTimeout: Int
        get() = socket.soTimeout
        set(value) { socket.soTimeout = value }

    override val isConnected: Boolean get() = socket.isConnected
    override val isClosed: Boolean get() = socket.isClosed

    override fun close() {
        runCatching { socket.close() }
    }

    override val tlsProtocol: String
        get() = socket.session?.protocol ?: "TLS"
    override val tlsCipherSuite: String
        get() = socket.session?.cipherSuite ?: "Unknown"
    override val peerCertificates: List<X509Certificate>
        get() = socket.session?.peerCertificates
            ?.mapNotNull { it as? X509Certificate }
            ?: emptyList()

    override val backendLabel: String = "java-sslsocket"
}

/**
 * Wraps a libyumetransport.so handle. Caller is responsible for
 * delivering an already-connected protected TCP fd (typically
 * obtained from [android.os.ParcelFileDescriptor.fromSocket]) AND
 * having driven nativeStart to a successful handshake before
 * constructing this. After construction, [inputStream] /
 * [outputStream] are safe to use exactly like an SSLSocket's.
 *
 * `close()` is idempotent. Once close() runs, all subsequent
 * stream reads/writes throw.
 */
class NativeTlsTransportSocket internal constructor(
    private val handle: Long,
) : YumeTransportSocket {

    @Volatile
    private var closed: Boolean = false

    @Volatile
    private var readTimeoutMs: Int = 0

    override var soTimeout: Int
        get() = readTimeoutMs
        set(value) { readTimeoutMs = value.coerceAtLeast(0) }

    private val nativeInput = object : InputStream() {
        private val singleByteScratch = ByteArray(1)

        override fun read(): Int {
            val n = read(singleByteScratch, 0, 1)
            return if (n < 0) -1 else singleByteScratch[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw java.io.IOException("native tls pipe closed")
            if (len == 0) return 0
            val timeout = readTimeoutMs.let { if (it <= 0) -1 else it }
            while (true) {
                val rc = YumeNativeTlsPipe.read(handle, b, off, len, timeout)
                when {
                    rc > 0 -> return rc
                    rc == 0 -> {
                        // Timeout. SSLSocket signals this with
                        // SocketTimeoutException; we match.
                        throw java.net.SocketTimeoutException(
                            "native tls pipe read timed out after ${readTimeoutMs}ms"
                        )
                    }
                    rc == -1 -> return -1  // EOF
                    else -> throw java.io.IOException(
                        "native tls pipe read failed (rc=$rc)"
                    )
                }
            }
        }
    }

    private val nativeOutput = object : OutputStream() {
        override fun write(b: Int) {
            val one = byteArrayOf((b and 0xFF).toByte())
            write(one, 0, 1)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw java.io.IOException("native tls pipe closed")
            if (len == 0) return
            val rc = YumeNativeTlsPipe.write(handle, b, off, len)
            if (rc != len) {
                throw java.io.IOException(
                    "native tls pipe write rejected $len bytes (rc=$rc); " +
                        "backpressure cap reached or transport failed"
                )
            }
        }

        override fun flush() {
            // No-op — the native worker is already draining as fast
            // as the kernel accepts. There's no Java-side buffer to
            // flush; SSLSocket.outputStream.flush() is a no-op for
            // the same reason.
        }
    }

    override val inputStream: InputStream get() = nativeInput
    override val outputStream: OutputStream get() = nativeOutput

    override val isConnected: Boolean get() = !closed
    override val isClosed: Boolean get() = closed

    override fun close() {
        if (closed) return
        synchronized(this) {
            if (closed) return
            closed = true
            runCatching { YumeNativeTlsPipe.close(handle) }
            runCatching { YumeNativeTlsPipe.destroy(handle) }
        }
    }

    override val tlsProtocol: String = YumeNativeTlsPipe.tlsProtocol(handle)
    override val tlsCipherSuite: String = YumeNativeTlsPipe.tlsCipherSuite(handle)
    override val peerCertificates: List<X509Certificate> = buildPeerCertChain(handle)

    override val backendLabel: String = "native-tls"

    companion object {
        private fun buildPeerCertChain(handle: Long): List<X509Certificate> {
            val count = YumeNativeTlsPipe.peerCertCount(handle)
            if (count <= 0) return emptyList()
            val cf = CertificateFactory.getInstance("X.509")
            val out = ArrayList<X509Certificate>(count)
            for (i in 0 until count) {
                val der = YumeNativeTlsPipe.peerCertDer(handle, i) ?: continue
                val cert = runCatching {
                    cf.generateCertificate(der.inputStream()) as? X509Certificate
                }.getOrNull()
                if (cert != null) out.add(cert)
            }
            return out
        }
    }
}
