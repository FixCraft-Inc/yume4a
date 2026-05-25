package com.fixcraft.yume.transport

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.VaultJsonMapStore
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.YumeVpnRuntime
import org.conscrypt.Conscrypt
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.PrivateKeyFactory
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.abs

data class YumeTransportTuning(
    val tcpStreamMaxBufferedBytes: Int = 256 * 1024,
    val udpStreamMaxBufferedBytes: Int = 128 * 1024,
    val outboundFrameQueueCapacity: Int = 64,
    // When true AND libyumetransport.so loads successfully, the TLS
    // pipe is driven by a native non-blocking OpenSSL loop instead of
    // SSLSocket. Off by default for release-channel safety. The toggle
    // is wired to the Diagnostics screen ("Native transport (beta)")
    // and to AppSettings so operators can A/B compare in the field.
    // When the native lib is unavailable for the current ABI, the
    // bootstrap silently falls back to the Java SSLSocket path.
    val useNativeTransport: Boolean = false,
)

private class BufferedChunkQueue(
    private val maxBufferedBytes: Int,
) {
    private val channel = Channel<ByteArray>(Channel.UNLIMITED)
    private val bufferedBytes = AtomicInteger(0)

    fun trySend(data: ByteArray): Boolean {
        if (data.isEmpty()) {
            return true
        }
        while (true) {
            val current = bufferedBytes.get()
            val updated = current + data.size
            if (updated < current || updated > maxBufferedBytes) {
                return false
            }
            if (bufferedBytes.compareAndSet(current, updated)) {
                break
            }
        }
        val result = channel.trySend(data)
        if (!result.isSuccess) {
            bufferedBytes.addAndGet(-data.size)
            return false
        }
        return true
    }

    suspend fun receiveCatching() = channel.receiveCatching().also { result ->
        result.getOrNull()?.let { chunk ->
            bufferedBytes.addAndGet(-chunk.size)
        }
    }

    fun close(cause: Throwable? = null) {
        if (cause == null) {
            channel.close()
        } else {
            channel.close(cause)
        }
    }
}

data class YumeVerityInfo(
    val summary: String,
    val source: String,
    val peerFingerprintSha256: String,
)

data class YumeServerBootstrap(
    val serverVersion: String,
    val serverMode: String,
    val tlsProtocol: String,
    val tlsCipherSuite: String,
    val verity: YumeVerityInfo,
    val innerSupported: Boolean,
    val innerRequired: Boolean,
    val innerDual: Boolean,
    val innerActive: Boolean,
    val innerMode: String,
    val innerKdf: String?,
    val capPq: Boolean,
    val capArgon2: Boolean,
    val capPbkdf2: Boolean,
    val hopEnabled: Boolean,
    val hopIntervalMs: Int,
    val serverTimeMs: Long,
)

private interface YumeInboundStream {
    val streamId: Int
    val isClosed: Boolean
    fun offerIncoming(data: ByteArray): Boolean
    fun closeFromRemote(reason: String?)
    fun fail(cause: Throwable)
}

class YumeTcpStream internal constructor(
    private val session: YumeBootstrapSession,
    override val streamId: Int,
    maxBufferedBytes: Int,
) : YumeInboundStream {
    private val incomingChunks = BufferedChunkQueue(maxBufferedBytes)
    private val closedReason = CompletableDeferred<String?>()

    override val isClosed: Boolean
        get() = closedReason.isCompleted

    suspend fun readChunk(): ByteArray? {
        val result = incomingChunks.receiveCatching()
        result.exceptionOrNull()?.let { throwable ->
            throw if (throwable is Exception) {
                throwable
            } else {
                IOException("The Yume TCP stream failed", throwable)
            }
        }
        return result.getOrNull()
    }

    suspend fun write(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        require(offset in 0..data.size) { "offset out of range" }
        require(length >= 0 && offset + length <= data.size) { "length out of range" }
        if (length == 0) {
            return
        }
        // Use the *queued* send so callers don't serialize on each
        // TLS write completion. Pre-fix, every chunk awaited
        // pending.completion (sendData -> sendEncodedFrame
        // waitForWrite=true), which made the SOCKS bridge effectively
        // one-frame-per-RTT and capped Android downloads to ~1-8 Mbps
        // on Wi-Fi regardless of CPU. With the queued send, the
        // outbound channel (capacity outboundFrameQueueCapacity)
        // provides backpressure but lets runWriteLoop coalesce up
        // to MAX_OUTBOUND_COALESCE_FRAMES (16) frames /
        // MAX_OUTBOUND_COALESCE_BYTES (64 KiB) into a single TLS
        // write — the actual fast path the writer was already built
        // for. Desktop CLI was fast because its socket loop is on
        // gigabit-loopback latency where the difference is invisible.
        session.sendDataQueued(streamId, data, offset, length)
    }

    suspend fun close(reason: String = "") {
        if (closedReason.isCompleted) {
            return
        }
        try {
            session.closeStream(streamId, reason)
        } finally {
            closeFromRemote(reason.ifBlank { null })
        }
    }

    override fun offerIncoming(data: ByteArray): Boolean = incomingChunks.trySend(data)

    override fun closeFromRemote(reason: String?) {
        if (closedReason.complete(reason)) {
            incomingChunks.close()
        }
    }

    override fun fail(cause: Throwable) {
        if (closedReason.complete(cause.message)) {
            incomingChunks.close(cause)
        }
    }
}

class YumeUdpStream internal constructor(
    private val session: YumeBootstrapSession,
    override val streamId: Int,
    maxBufferedBytes: Int,
) : YumeInboundStream {
    private val incomingChunks = BufferedChunkQueue(maxBufferedBytes)
    private val closedReason = CompletableDeferred<String?>()

    override val isClosed: Boolean
        get() = closedReason.isCompleted

    suspend fun readDatagram(): ByteArray? {
        val result = incomingChunks.receiveCatching()
        result.exceptionOrNull()?.let { throwable ->
            throw if (throwable is Exception) {
                throwable
            } else {
                IOException("The Yume UDP stream failed", throwable)
            }
        }
        return result.getOrNull()
    }

    suspend fun sendDatagram(data: ByteArray) {
        if (data.isEmpty()) {
            return
        }
        // Same reasoning as YumeTcpStream.write — queued send lets
        // the writer batch multiple UDP frames per TLS write.
        session.sendDataQueued(streamId, data)
    }

    suspend fun close(reason: String = "") {
        if (closedReason.isCompleted) {
            return
        }
        try {
            session.closeStream(streamId, reason)
        } finally {
            closeFromRemote(reason.ifBlank { null })
        }
    }

    override fun offerIncoming(data: ByteArray): Boolean = incomingChunks.trySend(data)

    override fun closeFromRemote(reason: String?) {
        if (closedReason.complete(reason)) {
            incomingChunks.close()
        }
    }

    override fun fail(cause: Throwable) {
        if (closedReason.complete(cause.message)) {
            incomingChunks.close(cause)
        }
    }
}

class YumeBootstrapSession internal constructor(
    private val transport: YumeTransportSocket,
    val bootstrap: YumeServerBootstrap,
    private val innerState: YumeInnerSessionState? = null,
    private val transportTuning: YumeTransportTuning = YumeTransportTuning(),
) {
    private val input = transport.inputStream
    private val output = transport.outputStream

    // Cheap accessor for the perf reporter: "native-tls" vs "java-sslsocket".
    val transportBackendLabel: String get() = transport.backendLabel
    private val writeMutex = Mutex()
    private val stateLock = Any()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboundFrames = Channel<PendingWrite>(transportTuning.outboundFrameQueueCapacity)
    // Priority channel for control frames (PING, PONG, CLOSE, CONTROL,
    // OPEN). The write loop drains this BEFORE outboundFrames. Without
    // this, a saturated upload (DATA frames filling outboundFrames +
    // writer blocked on output.write because the kernel TCP send
    // buffer is full) starves the keepalive PING — server times the
    // session out and the VPN re-auths mid-test. 32 slots is plenty:
    // we usually have at most a few in-flight control frames.
    private val controlFrames = Channel<PendingWrite>(32)
    private val pendingOpen = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private val pendingPongs = ArrayDeque<CompletableDeferred<Unit>>()
    private val pendingControl = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val streams = mutableMapOf<Int, YumeInboundStream>()
    private var nextStreamId = 1
    @Volatile
    private var closingCause: Throwable? = null
    @Volatile
    private var closeState = CloseState.Open

    // Per-second throughput counters. The reporter coroutine logs a
    // one-line summary every PERF_REPORT_INTERVAL_MS so we can see
    // WHERE bandwidth is going on a slow connection. Enabled when
    // System.getProperty("yume.perf") == "1" OR YUME_PERF=1 env, OR
    // when the static debug flag is set. Lightweight: just atomic
    // counters, ~zero overhead when not logged.
    private val perfBytesEncrypted = java.util.concurrent.atomic.AtomicLong(0)
    private val perfBytesDecrypted = java.util.concurrent.atomic.AtomicLong(0)
    private val perfFramesSent     = java.util.concurrent.atomic.AtomicLong(0)
    private val perfFramesRecv     = java.util.concurrent.atomic.AtomicLong(0)
    private val perfTlsBytesWritten = java.util.concurrent.atomic.AtomicLong(0)
    private val perfTlsBytesRead    = java.util.concurrent.atomic.AtomicLong(0)
    private val perfEncryptNanos   = java.util.concurrent.atomic.AtomicLong(0)
    private val perfDecryptNanos   = java.util.concurrent.atomic.AtomicLong(0)
    private val perfWriteWaitNanos = java.util.concurrent.atomic.AtomicLong(0)
    private val perfTlsWriteNanos  = java.util.concurrent.atomic.AtomicLong(0)

    init {
        transport.soTimeout = 0
        sessionScope.launch {
            runWriteLoop()
        }
        sessionScope.launch {
            runReadLoop()
        }
        if (perfEnabled()) {
            sessionScope.launch {
                runPerfReporter()
            }
            sessionScope.launch {
                runSubSecondFlowReporter()
            }
        }
    }

    private fun perfEnabled(): Boolean {
        return System.getProperty("yume.perf") == "1" ||
            System.getenv("YUME_PERF") == "1" ||
            YumePerfDebug.enabled
    }

    private suspend fun runPerfReporter() {
        var lastWallMs = System.currentTimeMillis()
        var lastEnc = 0L
        var lastDec = 0L
        var lastFs  = 0L
        var lastFr  = 0L
        var lastTw  = 0L
        var lastTr  = 0L
        var lastEncN = 0L
        var lastDecN = 0L
        var lastWwN  = 0L
        var lastTwN  = 0L
        while (true) {
            kotlinx.coroutines.delay(PERF_REPORT_INTERVAL_MS)
            if (closeState != CloseState.Open) return
            val now = System.currentTimeMillis()
            val dt = (now - lastWallMs).coerceAtLeast(1L)
            lastWallMs = now
            val enc = perfBytesEncrypted.get(); val dEnc = enc - lastEnc; lastEnc = enc
            val dec = perfBytesDecrypted.get(); val dDec = dec - lastDec; lastDec = dec
            val fs  = perfFramesSent.get();     val dFs  = fs  - lastFs;  lastFs  = fs
            val fr  = perfFramesRecv.get();     val dFr  = fr  - lastFr;  lastFr  = fr
            val tw  = perfTlsBytesWritten.get();val dTw  = tw  - lastTw;  lastTw  = tw
            val tr  = perfTlsBytesRead.get();   val dTr  = tr  - lastTr;  lastTr  = tr
            val encN = perfEncryptNanos.get();  val dEncN = encN - lastEncN; lastEncN = encN
            val decN = perfDecryptNanos.get();  val dDecN = decN - lastDecN; lastDecN = decN
            val wwN  = perfWriteWaitNanos.get();val dWwN  = wwN  - lastWwN;  lastWwN  = wwN
            val twN  = perfTlsWriteNanos.get(); val dTwN  = twN  - lastTwN;  lastTwN  = twN
            // All `/s` figures below are normalized to per-second by
            // `* 1000 / dt`. Without this, a 2000 ms window would
            // report "2176ms/s" when the writer was 100% pegged —
            // misleading. cpu/wait values can still exceed 1000ms/s
            // because multiple producer coroutines can accumulate wait
            // time concurrently (e.g. several streams blocked on the
            // outbound channel at once).
            val mbpsEnc = (dEnc * 8.0 / 1_000_000.0) * 1000.0 / dt
            val mbpsDec = (dDec * 8.0 / 1_000_000.0) * 1000.0 / dt
            val mbpsTw  = (dTw  * 8.0 / 1_000_000.0) * 1000.0 / dt
            val mbpsTr  = (dTr  * 8.0 / 1_000_000.0) * 1000.0 / dt
            val fsPerSec = dFs * 1000.0 / dt
            val frPerSec = dFr * 1000.0 / dt
            val encMsPerSec = (dEncN / 1_000_000.0) * 1000.0 / dt
            val decMsPerSec = (dDecN / 1_000_000.0) * 1000.0 / dt
            val twMsPerSec  = (dTwN  / 1_000_000.0) * 1000.0 / dt
            val wwMsPerSec  = (dWwN  / 1_000_000.0) * 1000.0 / dt
            val msg = String.format(
                "[yume-perf] transport=%s tls-out=%.1fMbps tls-in=%.1fMbps " +
                "enc=%.1fMbps dec=%.1fMbps " +
                "frames-sent=%.0f/s frames-recv=%.0f/s outQ=%d enc-cpu=%.0fms/s dec-cpu=%.0fms/s " +
                "tls-write-cpu=%.0fms/s write-wait=%.0fms/s",
                transport.backendLabel,
                mbpsTw, mbpsTr, mbpsEnc, mbpsDec, fsPerSec, frPerSec,
                outboundFramesDepthApproximation(),
                encMsPerSec, decMsPerSec, twMsPerSec, wwMsPerSec,
            )
            android.util.Log.i("YumePerf", msg)
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, msg)
        }
    }

    // Sub-second flow reporter. Samples perfTlsBytesWritten every 100ms
    // into a 50-slot ring (5 s window) and prints the buckets once per
    // second. This is what visually proves "live flowing" vs sawtooth:
    // an SSLSocket bottleneck shows long 0 gaps followed by big spikes
    // when ACKs arrive; the native pipe shows smooth, similar values
    // across all 50 buckets.
    //
    // Output format:
    //   [yume-flow] transport=native window=5.0s (KB/100ms): 124 130 ...
    //               min=110 max=150 med=128
    //
    // Costs ~10 atomic reads per second + one log line. Negligible.
    private suspend fun runSubSecondFlowReporter() {
        val bucketMs = 100L
        val windowSlots = 50               // 5 s sliding window
        val printIntervalSlots = 10        // print once per 1000 ms
        val outBuckets = LongArray(windowSlots)
        val inBuckets = LongArray(windowSlots)
        var idx = 0
        var lastOut = perfTlsBytesWritten.get()
        var lastIn  = perfTlsBytesRead.get()
        var tickCount = 0
        while (true) {
            kotlinx.coroutines.delay(bucketMs)
            if (closeState != CloseState.Open) return
            val curOut = perfTlsBytesWritten.get()
            val curIn  = perfTlsBytesRead.get()
            outBuckets[idx] = (curOut - lastOut).coerceAtLeast(0L)
            inBuckets[idx]  = (curIn  - lastIn ).coerceAtLeast(0L)
            lastOut = curOut
            lastIn  = curIn
            idx = (idx + 1) % windowSlots
            tickCount += 1
            if (tickCount % printIntervalSlots != 0) continue

            // Render in chronological order: oldest first.
            val orderedOut = LongArray(windowSlots) { i -> outBuckets[(idx + i) % windowSlots] }
            val orderedIn  = LongArray(windowSlots) { i -> inBuckets [(idx + i) % windowSlots] }
            val out = String.format(
                "[yume-flow] transport=%s window=%.1fs out(KB/100ms)=[%s] min=%d max=%d med=%d",
                transport.backendLabel,
                windowSlots * bucketMs / 1000.0,
                orderedOut.joinToString(" ") { (it / 1024).toString() },
                orderedOut.min() / 1024,
                orderedOut.max() / 1024,
                orderedOut.sortedArray()[orderedOut.size / 2] / 1024,
            )
            val inb = String.format(
                "[yume-flow]                in (KB/100ms)=[%s] min=%d max=%d med=%d",
                orderedIn.joinToString(" ") { (it / 1024).toString() },
                orderedIn.min() / 1024,
                orderedIn.max() / 1024,
                orderedIn.sortedArray()[orderedIn.size / 2] / 1024,
            )
            android.util.Log.i("YumePerf", out)
            android.util.Log.i("YumePerf", inb)
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, out)
            YumeVpnRuntime.appendLog(VpnLogLevel.Info, inb)
        }
    }

    private fun outboundFramesDepthApproximation(): Int {
        // Channel doesn't expose size publicly. Best-effort estimate
        // via Channel.empty() check; non-trivial introspection would
        // require reflection so we just return -1 to mean "unknown"
        // for now. Producers and consumers race anyway.
        return -1
    }

    val isAlive: Boolean
        get() = closeState == CloseState.Open && transport.isConnected && !transport.isClosed && closingCause == null

    val closeReason: String?
        get() = closingCause?.message

    suspend fun ping(timeoutMs: Int = DEFAULT_PING_TIMEOUT_MS) {
        val waiter = CompletableDeferred<Unit>()
        synchronized(stateLock) {
            ensureOpenLocked()
            pendingPongs.addLast(waiter)
        }
        try {
            sendFrame(YumeFrame(type = YumeFrameType.PING))
            withTimeout(timeoutMs.toLong()) {
                waiter.await()
            }
        } catch (throwable: Exception) {
            synchronized(stateLock) {
                pendingPongs.remove(waiter)
            }
            throw YumeBootstrapException("The Yume server did not answer the keepalive ping in time", throwable)
        }
    }

    suspend fun openTcpStream(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_STREAM_TIMEOUT_MS,
    ): YumeTcpStream {
        return openStream(
            host = host,
            port = port,
            proto = "tcp",
            timeoutMs = timeoutMs,
            createStream = { streamId -> YumeTcpStream(this, streamId, transportTuning.tcpStreamMaxBufferedBytes) },
            buildFailure = { throwable ->
                if (throwable is YumeBootstrapException) {
                    throwable
                } else {
                    YumeBootstrapException("Failed to open a Yume TCP stream to $host:$port", throwable)
                }
            },
        )
    }

    suspend fun openTcpStreamOptimistic(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_STREAM_TIMEOUT_MS,
    ): YumeTcpStream {
        return openStream(
            host = host,
            port = port,
            proto = "tcp",
            timeoutMs = timeoutMs,
            waitForOpen = false,
            createStream = { streamId -> YumeTcpStream(this, streamId, transportTuning.tcpStreamMaxBufferedBytes) },
            buildFailure = { throwable ->
                if (throwable is YumeBootstrapException) {
                    throwable
                } else {
                    YumeBootstrapException("Failed to open a Yume TCP stream to $host:$port", throwable)
                }
            },
        )
    }

    suspend fun openUdpStream(
        host: String,
        port: Int,
        timeoutMs: Int = DEFAULT_STREAM_TIMEOUT_MS,
    ): YumeUdpStream {
        return openStream(
            host = host,
            port = port,
            proto = "udp",
            timeoutMs = timeoutMs,
            createStream = { streamId -> YumeUdpStream(this, streamId, transportTuning.udpStreamMaxBufferedBytes) },
            buildFailure = { throwable ->
                if (throwable is YumeBootstrapException) {
                    throwable
                } else {
                    YumeBootstrapException("Failed to open a Yume UDP stream to $host:$port", throwable)
                }
            },
        )
    }

    suspend fun sendControlRequest(
        request: JSONObject,
        timeoutMs: Int = DEFAULT_CONTROL_TIMEOUT_MS,
    ): JSONObject {
        val requestId = request.optString("request_id").trim().ifEmpty { UUID.randomUUID().toString() }
        request.put("request_id", requestId)
        val waiter = CompletableDeferred<JSONObject>()
        synchronized(stateLock) {
            ensureOpenLocked()
            pendingControl[requestId] = waiter
        }
        try {
            sendFrame(
                YumeFrame(
                    type = YumeFrameType.CONTROL,
                    payload = request.toString().toByteArray(StandardCharsets.UTF_8),
                ),
            )
            return withTimeout(timeoutMs.toLong()) {
                waiter.await()
            }
        } catch (throwable: Exception) {
            synchronized(stateLock) {
                pendingControl.remove(requestId)
            }
            throw if (throwable is YumeBootstrapException) {
                throwable
            } else {
                YumeBootstrapException("Timed out waiting for Yume control response", throwable)
            }
        }
    }

    private suspend fun <T : YumeInboundStream> openStream(
        host: String,
        port: Int,
        proto: String,
        timeoutMs: Int,
        waitForOpen: Boolean = true,
        createStream: (Int) -> T,
        buildFailure: (Throwable) -> YumeBootstrapException,
    ): T {
        require(port in 1..65535) { "port out of range" }
        val stream: T
        val waiter: CompletableDeferred<Unit>
        synchronized(stateLock) {
            ensureOpenLocked()
            val streamId = allocateStreamIdLocked()
            stream = createStream(streamId)
            waiter = CompletableDeferred()
            streams[streamId] = stream
            pendingOpen[streamId] = waiter
        }

        val openPayload = JSONObject()
            .put("host", host)
            .put("port", port)
            .put("proto", proto)
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        try {
            val openFrame = YumeFrame(
                type = YumeFrameType.OPEN,
                streamId = stream.streamId,
                payload = openPayload,
            )
            if (!waitForOpen) {
                enqueueFrame(
                    encodeOutgoingFrame(openFrame),
                    waitForWrite = false,
                )
                scheduleOpenTimeout(
                    stream = stream,
                    waiter = waiter,
                    proto = proto,
                    host = host,
                    port = port,
                    timeoutMs = timeoutMs,
                )
                return stream
            }
            sendFrame(openFrame)
            withTimeout(timeoutMs.toLong()) {
                waiter.await()
            }
            return stream
        } catch (throwable: Exception) {
            synchronized(stateLock) {
                pendingOpen.remove(stream.streamId)
                streams.remove(stream.streamId)
            }
            val failure = if (throwable is TimeoutCancellationException) {
                runCatching {
                    sendFrame(
                        YumeFrame(
                            type = YumeFrameType.CLOSE,
                            streamId = stream.streamId,
                            payload = "client open timeout".toByteArray(StandardCharsets.UTF_8),
                        ),
                    )
                }
                YumeBootstrapException(
                    "Timed out waiting for the Yume server to open $proto stream to $host:$port after ${timeoutMs}ms",
                    throwable,
                )
            } else {
                throwable
            }
            stream.fail(failure)
            throw buildFailure(failure)
        }
    }

    private fun scheduleOpenTimeout(
        stream: YumeInboundStream,
        waiter: CompletableDeferred<Unit>,
        proto: String,
        host: String,
        port: Int,
        timeoutMs: Int,
    ) {
        sessionScope.launch {
            delay(timeoutMs.toLong().coerceAtLeast(1L))
            val shouldFail = synchronized(stateLock) {
                if (pendingOpen[stream.streamId] === waiter) {
                    pendingOpen.remove(stream.streamId)
                    streams.remove(stream.streamId)
                    true
                } else {
                    false
                }
            }
            if (!shouldFail) {
                return@launch
            }
            val failure = YumeBootstrapException(
                "Timed out waiting for the Yume server to open $proto stream to $host:$port after ${timeoutMs}ms",
            )
            stream.fail(failure)
            runCatching {
                sendFrame(
                    YumeFrame(
                        type = YumeFrameType.CLOSE,
                        streamId = stream.streamId,
                        payload = "client open timeout".toByteArray(StandardCharsets.UTF_8),
                    ),
                )
            }
        }
    }

    suspend fun probeExitIp(
        host: String = "ifconfig.me",
        path: String = "/ip",
        port: Int = 80,
        timeoutMs: Int = DEFAULT_STREAM_TIMEOUT_MS,
    ): String {
        val stream = openTcpStream(host = host, port = port, timeoutMs = timeoutMs)
        return try {
            val request = buildString {
                append("GET $path HTTP/1.1\r\n")
                append("Host: $host\r\n")
                append("User-Agent: YumeAndroid/${BuildConfig.VERSION_NAME}\r\n")
                append("Accept: text/plain\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)
            try {
                stream.write(request)
            } catch (throwable: Exception) {
                throw YumeBootstrapException(
                    "Relay canary stream ${stream.streamId} failed while sending the HTTP request",
                    throwable,
                )
            }

            val response = ByteArrayOutputStream()
            var receivedResponseBytes = false
            while (true) {
                val chunk = try {
                    withTimeout(timeoutMs.toLong()) {
                        stream.readChunk()
                    }
                } catch (throwable: Exception) {
                    val stage = if (receivedResponseBytes) {
                        "while reading the relay canary response"
                    } else {
                        "while waiting for the first relay canary response bytes"
                    }
                    throw YumeBootstrapException(
                        "Relay canary stream ${stream.streamId} failed $stage",
                        throwable,
                    )
                } ?: break
                receivedResponseBytes = true
                response.write(chunk)
                if (response.size() > MAX_PROBE_RESPONSE_BYTES) {
                    throw YumeBootstrapException("The Yume traffic probe returned too much data")
                }
            }

            val httpResponse = response.toString(StandardCharsets.UTF_8.name())
            parseHttpBody(httpResponse)
        } finally {
            if (!stream.isClosed) {
                try {
                    stream.close("probe complete")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun close() {
        shutdown(YumeBootstrapException("The authenticated Yume session was closed"))
    }

    internal suspend fun sendData(
        streamId: Int,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ) {
        enqueueFrame(buildOutgoingDataFrame(streamId, data, offset, length))
    }

    internal suspend fun sendDataQueued(
        streamId: Int,
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size,
    ) {
        enqueueFrame(
            buildOutgoingDataFrame(streamId, data, offset, length),
            waitForWrite = false,
        )
    }

    internal suspend fun closeStream(streamId: Int, reason: String) {
        synchronized(stateLock) {
            pendingOpen.remove(streamId)
            streams.remove(streamId)
        }
        sendFrame(YumeFrame(type = YumeFrameType.CLOSE, streamId = streamId, payload = reason.toByteArray(StandardCharsets.UTF_8)))
    }

    private suspend fun runReadLoop() {
        try {
            while (true) {
                val rawFrame = YumeFrameCodec.readFrame(input)
                perfTlsBytesRead.addAndGet((8L + rawFrame.payload.size))
                perfFramesRecv.incrementAndGet()
                val frame = decodeIncomingFrame(rawFrame)
                when (frame.type) {
                    YumeFrameType.PING -> {
                        sendFrame(YumeFrame(type = YumeFrameType.PONG))
                    }

                    YumeFrameType.PONG -> {
                        val waiter = synchronized(stateLock) {
                            if (pendingPongs.isEmpty()) null else pendingPongs.removeFirst()
                        }
                        waiter?.complete(Unit)
                    }

                    YumeFrameType.OPEN -> {
                        val ok = (frame.flags and 0x0001) != 0
                        val reason = frame.payload.toString(StandardCharsets.UTF_8).ifBlank {
                            if (ok) "opened" else "open failed"
                        }
                        val waiter = synchronized(stateLock) {
                            pendingOpen.remove(frame.streamId)
                        }
                        val stream = synchronized(stateLock) {
                            streams[frame.streamId]
                        }
                        if (ok) {
                            waiter?.complete(Unit)
                        } else {
                            synchronized(stateLock) {
                                streams.remove(frame.streamId)
                            }
                            waiter?.completeExceptionally(
                                YumeBootstrapException("The Yume server rejected the stream: $reason")
                            )
                            stream?.closeFromRemote(reason)
                        }
                    }

                    YumeFrameType.DATA -> {
                        val stream = synchronized(stateLock) {
                            streams[frame.streamId]
                        }
                        // Kill the stream on receive-buffer overflow.
                        // YUME has no per-stream WINDOW_UPDATE frame, so
                        // there is no way for us to signal "slow down" to
                        // the server other than closing the stream — the
                        // close propagates back as TCP FIN through the
                        // server's outbound socket, which is what
                        // throttles the remote sender. The previous
                        // attempt to "silently drop" the excess payload
                        // instead made things worse: the server kept
                        // blasting at line-rate while we threw the data
                        // away, and the connection never recovered.
                        if (stream != null && !stream.offerIncoming(frame.payload)) {
                            synchronized(stateLock) {
                                streams.remove(frame.streamId)
                                pendingOpen.remove(frame.streamId)
                            }
                            stream.fail(YumeBootstrapException("Incoming Yume stream overflow"))
                            sendFrame(YumeFrame(type = YumeFrameType.CLOSE, streamId = frame.streamId, payload = "client receive buffer overflow".toByteArray(StandardCharsets.UTF_8)))
                        }
                    }

                    YumeFrameType.CLOSE -> {
                        val reason = frame.payload.toString(StandardCharsets.UTF_8).ifBlank { "remote closed the stream" }
                        val waiter = synchronized(stateLock) {
                            pendingOpen.remove(frame.streamId)
                        }
                        val stream = synchronized(stateLock) {
                            streams.remove(frame.streamId)
                        }
                        waiter?.completeExceptionally(YumeBootstrapException(reason))
                        stream?.closeFromRemote(reason)
                    }

                    YumeFrameType.CONTROL -> {
                        val json = try {
                            JSONObject(String(frame.payload, StandardCharsets.UTF_8))
                        } catch (throwable: Exception) {
                            throw YumeBootstrapException("The Yume server sent invalid CONTROL JSON", throwable)
                        }
                        val requestId = json.optString("request_id").trim()
                        if (requestId.isNotEmpty()) {
                            val waiter = synchronized(stateLock) {
                                pendingControl.remove(requestId)
                            }
                            waiter?.complete(json)
                        } else {
                            val cmd = json.optString("cmd").trim()
                            if (cmd == "server.closing") {
                                val reason = json.optString("reason").trim()
                                    .ifBlank { json.optString("message").trim() }
                                    .ifBlank { "server closed, kicked" }
                                throw YumeBootstrapException(reason)
                            }
                        }
                    }

                    else -> {
                    }
                }
            }
        } catch (throwable: Exception) {
            if (isExpectedCloseDuringShutdown(throwable)) {
                synchronized(stateLock) {
                    closeState = CloseState.Closed
                }
                sessionScope.cancel()
                return
            }
            shutdown(
                if (throwable is YumeBootstrapException) {
                    throwable
                } else {
                    YumeBootstrapException(
                        throwable.message ?: "The authenticated Yume session ended unexpectedly",
                        throwable,
                    )
                }
            )
        }
    }

    private suspend fun runWriteLoop() {
        var activeBatch: List<PendingWrite> = emptyList()
        try {
            while (true) {
                ensureOpen()
                val first = takeNextPending() ?: break
                activeBatch = collectWriteBatch(first)
                val encodedBatch = encodeWriteBatch(activeBatch)
                writeMutex.withLock {
                    ensureOpen()
                    val twStart = System.nanoTime()
                    output.write(encodedBatch.buffer, 0, encodedBatch.length)
                    perfTlsWriteNanos.addAndGet(System.nanoTime() - twStart)
                    perfTlsBytesWritten.addAndGet(encodedBatch.length.toLong())
                    perfFramesSent.addAndGet(activeBatch.size.toLong())
                }
                activeBatch.forEach { it.completion?.complete(Unit) }
                activeBatch = emptyList()
            }
        } catch (throwable: Exception) {
            val failure = if (throwable is YumeBootstrapException) {
                throwable
            } else {
                YumeBootstrapException(
                    throwable.message ?: "The authenticated Yume session ended unexpectedly",
                    throwable,
                )
            }
            activeBatch.forEach { it.completion?.completeExceptionally(failure) }
            activeBatch = emptyList()
            if (closeState != CloseState.Open || isExpectedCloseDuringShutdown(throwable)) {
                synchronized(stateLock) {
                    closeState = CloseState.Closed
                }
                return
            }
            shutdown(failure)
        } finally {
            val failure = synchronized(stateLock) {
                closingCause ?: YumeBootstrapException("The authenticated Yume session was closed")
            }
            while (true) {
                val pending = controlFrames.tryReceive().getOrNull() ?: break
                pending.completion?.completeExceptionally(failure)
            }
            while (true) {
                val pending = outboundFrames.tryReceive().getOrNull() ?: break
                pending.completion?.completeExceptionally(failure)
            }
        }
    }

    // Pull the next frame to send, draining `controlFrames` first so
    // PING/PONG/CLOSE/CONTROL never sit behind a wall of DATA frames
    // while an upload is saturating outboundFrames. When both queues
    // are empty we suspend in `select` and resume on whichever side
    // gets a frame. Returns null only after both channels are
    // closed-and-empty so the write loop can exit cleanly.
    //
    // Care: a closed Channel makes `onReceiveCatching` fire IMMEDIATELY
    // with a closed result on every select iteration. Including a
    // closed branch in `select` would CPU-spin returning null. We
    // therefore only register select branches for channels that are
    // still open (or have buffered items).
    private suspend fun takeNextPending(): PendingWrite? {
        while (true) {
            controlFrames.tryReceive().getOrNull()?.let { return it }
            outboundFrames.tryReceive().getOrNull()?.let { return it }
            val controlOpen = !controlFrames.isClosedForReceive
            val outboundOpen = !outboundFrames.isClosedForReceive
            if (!controlOpen && !outboundOpen) {
                return null
            }
            val winner = select<PendingWrite?> {
                if (controlOpen) {
                    controlFrames.onReceiveCatching { result ->
                        result.getOrNull()
                    }
                }
                if (outboundOpen) {
                    outboundFrames.onReceiveCatching { result ->
                        result.getOrNull()
                    }
                }
            }
            if (winner != null) return winner
            // winner==null means the side we picked closed between the
            // open-check and the select — loop back, the next iteration
            // will see the closed flag and either drain the still-open
            // side or exit.
        }
    }

    private fun collectWriteBatch(first: PendingWrite): List<PendingWrite> {
        val batch = ArrayList<PendingWrite>(4)
        batch += first
        var totalBytes = first.encodedSize
        // Drain control frames first — cheap, small, and we want them
        // out NOW to keep keepalive responsive.
        while (batch.size < MAX_OUTBOUND_COALESCE_FRAMES && totalBytes < MAX_OUTBOUND_COALESCE_BYTES) {
            val next = controlFrames.tryReceive().getOrNull() ?: break
            batch += next
            totalBytes += next.encodedSize
        }
        while (batch.size < MAX_OUTBOUND_COALESCE_FRAMES && totalBytes < MAX_OUTBOUND_COALESCE_BYTES) {
            val next = outboundFrames.tryReceive().getOrNull() ?: break
            batch += next
            totalBytes += next.encodedSize
        }
        return batch
    }

    // Reusable scratch buffer for batch coalescing. Only written from
    // runWriteLoop's single coroutine, so no synchronization needed.
    // Grows monotonically (doubling) up to MAX_OUTBOUND_COALESCE_BYTES
    // and is never reclaimed for the lifetime of the session — that's
    // exactly the point: every output.write batch reuses this instead
    // of allocating a fresh ByteArray of 16-64 KiB. At ~100 batches/sec
    // during high throughput, eliminating that allocation pressure is
    // worth ~1-2 MiB/s less garbage for the GC to collect, which
    // directly buys back the 80-100 ms GC pauses observed in
    // logg.log 14:18:48.
    @Volatile
    private var writeBatchScratch: ByteArray = ByteArray(0)

    private data class EncodedBatch(val buffer: ByteArray, val length: Int)

    private fun encodeWriteBatch(batch: List<PendingWrite>): EncodedBatch {
        val totalBytes = batch.sumOf { it.encodedSize }
        var scratch = writeBatchScratch
        if (scratch.size < totalBytes) {
            scratch = ByteArray(maxOf(totalBytes, scratch.size * 2, 16 * 1024))
            writeBatchScratch = scratch
        }
        var offset = 0
        batch.forEach { pending ->
            val frame = pending.frame
            val payload = frame.payload
            val payloadLen = payload.size
            // 8-byte big-endian header: len(4) / type(1) / streamId(1) / flags(2)
            scratch[offset]     = ((payloadLen ushr 24) and 0xFF).toByte()
            scratch[offset + 1] = ((payloadLen ushr 16) and 0xFF).toByte()
            scratch[offset + 2] = ((payloadLen ushr 8)  and 0xFF).toByte()
            scratch[offset + 3] = (payloadLen and 0xFF).toByte()
            scratch[offset + 4] = (frame.type.wireValue and 0xFF).toByte()
            scratch[offset + 5] = (frame.streamId and 0xFF).toByte()
            scratch[offset + 6] = ((frame.flags ushr 8) and 0xFF).toByte()
            scratch[offset + 7] = (frame.flags and 0xFF).toByte()
            offset += 8
            if (payloadLen > 0) {
                payload.copyInto(scratch, destinationOffset = offset)
                offset += payloadLen
            }
        }
        return EncodedBatch(scratch, totalBytes)
    }

    private suspend fun sendFrame(frame: YumeFrame) {
        enqueueFrame(
            encodeOutgoingFrame(frame),
            priority = frame.type != YumeFrameType.DATA,
        )
    }

    private suspend fun enqueueFrame(
        frame: YumeFrame,
        waitForWrite: Boolean = true,
        priority: Boolean = false,
    ) {
        ensureOpen()
        val pending = PendingWrite(
            frame = frame,
            completion = if (waitForWrite) CompletableDeferred() else null,
        )
        try {
            val sendStart = System.nanoTime()
            if (priority) {
                controlFrames.send(pending)
            } else {
                outboundFrames.send(pending)
            }
            perfWriteWaitNanos.addAndGet(System.nanoTime() - sendStart)
            pending.completion?.await()
        } catch (throwable: Exception) {
            if (throwable is YumeBootstrapException) {
                throw throwable
            }
            throw YumeBootstrapException(
                throwable.message ?: "Failed to write a Yume frame",
                throwable,
            )
        }
    }

    // Returns a YumeFrame ready to be queued — payload is already
    // inner-encrypted if required. The runWriteLoop scratch buffer
    // serialises the 8-byte header + payload directly, so no encoded
    // ByteArray is created here.
    //
    // (Earlier revision attempted an exact-size payloadBufferPool to
    // avoid the per-frame ciphertext allocation, but variable upload
    // chunk sizes meant pool buckets rarely hit; the ConcurrentHashMap
    // bookkeeping overhead added more cost than the alloc itself. The
    // remaining alloc — `length + 28` bytes for ciphertext — is the
    // smallest LOS allocation in the path and not the dominant GC
    // pressure source.)
    private fun buildOutgoingDataFrame(
        streamId: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): YumeFrame {
        require(offset in 0..data.size) { "offset out of range" }
        require(length >= 0 && offset + length <= data.size) { "length out of range" }
        if (length == 0) {
            return YumeFrame(type = YumeFrameType.DATA, streamId = streamId)
        }
        val state = innerState
        if (state == null || !requiresInnerEncryption(YumeFrameType.DATA)) {
            val payload = if (offset == 0 && length == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + length)
            }
            return YumeFrame(type = YumeFrameType.DATA, streamId = streamId, payload = payload)
        }
        val payload = if (offset == 0 && length == data.size) {
            data
        } else {
            data.copyOfRange(offset, offset + length)
        }
        val encStart = System.nanoTime()
        val encrypted = YumeInnerCrypto.encrypt(state, YumeFrameType.DATA, streamId, payload)
        perfEncryptNanos.addAndGet(System.nanoTime() - encStart)
        perfBytesEncrypted.addAndGet(payload.size.toLong())
        return YumeFrame(
            type = YumeFrameType.DATA,
            streamId = streamId,
            flags = INNER_ENCRYPTED_FLAG,
            payload = encrypted,
        )
    }

    private fun encodeOutgoingFrame(frame: YumeFrame): YumeFrame {
        val state = innerState ?: return frame
        if (!requiresInnerEncryption(frame.type)) {
            return frame
        }
        return frame.copy(
            flags = frame.flags or INNER_ENCRYPTED_FLAG,
            payload = YumeInnerCrypto.encrypt(state, frame.type, frame.streamId, frame.payload),
        )
    }

    private fun decodeIncomingFrame(frame: YumeFrame): YumeFrame {
        val state = innerState ?: return frame
        if (!requiresInnerEncryption(frame.type)) {
            return frame
        }
        if ((frame.flags and INNER_ENCRYPTED_FLAG) == 0) {
            throw YumeBootstrapException("The Yume server stopped sending required inner-encrypted ${frame.type.name} frames")
        }
        val decStart = System.nanoTime()
        val decrypted = YumeInnerCrypto.decrypt(state, frame.type, frame.streamId, frame.payload)
        perfDecryptNanos.addAndGet(System.nanoTime() - decStart)
        perfBytesDecrypted.addAndGet(decrypted.size.toLong())
        return frame.copy(
            payload = decrypted,
            flags = frame.flags and INNER_ENCRYPTED_FLAG.inv(),
        )
    }

    private fun ensureOpen() {
        synchronized(stateLock) {
            ensureOpenLocked()
        }
    }

    private fun ensureOpenLocked() {
        if (closeState != CloseState.Open) {
            val cause = closingCause
            throw if (cause is YumeBootstrapException) cause else YumeBootstrapException(cause?.message ?: "Yume session closed", cause)
        }
        val cause = closingCause
        if (cause != null) {
            throw if (cause is YumeBootstrapException) cause else YumeBootstrapException(cause.message ?: "Yume session closed", cause)
        }
        if (transport.isClosed) {
            throw YumeBootstrapException("The Yume TLS session is closed")
        }
    }

    private fun shutdown(cause: Throwable) {
        val streamsToFail: List<YumeInboundStream>
        val controlToFail: List<CompletableDeferred<JSONObject>>
        val opensToFail: List<CompletableDeferred<Unit>>
        val pongsToFail: List<CompletableDeferred<Unit>>
        synchronized(stateLock) {
            if (closeState != CloseState.Open) {
                if (closingCause == null) {
                    closingCause = cause
                }
                return
            }
            closeState = CloseState.Closing
            closingCause = cause
            streamsToFail = streams.values.toList()
            controlToFail = pendingControl.values.toList()
            opensToFail = pendingOpen.values.toList()
            pongsToFail = pendingPongs.toList()
            streams.clear()
            pendingControl.clear()
            pendingOpen.clear()
            pendingPongs.clear()
        }
        outboundFrames.close()
        controlFrames.close()
        closeScope.launch {
            writeMutex.withLock {
                runCatching { output.flush() }
                runCatching { transport.close() }
            }
            synchronized(stateLock) {
                closeState = CloseState.Closed
            }
            sessionScope.cancel()
        }
        controlToFail.forEach { waiter -> waiter.completeExceptionally(cause) }
        opensToFail.forEach { waiter -> waiter.completeExceptionally(cause) }
        pongsToFail.forEach { waiter -> waiter.completeExceptionally(cause) }
        streamsToFail.forEach { stream -> stream.fail(cause) }
    }

    companion object {
        private const val DEFAULT_PING_TIMEOUT_MS = 5_000
        private const val DEFAULT_STREAM_TIMEOUT_MS = 25_000
        private const val DEFAULT_CONTROL_TIMEOUT_MS = 5_000
        private const val MAX_PROBE_RESPONSE_BYTES = 32 * 1024
        private const val MAX_OUTBOUND_COALESCE_FRAMES = 16
        private const val MAX_OUTBOUND_COALESCE_BYTES = 64 * 1024
        private const val INNER_ENCRYPTED_FLAG = 0x8000
        // AES-GCM wrap overhead: 12-byte IV + 16-byte authentication tag.
        private const val AEAD_OVERHEAD = 12 + 16
        // Cap on how many idle ByteArrays each size-bucket of the payload
        // pool retains. Prevents unbounded growth when stream sizes vary.
        private const val MAX_POOL_DEPTH = 32
        private const val PERF_REPORT_INTERVAL_MS = 2_000L

        private fun parseHttpBody(response: String): String {
            val trimmed = response.trim()
            if (trimmed.isEmpty()) {
                throw YumeBootstrapException("The Yume probe endpoint returned an empty response")
            }
            val separator = "\r\n\r\n"
            val index = response.indexOf(separator)
            if (index < 0) {
                if (looksLikeIpLiteral(trimmed)) {
                    return trimmed
                }
                val preview = summarizeProbeResponse(trimmed)
                throw YumeBootstrapException(
                    if (preview == null) {
                        "The Yume probe endpoint returned an invalid HTTP response"
                    } else {
                        "The Yume probe endpoint returned an invalid HTTP response: $preview"
                    },
                )
            }
            val statusLine = response.lineSequence().firstOrNull().orEmpty()
            if (!statusLine.contains(" 200 ")) {
                throw YumeBootstrapException("The Yume probe endpoint returned $statusLine")
            }
            return response.substring(index + separator.length).trim()
        }

        private fun looksLikeIpLiteral(value: String): Boolean {
            if (value.isBlank() || value.length > 64) {
                return false
            }
            return value.all { char ->
                char.isDigit() ||
                    char == '.' ||
                    char == ':' ||
                    char in 'a'..'f' ||
                    char in 'A'..'F'
            }
        }

        private fun summarizeProbeResponse(value: String): String? {
            val summary = value
                .replace(Regex("\\s+"), " ")
                .take(96)
                .trim()
            return summary.ifEmpty { null }
        }

        private fun requiresInnerEncryption(type: YumeFrameType): Boolean {
            return when (type) {
                YumeFrameType.OPEN,
                YumeFrameType.DATA,
                YumeFrameType.CLOSE,
                YumeFrameType.CONTROL,
                YumeFrameType.RLISTEN,
                YumeFrameType.EXEC,
                -> true

                else -> false
            }
        }

    }

    private fun isExpectedCloseDuringShutdown(throwable: Throwable): Boolean {
        if (closeState == CloseState.Open) {
            return false
        }
        val message = throwable.message?.lowercase(Locale.US).orEmpty()
        return throwable is IOException ||
            throwable is EOFException ||
            message.contains("socket closed") ||
            message.contains("broken pipe") ||
            message.contains("connection reset") ||
            message.contains("eof") ||
            message.contains("closed") ||
            message.contains("cancelled")
    }

    private enum class CloseState {
        Open,
        Closing,
        Closed,
    }

    // PendingWrite no longer carries a pre-encoded ByteArray. We hold the
    // (already inner-encrypted, if applicable) YumeFrame and encode it
    // straight into the runWriteLoop scratch buffer at batch time. This
    // removes one ByteArray allocation per frame on the send hot path —
    // the dominant non-data allocation observed in the GC trace.
    private data class PendingWrite(
        val frame: YumeFrame,
        val completion: CompletableDeferred<Unit>?,
    ) {
        val encodedSize: Int get() = HEADER_SIZE + frame.payload.size

        companion object {
            const val HEADER_SIZE = 8
        }
    }

    private fun allocateStreamIdLocked(): Int {
        repeat(255) {
            val candidate = if (nextStreamId == 0) 1 else nextStreamId
            nextStreamId = (candidate + 1) and 0xFF
            if (candidate != 0 &&
                candidate !in streams &&
                candidate !in pendingOpen
            ) {
                return candidate
            }
        }
        throw YumeBootstrapException("No Yume stream ids are available")
    }
}

class YumeBootstrapException(message: String, cause: Throwable? = null) : Exception(message, cause)

private data class YumeBootstrapEnvelope(
    val json: JSONObject,
    val tlsProtocol: String,
    val tlsCipherSuite: String,
    val peerCertificate: X509Certificate,
)

object YumeBootstrapClient {
    private const val AUTH_TIMEOUT_MS = 8_000
    private const val SERVER_INFO_TIMEOUT_MS = 12_000
    private const val SERVER_INFO_TIMEOUT_MS_INNER = 20_000
    private const val SERVER_INFO_TIMEOUT_MS_INNER_HEAVY = 45_000
    private const val MAX_CONNECT_ATTEMPTS = 6
    private val EMPTY_BYTES = ByteArray(0)
    private const val ANON_PROOF_WINDOW_SECONDS = 600L
    private const val ANON_MESSAGE_PREFIX = "YUME-ANON-V1:"
    private const val PQ_BOOTSTRAP_PREFIX = "YUME-PQ-V1:"
    private const val INNER_PREFS_NAME = "yume_bootstrap_inner_profiles"
    private const val INNER_VAULT_NAME = "bootstrap_inner_profiles"
    private const val FIXCRAFT_ANONYM_PUBLIC_KEY_PEM =
        "-----BEGIN PUBLIC KEY-----\n" +
            "MCowBQYDK2VwAyEAtupzLhANnB0VxP51vB/7yYwR+/3/jv4Str9MGLGA+is=\n" +
            "-----END PUBLIC KEY-----\n"

    private val provider: Provider by lazy { BouncyCastleProvider() }
    private val conscryptProvider: Provider? by lazy {
        runCatching { Conscrypt.newProvider() }.getOrNull()
    }
    private val pemConverter: JcaPEMKeyConverter by lazy {
        JcaPEMKeyConverter().setProvider(provider)
    }
    private val cachedInnerProfiles = mutableMapOf<String, CachedInnerProfile>()
    private val cachedInnerPreferencesLock = Any()

    suspend fun connect(
        appContext: Context,
        profile: ServerProfile,
        anonymCaPem: String,
        authKeyPem: String,
        rawSocketFactory: suspend () -> Socket,
        requireAnonymProof: Boolean = true,
        preferHopRequested: Boolean = true,
        allowInnerProtection: Boolean = true,
        transportTuning: YumeTransportTuning = YumeTransportTuning(),
        log: ((String) -> Unit)? = null,
    ): YumeBootstrapSession {
        val applicationContext = appContext.applicationContext
        val authMaterial = loadAuthMaterial(authKeyPem)
        var cachedProfile = if (allowInnerProtection) loadCachedInnerPreference(applicationContext, profile) else null
        var preference = if (allowInnerProtection) {
            cachedProfile?.preference ?: YumeInnerPreference.disabled()
        } else {
            YumeInnerPreference.disabled()
        }
        var lastFailure: YumeBootstrapException? = null

        // One-shot probe per connect() — if the user toggled the native
        // transport on but libyumetransport.so isn't shipped for this
        // ABI, log it once and silently use the SSLSocket path.
        val nativeRequested = transportTuning.useNativeTransport
        val nativeAvailable = nativeRequested && YumeNativeTlsPipe.tryLoad()
        if (nativeRequested && !nativeAvailable) {
            log?.invoke(
                "Native transport requested but libyumetransport.so is unavailable" +
                    (YumeNativeTlsPipe.loadFailureReason?.let { " ($it)" } ?: "") +
                    "; falling back to SSLSocket.",
            )
        }

        connectLoop@ for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            var rawSocket: Socket? = null
            var transport: YumeTransportSocket? = null
            try {
                rawSocket = rawSocketFactory()
                runCatching {
                    rawSocket.tcpNoDelay = true
                    rawSocket.keepAlive = true
                    // Lift the bandwidth-delay-product cap. Android's
                    // default TCP receive window is ~64 KiB on many
                    // builds, which over 50 ms RTT puts a single
                    // tunnel TCP connection at ~10 Mbps regardless
                    // of CPU. The yume tunnel multiplexes every app
                    // stream through ONE TCP flow, so that BDP cap
                    // becomes the total throughput cap. 2 MiB is
                    // enough headroom for ~300 Mbps at 50 ms RTT.
                    rawSocket.receiveBufferSize = 2 * 1024 * 1024
                    rawSocket.sendBufferSize    = 2 * 1024 * 1024
                }
                if (attempt > 1) {
                    log?.invoke("Retry attempt $attempt/$MAX_CONNECT_ATTEMPTS with a fresh protected socket.")
                }
                transport = if (nativeAvailable) {
                    buildNativeTransport(rawSocket, profile, anonymCaPem, log)
                } else {
                    buildJavaTransport(rawSocket, profile, anonymCaPem, log)
                }
                log?.invoke(
                    "TLS handshake completed via ${transport.backendLabel}: " +
                        "${transport.tlsProtocol} ${transport.tlsCipherSuite}.",
                )
                if (profile.obfuscation) {
                    performH2CarrierHandshake(transport, profile)
                    log?.invoke("HTTP/2 carrier handshake established.")
                }

                val peerCertificate = transport.peerCertificates.firstOrNull()
                    ?: throw YumeBootstrapException("The server did not provide a TLS certificate")
                val peerFingerprintSha256 = sha256Hex(peerCertificate.encoded)
                cachedProfile?.peerFingerprintSha256
                    ?.takeIf { it.isNotBlank() }
                    ?.let { cachedFingerprint ->
                        if (!cachedFingerprint.equals(peerFingerprintSha256, ignoreCase = true)) {
                            log?.invoke("Discarding the cached inner protection profile because the Yume TLS fingerprint changed.")
                            clearCachedInnerPreference(applicationContext, profile)
                            cachedProfile = null
                            preference = YumeInnerPreference.disabled()
                        }
                    }
                val envelope = YumeBootstrapEnvelope(
                    json = JSONObject(),
                    tlsProtocol = transport.tlsProtocol,
                    tlsCipherSuite = transport.tlsCipherSuite,
                    peerCertificate = peerCertificate,
                )

                val challenge = YumeFrameCodec.readFrame(transport.inputStream)
                if (challenge.type != YumeFrameType.AUTH) {
                    throw YumeBootstrapException("This endpoint is not a Yume server (expected AUTH challenge)")
                }
                log?.invoke("Received Yume AUTH challenge.")

                val preparedInner = if (preference.enabled) {
                    withContext(Dispatchers.Default) {
                        YumeInnerCrypto.prepare(preference)
                    }
                } else {
                    null
                }
                val signature = withContext(Dispatchers.Default) {
                    sign(authMaterial.privateKey, challenge.payload)
                }
                val payload = buildAuthPayload(
                    publicKeyPem = authMaterial.publicKeyPem.toByteArray(Charsets.US_ASCII),
                    signature = signature,
                    preparedInner = preparedInner,
                )
                YumeFrameCodec.writeFrame(
                    transport.outputStream,
                    YumeFrame(type = YumeFrameType.AUTH, payload = payload),
                )
                log?.invoke(
                    if (preparedInner != null) {
                        "Auth response sent with ${preparedInner.mode} inner crypto (${preparedInner.kdfParams.name}); waiting for server confirmation."
                    } else {
                        "Auth response sent; waiting for server confirmation."
                    },
                )

                transport.soTimeout = serverInfoTimeout(preparedInner)
                val anonFrame = YumeFrameCodec.readFrame(transport.inputStream)
                if (anonFrame.type != YumeFrameType.ANON) {
                    throw YumeBootstrapException("This endpoint is not a Yume server (expected ANON response)")
                }
                val json = try {
                    JSONObject(String(anonFrame.payload, Charsets.UTF_8))
                } catch (throwable: Exception) {
                    throw YumeBootstrapException("The Yume server returned invalid JSON", throwable)
                }
                val response = envelope.copy(json = json)
                val pqPublicKey = verifyAndExtractPqPublicKey(json, anonymCaPem, peerCertificate, log)
                val serverError = json.optString("error").trim()
                if (serverError.isNotEmpty()) {
                    val nextPreference = if (allowInnerProtection) {
                        nextInnerPreference(
                            current = preference,
                            serverError = serverError,
                            trustedPqPublicKey = pqPublicKey,
                            preferHopRequested = preferHopRequested,
                        )
                    } else {
                        null
                    }
                    if (nextPreference != null && nextPreference != preference && attempt < MAX_CONNECT_ATTEMPTS) {
                        log?.invoke("Server requested a different protection profile ($serverError). Retrying with adjusted inner negotiation.")
                        preference = nextPreference
                        cacheInnerPreference(
                            appContext = applicationContext,
                            profile = profile,
                            preference = preference,
                            peerFingerprintSha256 = peerFingerprintSha256,
                            pqPublicKey = nextPreference.pqPublicKey,
                        )
                        runCatching { transport.close() }
                        continue@connectLoop
                    }
                    val effectiveError = if (
                        !preferHopRequested &&
                        serverError.lowercase(Locale.US).contains("requires hopping")
                    ) {
                        "$serverError (Android compatibility mode keeps hopping disabled for this session)"
                    } else {
                        serverError
                    }
                    throw YumeBootstrapException(effectiveError)
                }

                log?.invoke("Authenticated to the Yume server.")
                val serverInfo = parseServerInfo(
                    json = response.json,
                    anonymCaPem = anonymCaPem,
                    requireAnonymProof = requireAnonymProof,
                    peerCertificate = response.peerCertificate,
                    tlsProtocol = response.tlsProtocol,
                    tlsCipherSuite = response.tlsCipherSuite,
                )
                if (serverInfo.serverVersion != BuildConfig.YUME_PROTOCOL_VERSION) {
                    throw YumeBootstrapException(
                        "Server version ${serverInfo.serverVersion} does not match this app (${BuildConfig.YUME_PROTOCOL_VERSION})"
                    )
                }
                log?.invoke("Verity ${serverInfo.verity.summary}.")
                if (!requireAnonymProof && !serverInfo.serverMode.equals("anonym", ignoreCase = true)) {
                    log?.invoke(
                        "Privacy warning: this server is not in anonym mode. The server operator may monitor proxied traffic."
                    )
                }

                val nextPreference = if (allowInnerProtection) {
                    nextInnerPreferenceAfterSuccess(
                        current = preference,
                        serverInfo = serverInfo,
                        trustedPqPublicKey = pqPublicKey,
                        preferHopRequested = preferHopRequested,
                    )
                } else {
                    null
                }
                if (nextPreference != null && nextPreference != preference && attempt < MAX_CONNECT_ATTEMPTS) {
                    val reason = when {
                        !preference.enabled -> "Verified pq_pub from the server. Reconnecting to enable inner crypto."
                        pqPublicKey != null && !pqPublicKey.contentEquals(preference.pqPublicKey) ->
                            "Verified a newer pq_pub from the server. Reconnecting with the updated inner key."
                        else -> "Reconnecting with the negotiated inner protection profile."
                    }
                    log?.invoke(reason)
                    preference = nextPreference
                    cacheInnerPreference(
                        appContext = applicationContext,
                        profile = profile,
                        preference = preference,
                        peerFingerprintSha256 = peerFingerprintSha256,
                        pqPublicKey = pqPublicKey,
                    )
                    runCatching { transport.close() }
                    continue@connectLoop
                }

                val sessionInnerState = if (preparedInner != null && serverInfo.innerActive) {
                    YumeInnerSessionState(
                        baseKey = preparedInner.key,
                        hopEnabled = preparedInner.hopRequested && serverInfo.hopEnabled && serverInfo.hopIntervalMs > 0,
                        hopIntervalMs = serverInfo.hopIntervalMs,
                        // Anchor against monotonic elapsedRealtime() so a
                        // wall-clock NTP step on the phone (Android performs
                        // step, not slew) doesn't immediately blow the hop
                        // window mid-session.
                        hopOffsetMs = if (serverInfo.serverTimeMs > 0L) {
                            serverInfo.serverTimeMs - SystemClock.elapsedRealtime()
                        } else {
                            0L
                        },
                    )
                } else {
                    null
                }

                if (allowInnerProtection && preference.enabled && preference.pqPublicKey != null) {
                    cacheInnerPreference(
                        appContext = applicationContext,
                        profile = profile,
                        preference = preference,
                        peerFingerprintSha256 = peerFingerprintSha256,
                        pqPublicKey = pqPublicKey ?: preference.pqPublicKey,
                    )
                } else if (allowInnerProtection) {
                    clearCachedInnerPreference(applicationContext, profile)
                }
                return YumeBootstrapSession(
                    transport = transport,
                    bootstrap = serverInfo,
                    innerState = sessionInnerState,
                    transportTuning = transportTuning,
                )
            } catch (throwable: Exception) {
                runCatching { transport?.close() ?: rawSocket?.close() }
                lastFailure = if (throwable is YumeBootstrapException) {
                    throwable
                } else {
                    YumeBootstrapException(throwable.message ?: "Failed to complete Yume session setup", throwable)
                }
                if (attempt < MAX_CONNECT_ATTEMPTS && shouldRetryBootstrapFailure(lastFailure)) {
                    log?.invoke(
                        "Yume session setup attempt $attempt/$MAX_CONNECT_ATTEMPTS failed: ${lastFailure.message ?: "transient network error"}. Retrying with a fresh protected socket."
                    )
                    continue@connectLoop
                }
                throw lastFailure
            }
        }
        throw lastFailure ?: YumeBootstrapException("Failed to complete Yume session setup")
    }

    private fun innerPreferenceCacheKey(profile: ServerProfile): String {
        return "${profile.serverName.lowercase(Locale.US)}:${profile.port}"
    }

    private fun cloneInnerPreference(preference: YumeInnerPreference): YumeInnerPreference {
        return preference.copy(pqPublicKey = preference.pqPublicKey?.copyOf())
    }

    private fun cloneCachedInnerProfile(profile: CachedInnerProfile): CachedInnerProfile {
        return profile.copy(
            preference = cloneInnerPreference(profile.preference),
            pqPublicKeySha256 = profile.pqPublicKeySha256,
            peerFingerprintSha256 = profile.peerFingerprintSha256,
        )
    }

    private fun loadCachedInnerPreference(appContext: Context, profile: ServerProfile): CachedInnerProfile? {
        val key = innerPreferenceCacheKey(profile)
        return synchronized(cachedInnerPreferencesLock) {
            cachedInnerProfiles[key]?.let(::cloneCachedInnerProfile)
                ?: loadPersistedInnerPreference(appContext, key)?.also { cachedInnerProfiles[key] = it }?.let(::cloneCachedInnerProfile)
        }
    }

    private fun cacheInnerPreference(
        appContext: Context,
        profile: ServerProfile,
        preference: YumeInnerPreference,
        peerFingerprintSha256: String,
        pqPublicKey: ByteArray?,
    ) {
        val pqKey = pqPublicKey ?: preference.pqPublicKey
        if (!preference.enabled || pqKey == null || pqKey.isEmpty()) {
            clearCachedInnerPreference(appContext, profile)
            return
        }
        val record = CachedInnerProfile(
            preference = preference.copy(pqPublicKey = pqKey.copyOf()),
            peerFingerprintSha256 = peerFingerprintSha256,
            pqPublicKeySha256 = sha256Hex(pqKey),
        )
        val key = innerPreferenceCacheKey(profile)
        synchronized(cachedInnerPreferencesLock) {
            cachedInnerProfiles[key] = cloneCachedInnerProfile(record)
        }
        persistInnerPreference(appContext, key, record)
    }

    private fun clearCachedInnerPreference(appContext: Context, profile: ServerProfile) {
        val key = innerPreferenceCacheKey(profile)
        synchronized(cachedInnerPreferencesLock) {
            cachedInnerProfiles.remove(key)
        }
        runCatching {
            VaultJsonMapStore(appContext, INNER_VAULT_NAME).remove(key)
        }.getOrElse {
            appContext.getSharedPreferences(INNER_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(key)
                .apply()
        }
    }

    private fun loadPersistedInnerPreference(appContext: Context, key: String): CachedInnerProfile? {
        val raw = runCatching {
            VaultJsonMapStore(appContext, INNER_VAULT_NAME).getString(key)
        }.getOrNull() ?: appContext.getSharedPreferences(INNER_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null)
            ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val enabled = json.optBoolean("enabled", false)
            val heavy = json.optBoolean("heavy", true)
            val hopRequested = json.optBoolean("hop_requested", true)
            val kdf = when (json.optString("kdf").trim().uppercase(Locale.US)) {
                YumeInnerKdf.HKDF.name -> YumeInnerKdf.HKDF
                YumeInnerKdf.PBKDF2.name -> YumeInnerKdf.PBKDF2
                else -> YumeInnerKdf.ARGON2
            }
            val pqPublicKey = json.optString("pq_public_key_b64").trim()
                .takeIf { it.isNotEmpty() }
                ?.let { Base64.getDecoder().decode(it) }
            if (!enabled || pqPublicKey == null || pqPublicKey.isEmpty()) {
                return null
            }
            CachedInnerProfile(
                preference = YumeInnerPreference(
                    enabled = enabled,
                    pqPublicKey = pqPublicKey,
                    heavy = heavy,
                    hopRequested = hopRequested,
                    kdf = kdf,
                ),
                peerFingerprintSha256 = json.optString("peer_fingerprint_sha256").trim().ifEmpty { null },
                pqPublicKeySha256 = json.optString("pq_public_key_sha256").trim().ifEmpty { null },
            )
        }.getOrNull()
    }

    private fun persistInnerPreference(appContext: Context, key: String, record: CachedInnerProfile) {
        val json = JSONObject()
            .put("enabled", record.preference.enabled)
            .put("heavy", record.preference.heavy)
            .put("hop_requested", record.preference.hopRequested)
            .put("kdf", record.preference.kdf.name)
            .put("pq_public_key_b64", Base64.getEncoder().encodeToString(record.preference.pqPublicKey))
            .put("peer_fingerprint_sha256", record.peerFingerprintSha256)
            .put("pq_public_key_sha256", record.pqPublicKeySha256)
        runCatching {
            VaultJsonMapStore(appContext, INNER_VAULT_NAME).putString(key, json.toString())
            appContext.getSharedPreferences(INNER_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(key)
                .apply()
        }.getOrElse {
            appContext.getSharedPreferences(INNER_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(key, json.toString())
                .apply()
        }
    }

    private data class CachedInnerProfile(
        val preference: YumeInnerPreference,
        val peerFingerprintSha256: String?,
        val pqPublicKeySha256: String?,
    )

    private fun parseServerInfo(
        json: JSONObject,
        anonymCaPem: String,
        requireAnonymProof: Boolean,
        peerCertificate: X509Certificate,
        tlsProtocol: String,
        tlsCipherSuite: String,
    ): YumeServerBootstrap {
        val version = json.optString("version").trim()
        if (version.isEmpty()) {
            throw YumeBootstrapException("The Yume server did not report its version")
        }
        val serverError = json.optString("error").trim()
        if (serverError.isNotEmpty()) {
            throw YumeBootstrapException(serverError)
        }

        return YumeServerBootstrap(
            serverVersion = version,
            serverMode = json.optString("mode", "normal").trim().ifEmpty { "normal" },
            tlsProtocol = tlsProtocol,
            tlsCipherSuite = tlsCipherSuite,
            verity = verifyAnonymProof(json, anonymCaPem, requireAnonymProof, peerCertificate),
            innerSupported = json.optBoolean("inner_supported", false),
            innerRequired = json.optBoolean("inner_required", false),
            innerDual = json.optBoolean("inner_dual", false),
            innerActive = json.optBoolean("inner_active", false),
            innerMode = json.optString("inner_mode", "").trim(),
            innerKdf = json.optString("inner_kdf", "").trim().ifEmpty { null },
            capPq = json.optBoolean("cap_pq", false),
            capArgon2 = json.optBoolean("cap_argon2", false),
            capPbkdf2 = json.optBoolean("cap_pbkdf2", false),
            hopEnabled = json.optBoolean("hop_enabled", false),
            hopIntervalMs = json.optInt("hop_interval_ms", 0),
            serverTimeMs = json.optLong("server_time_ms", 0L),
        )
    }

    private fun verifyAnonymProof(
        json: JSONObject,
        anonymCaPem: String,
        requireAnonymProof: Boolean,
        peerCertificate: X509Certificate,
    ): YumeVerityInfo {
        val mode = json.optString("mode", "normal").trim().ifEmpty { "normal" }
        val peerFingerprint = sha256Hex(peerCertificate.encoded)
        if (mode != "anonym") {
            if (requireAnonymProof) {
                throw YumeBootstrapException("SERVER IS NOT IN ANONYM MODE")
            }
            return YumeVerityInfo(
                summary = "FAIL",
                source = "normal",
                peerFingerprintSha256 = peerFingerprint,
            )
        }

        val hash = json.optString("hash").trim()
        val ts = json.optString("ts").trim()
        val nonce = json.optString("nonce").trim()
        val certfp = json.optString("certfp").trim()
        val sig = json.optString("sig").trim()
        val caSig = json.optString("ca_sig").trim()
        val subSig = json.optString("sub_sig").trim()
        val subCertB64 = json.optString("sub_cert").trim()
        val proofSources = parseProofSources(json, sig, caSig, subSig, subCertB64)

        if (hash.isEmpty() || ts.isEmpty() || nonce.isEmpty()) {
            throw YumeBootstrapException("The anonym proof from the server is incomplete")
        }
        if (certfp.isEmpty()) {
            throw YumeBootstrapException("The anonym proof is missing the TLS certificate fingerprint")
        }
        if (peerFingerprint != certfp) {
            throw YumeBootstrapException("The anonym proof fingerprint does not match the server TLS certificate")
        }

        val proofTimestampSeconds = ts.toLongOrNull()
            ?: throw YumeBootstrapException("The anonym proof timestamp is invalid")
        val nowSeconds = System.currentTimeMillis() / 1_000L
        if (abs(nowSeconds - proofTimestampSeconds) > ANON_PROOF_WINDOW_SECONDS) {
            throw YumeBootstrapException("The anonym proof is expired or not yet valid")
        }

        val proofMessage = "$ANON_MESSAGE_PREFIX$hash:$ts:$nonce:$certfp".toByteArray(Charsets.UTF_8)
        val verifiedSources = linkedSetOf<String>()

        if ("fixcraft" in proofSources) {
            if (sig.isEmpty()) {
                throw YumeBootstrapException("The server announced FixCraft proof but did not send a signature")
            }
            val fixcraftKey = loadPublicKeyFromPem(FIXCRAFT_ANONYM_PUBLIC_KEY_PEM)
            if (!verifySignature(fixcraftKey, proofMessage, decodeBase64(sig, "FixCraft signature"), json.optString("algo"))) {
                throw YumeBootstrapException("The FixCraft anonym signature is invalid")
            }
            verifiedSources += "fixcraft"
        }

        if ("sub-ca" in proofSources) {
            if (subCertB64.isEmpty()) {
                throw YumeBootstrapException("The server announced a Sub-CA proof but did not send a sub certificate")
            }
            if (subSig.isEmpty()) {
                throw YumeBootstrapException("The server announced a Sub-CA proof but did not send a sub signature")
            }
            val subCert = loadCertificate(String(decodeBase64(subCertB64, "Sub-CA certificate"), Charsets.UTF_8))
            val caCert = loadCertificate(anonymCaPem)
            subCert.checkValidity(Date())
            subCert.verify(caCert.publicKey, provider)
            if (!verifySignature(subCert.publicKey, proofMessage, decodeBase64(subSig, "Sub-CA signature"), json.optString("sub_alg"))) {
                throw YumeBootstrapException("The Sub-CA anonym signature is invalid")
            }
            verifiedSources += "sub-ca"
        }

        if ("ca" in proofSources) {
            if (caSig.isEmpty()) {
                throw YumeBootstrapException("The server announced a CA proof but did not send a CA signature")
            }
            val caCert = loadCertificate(anonymCaPem)
            if (!verifySignature(caCert.publicKey, proofMessage, decodeBase64(caSig, "CA signature"), json.optString("ca_alg"))) {
                throw YumeBootstrapException("The CA anonym signature is invalid")
            }
            verifiedSources += "ca"
        }

        val primarySource = when {
            "sub-ca" in verifiedSources -> "sub-ca"
            "ca" in verifiedSources -> "ca"
            "fixcraft" in verifiedSources -> "fixcraft"
            else -> throw YumeBootstrapException("No trusted anonym proof source could be verified")
        }

        val summary = when (primarySource) {
            "sub-ca" -> "PASS [Sub-CA]"
            "ca" -> "PASS [CA]"
            "fixcraft" -> "PASS [FixCraft]"
            else -> "PASS"
        }
        return YumeVerityInfo(
            summary = summary,
            source = primarySource,
            peerFingerprintSha256 = peerFingerprint,
        )
    }

    private fun parseProofSources(
        json: JSONObject,
        fixcraftSig: String,
        caSig: String,
        subSig: String,
        subCertB64: String,
    ): Set<String> {
        val normalized = linkedSetOf<String>()
        val array = json.optJSONArray("proof_sources")
        if (array != null) {
            for (index in 0 until array.length()) {
                val value = array.optString(index).trim().lowercase(Locale.US)
                if (value.isNotEmpty()) {
                    normalized += value
                }
            }
        }
        if (normalized.isEmpty()) {
            if (fixcraftSig.isNotEmpty()) {
                normalized += "fixcraft"
            }
            if (caSig.isNotEmpty()) {
                normalized += "ca"
            }
            if (subSig.isNotEmpty() || subCertB64.isNotEmpty()) {
                normalized += "sub-ca"
            }
        }
        return normalized
    }

    private fun buildAuthPayload(
        publicKeyPem: ByteArray,
        signature: ByteArray,
        preparedInner: YumeInnerPrepared? = null,
    ): ByteArray {
        require(publicKeyPem.size <= 0xFFFF) { "Public key is too large for the Yume AUTH payload" }
        require(signature.size <= 0xFFFF) { "Signature is too large for the Yume AUTH payload" }
        preparedInner?.let { inner ->
            require(inner.pqCiphertext.size <= 0xFFFF) { "PQ ciphertext is too large for the Yume AUTH payload" }
            require(inner.salt.size <= 0xFFFF) { "PQ salt is too large for the Yume AUTH payload" }
            require(inner.mode.toByteArray(StandardCharsets.UTF_8).size <= 0xFFFF) { "Inner mode is too large for the Yume AUTH payload" }
            require(inner.kdfParams.name.toByteArray(StandardCharsets.UTF_8).size <= 0xFFFF) { "Inner KDF name is too large for the Yume AUTH payload" }
        }

        return ByteArrayOutputStream().use { output ->
            writeField(output, publicKeyPem)
            writeField(output, signature)
            preparedInner?.let { inner ->
                writeField(output, inner.pqCiphertext)
                writeField(output, inner.salt)
                writeField(output, inner.mode.toByteArray(StandardCharsets.UTF_8))
                writeField(output, byteArrayOf(if (inner.hopRequested) '1'.code.toByte() else '0'.code.toByte()))
                writeField(output, inner.kdfParams.name.toByteArray(StandardCharsets.UTF_8))
                writeField(output, encodeInnerKdfParams(inner.kdfParams))
            }
            output.toByteArray()
        }
    }

    private fun encodeInnerKdfParams(params: YumeInnerKdfParams): ByteArray {
        val bytes = ByteArray(16)
        writeU32(bytes, 0, params.argon2Time)
        writeU32(bytes, 4, params.argon2Memory)
        writeU32(bytes, 8, params.argon2Parallelism)
        writeU32(bytes, 12, params.pbkdf2Iterations)
        return bytes
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = ((value ushr 24) and 0xFF).toByte()
        target[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 3] = (value and 0xFF).toByte()
    }

    private fun serverInfoTimeout(preparedInner: YumeInnerPrepared?): Int {
        return when {
            preparedInner == null -> SERVER_INFO_TIMEOUT_MS
            preparedInner.mode == "heavy" -> SERVER_INFO_TIMEOUT_MS_INNER_HEAVY
            else -> SERVER_INFO_TIMEOUT_MS_INNER
        }
    }

    private fun nextInnerPreference(
        current: YumeInnerPreference,
        serverError: String,
        trustedPqPublicKey: ByteArray?,
        preferHopRequested: Boolean,
    ): YumeInnerPreference? {
        val message = serverError.lowercase(Locale.US)
        val updatedKey = trustedPqPublicKey ?: current.pqPublicKey
        if (message.contains("requires inner crypto") && updatedKey != null) {
            return YumeInnerPreference(
                enabled = true,
                pqPublicKey = updatedKey,
                heavy = true,
                hopRequested = preferHopRequested,
                kdf = YumeInnerKdf.ARGON2,
            )
        }
        if (!current.enabled || updatedKey == null) {
            return null
        }
        return when {
            message.contains("does not support argon2") && current.kdf != YumeInnerKdf.PBKDF2 -> {
                current.copy(pqPublicKey = updatedKey, kdf = YumeInnerKdf.PBKDF2)
            }

            message.contains("does not support requested inner mode") && current.heavy -> {
                current.copy(pqPublicKey = updatedKey, heavy = false, kdf = YumeInnerKdf.HKDF)
            }

            message.contains("does not support hopping") && current.hopRequested -> {
                current.copy(pqPublicKey = updatedKey, hopRequested = false)
            }

            message.contains("requires hopping") && !current.hopRequested && preferHopRequested -> {
                current.copy(pqPublicKey = updatedKey, hopRequested = true)
            }

            trustedPqPublicKey != null && !trustedPqPublicKey.contentEquals(current.pqPublicKey) -> {
                current.copy(pqPublicKey = trustedPqPublicKey)
            }

            else -> null
        }
    }

    private fun nextInnerPreferenceAfterSuccess(
        current: YumeInnerPreference,
        serverInfo: YumeServerBootstrap,
        trustedPqPublicKey: ByteArray?,
        preferHopRequested: Boolean,
    ): YumeInnerPreference? {
        if (!serverInfo.innerSupported || !serverInfo.capPq || trustedPqPublicKey == null) {
            return null
        }
        val wantsHeavy = when {
            serverInfo.innerDual -> true
            serverInfo.innerMode.equals("light", ignoreCase = true) -> false
            else -> true
        }
        val desiredKdf = when {
            !wantsHeavy -> YumeInnerKdf.HKDF
            serverInfo.capArgon2 -> YumeInnerKdf.ARGON2
            serverInfo.capPbkdf2 -> YumeInnerKdf.PBKDF2
            else -> return null
        }
        val desired = YumeInnerPreference(
            enabled = true,
            pqPublicKey = trustedPqPublicKey,
            heavy = wantsHeavy,
            hopRequested = preferHopRequested && serverInfo.hopEnabled,
            kdf = desiredKdf,
        )
        if (current.matches(desired) && (!serverInfo.innerActive || current.enabled)) {
            return null
        }
        return desired
    }

    private fun YumeInnerPreference.matches(other: YumeInnerPreference): Boolean {
        return enabled == other.enabled &&
            heavy == other.heavy &&
            hopRequested == other.hopRequested &&
            kdf == other.kdf &&
            ((pqPublicKey == null && other.pqPublicKey == null) ||
                (pqPublicKey != null && other.pqPublicKey != null && pqPublicKey.contentEquals(other.pqPublicKey)))
    }

    private fun verifyAndExtractPqPublicKey(
        json: JSONObject,
        anonymCaPem: String,
        peerCertificate: X509Certificate,
        log: ((String) -> Unit)? = null,
    ): ByteArray? {
        val pqPubB64 = json.optString("pq_pub").trim()
        val pqSigB64 = json.optString("pq_sig").trim()
        val certfp = json.optString("certfp").trim()
        if (pqPubB64.isEmpty() || pqSigB64.isEmpty() || certfp.isEmpty()) {
            return null
        }
        val peerFingerprint = sha256Hex(peerCertificate.encoded)
        if (peerFingerprint != certfp) {
            log?.invoke("Ignoring pq_pub because its certificate fingerprint does not match the TLS peer certificate.")
            return null
        }

        val verifierKey = try {
            val subCertB64 = json.optString("sub_cert").trim()
            if (subCertB64.isNotEmpty()) {
                val subCertPem = String(decodeBase64(subCertB64, "Sub-CA certificate"), Charsets.UTF_8)
                val subCert = loadCertificate(subCertPem)
                val caCert = loadCertificate(anonymCaPem)
                subCert.checkValidity(Date())
                subCert.verify(caCert.publicKey, provider)
                subCert.publicKey
            } else {
                loadCertificate(anonymCaPem).publicKey
            }
        } catch (throwable: Exception) {
            log?.invoke("Ignoring pq_pub because the CA/Sub-CA chain could not be verified: ${throwable.message}.")
            return null
        }

        val pqMessage = "$PQ_BOOTSTRAP_PREFIX$pqPubB64:$certfp".toByteArray(Charsets.UTF_8)
        val pqSignature = try {
            decodeBase64(pqSigB64, "PQ session signature")
        } catch (throwable: Exception) {
            log?.invoke("Ignoring pq_pub because the signature is not valid Base64.")
            return null
        }
        if (!verifySignature(verifierKey, pqMessage, pqSignature, json.optString("pq_alg"))) {
            log?.invoke("Ignoring pq_pub because its trust signature is invalid.")
            return null
        }

        return try {
            Base64.getDecoder().decode(pqPubB64)
        } catch (_: IllegalArgumentException) {
            log?.invoke("Ignoring pq_pub because the public key is not valid Base64.")
            null
        }
    }

    private fun writeField(output: ByteArrayOutputStream, value: ByteArray) {
        output.write((value.size ushr 8) and 0xFF)
        output.write(value.size and 0xFF)
        output.write(value)
    }

    private fun applyClientTlsParameters(
        socket: SSLSocket,
        serverName: String,
        obfuscation: Boolean,
    ) {
        val parameters = socket.sslParameters
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        parameters.serverNames = listOf(SNIHostName(serverName))
        if (obfuscation && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            parameters.applicationProtocols = arrayOf("http/1.1")
        }
        socket.sslParameters = parameters
        if (obfuscation) {
            runCatching {
                if (Conscrypt.isConscrypt(socket)) {
                    Conscrypt.setApplicationProtocols(socket, arrayOf("http/1.1"))
                }
            }
        }
    }

    /**
     * Sends the HTTP/2 carrier client handshake (preface + SETTINGS +
     * WINDOW_UPDATE + HEADERS) to the cleartext side of {@code transport} and
     * consumes the server's canned reply (SETTINGS, SETTINGS-ACK, HEADERS
     * :status=200) before returning. Reads are sized exactly so no bytes past
     * the HEADERS frame are consumed; the next reader on the socket gets the
     * AUTH challenge cleanly.
     *
     * Replaces the legacy CONNECT-style preface; matches the desktop client's
     * perform_h2_carrier_handshake() byte-for-byte.
     */
    private fun performH2CarrierHandshake(
        transport: YumeTransportSocket,
        profile: ServerProfile,
    ) {
        val authorityHost = profile.serverName.ifBlank { profile.host }.trim()
        val authority = "$authorityHost:${profile.port}"
        val signal = YumeObfsH2.deriveSignalKey(profile.obfsSecret)
        val hour = System.currentTimeMillis() / 1000L / 3600L
        val token = YumeObfsH2.derivePathToken(signal, authorityHost, hour)
        val nonce = YumeObfsH2.randomNonceHex()
        val path = YumeObfsH2.buildPath(token, nonce)
        // Honour the per-profile TLS-stealth selection. The actual
        // TLS handshake (cipher list / extension order) is still
        // stock Android JSSE — full JA3 match would need Conscrypt-
        // specific tuning that isn't exposed at this layer — but
        // the carrier UA we send in the cleartext HEADERS frame
        // matches whatever browser the operator picked.
        val userAgent = when (profile.tlsStealthProfile.lowercase()) {
            "firefox" -> "Mozilla/5.0 (Android 14; Mobile; rv:133.0) " +
                "Gecko/133.0 Firefox/133.0"
            "safari"  -> "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) " +
                "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 " +
                "Mobile/15E148 Safari/604.1"
            "edge"    -> "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36 " +
                "EdgA/131.0.0.0"
            else      -> "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
        }

        val handshake = YumeObfsH2.encodeClientHandshake(authorityHost, path, userAgent)
        try {
            transport.outputStream.write(handshake)
            transport.outputStream.flush()
        } catch (throwable: SocketTimeoutException) {
            throw YumeBootstrapException(
                "This endpoint is not a Yume obfs endpoint ($authority; h2 preface timed out)",
                throwable,
            )
        } catch (throwable: IOException) {
            throw YumeBootstrapException(
                "This endpoint is not a Yume obfs endpoint ($authority; h2 preface failed: ${throwable.message ?: "write failed"})",
                throwable,
            )
        }

        // Read frame-by-frame, sizing each read to exactly the frame length so
        // we never accidentally consume the AUTH challenge that the server
        // sends right after the HEADERS frame.
        val input = transport.inputStream
        val output = transport.outputStream
        var sawHeaders = false
        while (!sawHeaders) {
            val frameHeader = readExact(input, 9, authority, "h2 frame header")
            val length = ((frameHeader[0].toInt() and 0xFF) shl 16) or
                ((frameHeader[1].toInt() and 0xFF) shl 8) or
                (frameHeader[2].toInt() and 0xFF)
            val type = frameHeader[3].toInt() and 0xFF
            val flags = frameHeader[4].toInt() and 0xFF
            if (length > 16384 + 256) {
                throw YumeBootstrapException(
                    "This endpoint is not a Yume obfs endpoint ($authority; h2 frame too large: $length bytes)",
                )
            }
            val body = if (length > 0) {
                readExact(input, length, authority, "h2 frame body (type=$type)")
            } else {
                EMPTY_BYTES
            }
            when (type) {
                0x01 -> sawHeaders = true   // HEADERS — done
                0x07 -> throw YumeBootstrapException(
                    "This endpoint is not a Yume obfs endpoint ($authority; server sent GOAWAY during obfs handshake)",
                )
                else -> Unit  // ignore SETTINGS, SETTINGS-ACK, PING, WINDOW_UPDATE, RST_STREAM, PRIORITY
            }
        }
    }

    private fun readExact(
        input: InputStream,
        n: Int,
        authority: String,
        label: String,
    ): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = try {
                input.read(out, read, n - read)
            } catch (throwable: SocketTimeoutException) {
                throw YumeBootstrapException(
                    "This endpoint is not a Yume obfs endpoint ($authority; $label timed out)",
                    throwable,
                )
            } catch (throwable: IOException) {
                throw YumeBootstrapException(
                    "This endpoint is not a Yume obfs endpoint ($authority; $label failed: ${throwable.message ?: "read failed"})",
                    throwable,
                )
            }
            if (r < 0) {
                throw YumeBootstrapException(
                    "This endpoint is not a Yume obfs endpoint ($authority; $label ended after $read of $n bytes)",
                )
            }
            read += r
        }
        return out
    }

    private fun loadAuthMaterial(pem: String): LoadedAuthMaterial {
        val pemObject = readPemObject(pem)
        val privateInfo: PrivateKeyInfo
        val publicInfo: SubjectPublicKeyInfo
        when (pemObject) {
            is PEMKeyPair -> {
                privateInfo = pemObject.privateKeyInfo
                publicInfo = pemObject.publicKeyInfo
            }

            is PrivateKeyInfo -> {
                privateInfo = pemObject
                publicInfo = derivePublicKeyInfo(privateInfo)
            }

            else -> {
                throw YumeBootstrapException("Unsupported auth key format. Import an unencrypted PEM private key.")
            }
        }

        val privateKey = pemConverter.getPrivateKey(privateInfo)
            ?: throw YumeBootstrapException("Failed to load the auth private key")
        val publicKeyPem = encodePemFromDer("PUBLIC KEY", publicInfo.encoded)
        return LoadedAuthMaterial(
            privateKey = privateKey,
            publicKeyPem = publicKeyPem,
        )
    }

    private fun derivePublicKeyInfo(privateInfo: PrivateKeyInfo): SubjectPublicKeyInfo {
        if (privateInfo.privateKeyAlgorithm.algorithm != EdECObjectIdentifiers.id_Ed25519) {
            throw YumeBootstrapException(
                "Android currently needs an Ed25519 auth key in PKCS#8 PEM format when only the private key is provided."
            )
        }
        val priv = PrivateKeyFactory.createKey(privateInfo) as Ed25519PrivateKeyParameters
        return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(priv.generatePublicKey())
    }

    private fun encodePemFromDer(label: String, der: ByteArray): String {
        val b64 = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP)
        val sb = StringBuilder()
        sb.append("-----BEGIN ").append(label).append("-----\n")
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 64, b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append("-----END ").append(label).append("-----\n")
        return sb.toString()
    }

    private fun loadPublicKeyFromPem(pem: String): PublicKey {
        val pemObject = readPemObject(pem)
        val publicInfo = when (pemObject) {
            is SubjectPublicKeyInfo -> pemObject
            is PEMKeyPair -> pemObject.publicKeyInfo
            else -> throw YumeBootstrapException("Unsupported public key PEM format")
        }
        return pemConverter.getPublicKey(publicInfo)
            ?: throw YumeBootstrapException("Failed to load the public key")
    }

    private fun readPemObject(pem: String): Any {
        PEMParser(StringReader(pem)).use { parser ->
            return parser.readObject() ?: throw YumeBootstrapException("The PEM payload is empty")
        }
    }

    private fun sign(privateKey: PrivateKey, message: ByteArray): ByteArray {
        return try {
            val signature = Signature.getInstance(signatureAlgorithmFor(privateKey.algorithm), provider)
            signature.initSign(privateKey)
            signature.update(message)
            signature.sign()
        } catch (throwable: Exception) {
            throw YumeBootstrapException("Failed to sign the Yume AUTH challenge", throwable)
        }
    }

    private fun verifySignature(
        publicKey: PublicKey,
        message: ByteArray,
        signatureBytes: ByteArray,
        algorithmHint: String? = null,
    ): Boolean {
        val attempts = signatureAlgorithmsForVerify(publicKey, algorithmHint)
        var lastFailure: Throwable? = null
        for (attempt in attempts) {
            try {
                val signature = Signature.getInstance(attempt.algorithm, provider)
                signature.initVerify(publicKey)
                attempt.pssSpec?.let(signature::setParameter)
                signature.update(message)
                if (signature.verify(signatureBytes)) {
                    return true
                }
            } catch (throwable: Exception) {
                lastFailure = throwable
            }
        }
        if (lastFailure == null) {
            return false
        }
        val hintLabel = algorithmHint?.takeIf { it.isNotBlank() } ?: "none"
        throw YumeBootstrapException(
            "Failed to verify a Yume signature for key=${publicKey.algorithm} hint=$hintLabel",
            lastFailure,
        )
    }

    private fun signatureAlgorithmFor(keyAlgorithm: String): String {
        return when (keyAlgorithm.uppercase(Locale.US)) {
            "ED25519" -> "Ed25519"
            "ED448" -> "Ed448"
            "RSA" -> "SHA256withRSA"
            "EC", "ECDSA" -> "SHA256withECDSA"
            else -> throw YumeBootstrapException("Unsupported key algorithm: $keyAlgorithm")
        }
    }

    private fun signatureAlgorithmsForVerify(
        publicKey: PublicKey,
        algorithmHint: String?,
    ): List<SignatureAttempt> {
        val attempts = LinkedHashSet<SignatureAttempt>()
        fun addEd25519() {
            attempts += SignatureAttempt("Ed25519")
        }
        fun addEd448() {
            attempts += SignatureAttempt("Ed448")
        }
        fun addRsaPkcs1() {
            attempts += SignatureAttempt("SHA256withRSA")
        }
        fun addRsaPss() {
            attempts += SignatureAttempt(
                algorithm = "RSASSA-PSS",
                pssSpec = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1),
            )
        }
        fun addEcdsa() {
            attempts += SignatureAttempt("SHA256withECDSA")
        }

        when (algorithmHint.orEmpty().trim().lowercase(Locale.US)) {
            "ed25519" -> addEd25519()
            "ed448" -> addEd448()
            "rsa" -> {
                addRsaPkcs1()
                addRsaPss()
            }
            "rsa-pss", "rsassa-pss", "pss" -> {
                addRsaPss()
                addRsaPkcs1()
            }
            "ecdsa", "ec" -> addEcdsa()
        }

        when (publicKey.algorithm.trim().uppercase(Locale.US)) {
            "ED25519" -> addEd25519()
            "ED448" -> addEd448()
            "RSA" -> {
                addRsaPkcs1()
                addRsaPss()
            }
            "RSASSA-PSS" -> {
                addRsaPss()
                addRsaPkcs1()
            }
            "EC", "ECDSA" -> addEcdsa()
        }

        if (attempts.isEmpty()) {
            addEd25519()
            addEd448()
            addRsaPkcs1()
            addRsaPss()
            addEcdsa()
        }
        return attempts.toList()
    }

    private data class SignatureAttempt(
        val algorithm: String,
        val pssSpec: PSSParameterSpec? = null,
    )

    private fun loadCertificate(pem: String): X509Certificate {
        val certificate = loadCertificates(pem).firstOrNull()
            ?: throw YumeBootstrapException("Failed to load the X.509 certificate")
        return certificate
    }

    private fun loadCertificates(pem: String): List<X509Certificate> {
        return CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8)))
            .filterIsInstance<X509Certificate>()
    }

    private fun decodeBase64(value: String, label: String): ByteArray {
        return try {
            Base64.getDecoder().decode(value)
        } catch (throwable: IllegalArgumentException) {
            throw YumeBootstrapException("The $label from the server is not valid Base64", throwable)
        }
    }

    private fun sha256Hex(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString(separator = "") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun buildJavaTransport(
        rawSocket: Socket,
        profile: ServerProfile,
        anonymCaPem: String,
        log: ((String) -> Unit)?,
    ): YumeTransportSocket {
        val tlsContext = buildTlsContext(anonymCaPem)
        log?.invoke("Using TLS provider: ${tlsContext.providerLabel}.")
        val sslSocket = (tlsContext.context.socketFactory.createSocket(
            rawSocket,
            profile.serverName,
            profile.port,
            true,
        ) as SSLSocket).apply {
            useClientMode = true
            runCatching {
                tcpNoDelay = true
                keepAlive = true
                receiveBufferSize = 2 * 1024 * 1024
                sendBufferSize    = 2 * 1024 * 1024
            }
            val supportedProtocols = supportedProtocols.toSet()
            if ("TLSv1.3" !in supportedProtocols) {
                throw YumeBootstrapException(
                    "This Android TLS stack does not support TLS 1.3, which Yume requires on the control path",
                )
            }
            applyClientTlsParameters(this, profile.serverName, profile.obfuscation)
            soTimeout = AUTH_TIMEOUT_MS
            try {
                startHandshake()
            } catch (throwable: Exception) {
                val detail = throwable.message?.trim().orEmpty()
                if (throwable is SSLHandshakeException || throwable is SSLProtocolException) {
                    throw YumeBootstrapException(
                        if (detail.isNotEmpty()) {
                            "TLS handshake failed: $detail"
                        } else {
                            "TLS handshake failed on this Android TLS provider"
                        },
                        throwable,
                    )
                }
                throw throwable
            }
        }
        return JavaTlsTransportSocket(sslSocket)
    }

    private fun buildNativeTransport(
        rawSocket: Socket,
        profile: ServerProfile,
        anonymCaPem: String,
        log: ((String) -> Unit)?,
    ): YumeTransportSocket {
        log?.invoke("Using libyumetransport.so (OpenSSL ${YumeNativeTlsPipe.openSslVersion()}).")
        val alpn = if (profile.obfuscation) "http/1.1" else ""
        // Combine the system trust store + the anonym CA into a single
        // PEM bundle. We can't rely on OpenSSL's
        // SSL_CTX_load_verify_locations("/system/etc/security/cacerts")
        // on modern Android — the path either isn't readable by app
        // processes or is in a different layout — so we extract every
        // accepted root from Java's TrustManagerFactory and base64-PEM
        // encode them client-side. Matches the CompositeX509TrustManager
        // (system + anonym) that the SSLSocket path uses.
        val combinedTrustPem = buildCombinedTrustPem(anonymCaPem)
        val handle = YumeNativeTlsPipe.create(
            sniHost = profile.serverName,
            trustPemConcat = combinedTrustPem,
            useSystemTrust = false,
            alpn = alpn,
        )
        try {
            // ParcelFileDescriptor.fromSocket DOES NOT dup; detaching
            // sets the underlying FileDescriptor's int to -1 inside the
            // Socket so rawSocket.close() afterwards is a safe no-op.
            // This is the Android-blessed pattern for handing a Java
            // Socket's fd to native code without double-close hazards.
            //
            // Once detachFd returns, native takes ownership of the fd
            // unconditionally — nativeStart closes it itself on
            // error (see TlsPipe::start). All we have to clean up on
            // failure is the handle.
            val pfd = ParcelFileDescriptor.fromSocket(rawSocket)
            val fd = pfd.detachFd()
            YumeNativeTlsPipe.start(handle, fd, AUTH_TIMEOUT_MS)
            return NativeTlsTransportSocket(handle)
        } catch (throwable: Throwable) {
            runCatching { YumeNativeTlsPipe.close(handle) }
            runCatching { YumeNativeTlsPipe.destroy(handle) }
            throw throwable
        }
    }

    private fun buildCombinedTrustPem(anonymCaPem: String): String {
        // Walks every accepted root from Java's TrustManagerFactory and
        // PEM-encodes it, then appends the user-supplied anonym CA PEM.
        // This is the EXACT same trust set that the Java SSLSocket path
        // sees via CompositeX509TrustManager(systemTrust, anonymTrust).
        //
        // Why: the native OpenSSL can't read /system/etc/security/cacerts
        // on modern Android (path layout differs, file permissions
        // exclude app processes), so we hand it the trust store
        // pre-extracted from the JCA — which works regardless of
        // Android version.
        val sb = StringBuilder()
        val systemTrust = runCatching { defaultX509TrustManager() }.getOrNull()
        if (systemTrust != null) {
            for (issuer in systemTrust.acceptedIssuers) {
                appendCertAsPem(sb, issuer)
            }
        }
        val trimmedAnonym = anonymCaPem.trim()
        if (trimmedAnonym.isNotEmpty()) {
            sb.append(trimmedAnonym)
            if (!trimmedAnonym.endsWith("\n")) sb.append('\n')
        }
        return sb.toString()
    }

    private fun appendCertAsPem(out: StringBuilder, cert: X509Certificate) {
        out.append("-----BEGIN CERTIFICATE-----\n")
        val encoded = Base64.getEncoder().encodeToString(cert.encoded)
        var i = 0
        while (i < encoded.length) {
            val end = (i + 64).coerceAtMost(encoded.length)
            out.append(encoded, i, end)
            out.append('\n')
            i = end
        }
        out.append("-----END CERTIFICATE-----\n")
    }

    private fun buildTlsContext(anonymCaPem: String): TlsContext {
        val provider = selectTlsProvider()
        val context = if (provider != null) {
            SSLContext.getInstance("TLS", provider)
        } else {
            SSLContext.getInstance("TLS")
        }
        context.init(null, buildTlsTrustManagers(anonymCaPem), SecureRandom())
        val providerLabel = provider?.name ?: "Android"
        return TlsContext(context, providerLabel)
    }

    private fun buildTlsTrustManagers(anonymCaPem: String): Array<TrustManager> {
        val systemTrust = defaultX509TrustManager()
        val anonymTrust = pemX509TrustManager(anonymCaPem)
        return arrayOf(CompositeX509TrustManager(listOf(systemTrust, anonymTrust)))
    }

    private fun defaultX509TrustManager(): X509TrustManager {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        return trustManagerFactory.x509TrustManager()
    }

    private fun pemX509TrustManager(pem: String): X509TrustManager {
        val certificates = try {
            loadCertificates(pem)
        } catch (throwable: Exception) {
            throw YumeBootstrapException("Failed to load the selected Anonym CA as a TLS trust anchor", throwable)
        }
        if (certificates.isEmpty()) {
            throw YumeBootstrapException("The selected Anonym CA does not contain an X.509 certificate")
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            certificates.forEachIndexed { index, certificate ->
                setCertificateEntry("yume-anonym-ca-$index", certificate)
            }
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        return trustManagerFactory.x509TrustManager()
    }

    private fun TrustManagerFactory.x509TrustManager(): X509TrustManager {
        return trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: throw YumeBootstrapException("Android did not provide an X.509 TLS trust manager")
    }

    private fun selectTlsProvider(): Provider? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            null
        } else {
            conscryptProvider
        }
    }

    private fun shouldRetryBootstrapFailure(failure: YumeBootstrapException): Boolean {
        val messages = throwableMessages(failure)
        if (messages.contains("tls handshake failed") ||
            messages.contains("obfs endpoint") ||
            messages.contains("not a yume") ||
            messages.contains("authentication rejected") ||
            messages.contains("access denied") ||
            messages.contains("server version") ||
            (messages.contains("anonym") && messages.contains("invalid"))
        ) {
            return false
        }
        val chain = throwableChain(failure)
        if (chain.any { it is SSLHandshakeException || it is SSLProtocolException }) {
            return false
        }
        return chain.any { it is IOException || it is SocketTimeoutException }
    }

    private fun throwableMessages(throwable: Throwable): String {
        return throwableChain(throwable)
            .mapNotNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(" ")
            .lowercase(Locale.US)
    }

    private fun throwableChain(throwable: Throwable): List<Throwable> {
        val chain = mutableListOf<Throwable>()
        var current: Throwable? = throwable
        while (current != null) {
            chain += current
            current = current.cause
        }
        return chain
    }

    private class CompositeX509TrustManager(
        private val delegates: List<X509TrustManager>,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            checkTrusted { delegate -> delegate.checkClientTrusted(chain, authType) }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            checkTrusted { delegate -> delegate.checkServerTrusted(chain, authType) }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> {
            return delegates.flatMap { it.acceptedIssuers.toList() }.toTypedArray()
        }

        private fun checkTrusted(block: (X509TrustManager) -> Unit) {
            var lastFailure: CertificateException? = null
            for (delegate in delegates) {
                try {
                    block(delegate)
                    return
                } catch (throwable: CertificateException) {
                    lastFailure = throwable
                }
            }
            throw lastFailure ?: CertificateException("No TLS trust manager accepted the certificate chain")
        }
    }

    private data class LoadedAuthMaterial(
        val privateKey: PrivateKey,
        val publicKeyPem: String,
    )

    private data class TlsContext(
        val context: SSLContext,
        val providerLabel: String,
    )
}
