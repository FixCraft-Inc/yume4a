package com.fixcraft.yume.transport

import android.content.Context
import android.os.SystemClock
import com.fixcraft.yume.BuildConfig
import com.fixcraft.yume.data.ServerProfile
import com.fixcraft.yume.data.VaultJsonMapStore
import org.conscrypt.Conscrypt
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
import java.io.StringReader
import java.io.StringWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Provider
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
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
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.math.abs

data class YumeTransportTuning(
    val tcpStreamMaxBufferedBytes: Int = 256 * 1024,
    val udpStreamMaxBufferedBytes: Int = 128 * 1024,
    val outboundFrameQueueCapacity: Int = 64,
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

data class YumePresenceInfo(
    val endpointId: String,
    val displayName: String,
    val serverId: String,
    val serverName: String,
)

data class YumeLifecycleAck(
    val acceptedState: String,
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
        session.sendData(streamId, data, offset, length)
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
        session.sendData(streamId, data)
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
    private val sslSocket: SSLSocket,
    val bootstrap: YumeServerBootstrap,
    private val innerState: YumeInnerSessionState? = null,
    private val transportTuning: YumeTransportTuning = YumeTransportTuning(),
) {
    private val input = sslSocket.inputStream
    private val output = sslSocket.outputStream
    private val writeMutex = Mutex()
    private val stateLock = Any()
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val closeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val outboundFrames = Channel<PendingWrite>(transportTuning.outboundFrameQueueCapacity)
    private val pendingOpen = mutableMapOf<Int, CompletableDeferred<Unit>>()
    private val pendingPongs = ArrayDeque<CompletableDeferred<Unit>>()
    private val pendingControl = mutableMapOf<String, CompletableDeferred<JSONObject>>()
    private val streams = mutableMapOf<Int, YumeInboundStream>()
    private var nextStreamId = 1
    @Volatile
    private var closingCause: Throwable? = null
    @Volatile
    private var closeState = CloseState.Open

    init {
        sslSocket.soTimeout = 0
        sessionScope.launch {
            runWriteLoop()
        }
        sessionScope.launch {
            runReadLoop()
        }
    }

    val isAlive: Boolean
        get() = closeState == CloseState.Open && sslSocket.isConnected && !sslSocket.isClosed && closingCause == null

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
            sendFrame(
                YumeFrame(
                    type = YumeFrameType.OPEN,
                    streamId = stream.streamId,
                    payload = openPayload,
                ),
            )
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
        sendEncodedFrame(encodeOutgoingDataFrame(streamId, data, offset, length))
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
                val frame = decodeIncomingFrame(YumeFrameCodec.readFrame(input))
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
        try {
            for (pending in outboundFrames) {
                ensureOpen()
                writeMutex.withLock {
                    ensureOpen()
                    output.write(pending.encodedFrame)
                }
                pending.completion.complete(Unit)
            }
        } catch (throwable: Exception) {
            if (closeState != CloseState.Open || isExpectedCloseDuringShutdown(throwable)) {
                synchronized(stateLock) {
                    closeState = CloseState.Closed
                }
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
                },
            )
        } finally {
            val failure = synchronized(stateLock) {
                closingCause ?: YumeBootstrapException("The authenticated Yume session was closed")
            }
            while (true) {
                val pending = outboundFrames.tryReceive().getOrNull() ?: break
                pending.completion.completeExceptionally(failure)
            }
        }
    }

    private suspend fun sendFrame(frame: YumeFrame) {
        sendEncodedFrame(YumeFrameCodec.encodeFrame(encodeOutgoingFrame(frame)))
    }

    private suspend fun sendEncodedFrame(encodedFrame: ByteArray) {
        ensureOpen()
        val pending = PendingWrite(
            encodedFrame = encodedFrame,
            completion = CompletableDeferred(),
        )
        try {
            outboundFrames.send(pending)
            pending.completion.await()
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

    private fun encodeOutgoingDataFrame(
        streamId: Int,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): ByteArray {
        require(offset in 0..data.size) { "offset out of range" }
        require(length >= 0 && offset + length <= data.size) { "length out of range" }
        if (length == 0) {
            return YumeFrameCodec.encodeFrame(type = YumeFrameType.DATA, streamId = streamId)
        }
        val state = innerState
        if (state == null || !requiresInnerEncryption(YumeFrameType.DATA)) {
            return YumeFrameCodec.encodeFrame(
                type = YumeFrameType.DATA,
                streamId = streamId,
                payload = data,
                payloadOffset = offset,
                payloadLength = length,
            )
        }
        val payload = if (offset == 0 && length == data.size) {
            data
        } else {
            data.copyOfRange(offset, offset + length)
        }
        return YumeFrameCodec.encodeFrame(
            type = YumeFrameType.DATA,
            streamId = streamId,
            flags = INNER_ENCRYPTED_FLAG,
            payload = YumeInnerCrypto.encrypt(state, YumeFrameType.DATA, streamId, payload),
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
        return frame.copy(
            payload = YumeInnerCrypto.decrypt(state, frame.type, frame.streamId, frame.payload),
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
        if (sslSocket.isClosed) {
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
        closeScope.launch {
            writeMutex.withLock {
                runCatching { output.flush() }
                runCatching { sslSocket.close() }
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
        private const val INNER_ENCRYPTED_FLAG = 0x8000

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

    private data class PendingWrite(
        val encodedFrame: ByteArray,
        val completion: CompletableDeferred<Unit>,
    )

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
    private val tlsProvider: Provider? by lazy {
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

        connectLoop@ for (attempt in 1..MAX_CONNECT_ATTEMPTS) {
            var rawSocket: Socket? = null
            var sslSocket: SSLSocket? = null
            try {
                rawSocket = rawSocketFactory()
                if (attempt > 1) {
                    log?.invoke("Retry attempt $attempt/$MAX_CONNECT_ATTEMPTS with a fresh protected socket.")
                }
                val tlsContext = buildSystemTlsContext()
                log?.invoke("Using TLS provider: ${tlsContext.providerLabel}.")
                sslSocket = (tlsContext.context.socketFactory.createSocket(
                    rawSocket,
                    profile.serverName,
                    profile.port,
                    true,
                ) as SSLSocket).apply {
                    useClientMode = true
                    val supportedProtocols = supportedProtocols.toSet()
                    if ("TLSv1.3" !in supportedProtocols) {
                        throw YumeBootstrapException(
                            "This Android TLS stack does not support TLS 1.3, which Yume requires on :443",
                        )
                    }
                    sslParameters = SSLParameters().also { parameters ->
                        parameters.endpointIdentificationAlgorithm = "HTTPS"
                        parameters.serverNames = listOf(SNIHostName(profile.serverName))
                    }
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
                log?.invoke("TLS handshake completed: ${sslSocket.session.protocol} ${sslSocket.session.cipherSuite}.")

                val peerCertificate = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
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
                    tlsProtocol = sslSocket.session.protocol ?: "TLS",
                    tlsCipherSuite = sslSocket.session.cipherSuite ?: "Unknown",
                    peerCertificate = peerCertificate,
                )

                val challenge = YumeFrameCodec.readFrame(sslSocket.inputStream)
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
                    sslSocket.outputStream,
                    YumeFrame(type = YumeFrameType.AUTH, payload = payload),
                )
                log?.invoke(
                    if (preparedInner != null) {
                        "Auth response sent with ${preparedInner.mode} inner crypto (${preparedInner.kdfParams.name}); waiting for server confirmation."
                    } else {
                        "Auth response sent; waiting for server confirmation."
                    },
                )

                sslSocket.soTimeout = serverInfoTimeout(preparedInner)
                val anonFrame = YumeFrameCodec.readFrame(sslSocket.inputStream)
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
                        runCatching { sslSocket.close() }
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
                    runCatching { sslSocket.close() }
                    continue@connectLoop
                }

                val sessionInnerState = if (preparedInner != null && serverInfo.innerActive) {
                    YumeInnerSessionState(
                        baseKey = preparedInner.key,
                        hopEnabled = preparedInner.hopRequested && serverInfo.hopEnabled && serverInfo.hopIntervalMs > 0,
                        hopIntervalMs = serverInfo.hopIntervalMs,
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
                    sslSocket = sslSocket,
                    bootstrap = serverInfo,
                    innerState = sessionInnerState,
                    transportTuning = transportTuning,
                )
            } catch (throwable: Exception) {
                runCatching { sslSocket?.close() ?: rawSocket?.close() }
                lastFailure = if (throwable is YumeBootstrapException) {
                    throwable
                } else {
                    YumeBootstrapException(throwable.message ?: "Failed to complete Yume session setup", throwable)
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
        val publicKey = pemConverter.getPublicKey(publicInfo)
            ?: throw YumeBootstrapException("Failed to derive the auth public key")
        val publicKeyPem = writePublicKeyPem(publicKey)
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
        val seed = ASN1OctetString.getInstance(privateInfo.parsePrivateKey()).octets
        val publicKeyParameters = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey()
        return SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicKeyParameters)
    }

    private fun writePublicKeyPem(publicKey: PublicKey): String {
        val writer = StringWriter()
        JcaPEMWriter(writer).use { pemWriter ->
            pemWriter.writeObject(publicKey)
        }
        return writer.toString()
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
        val certificates = CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(pem.toByteArray(Charsets.UTF_8)))
        val certificate = certificates.firstOrNull() as? X509Certificate
            ?: throw YumeBootstrapException("Failed to load the X.509 certificate")
        return certificate
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

    private fun buildSystemTlsContext(): TlsContext {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        val provider = tlsProvider
        val context = if (provider != null) {
            SSLContext.getInstance("TLS", provider)
        } else {
            SSLContext.getInstance("TLS")
        }
        context.init(null, trustManagerFactory.trustManagers, SecureRandom())
        val providerLabel = provider?.name ?: context.provider?.name ?: "System"
        return TlsContext(context, providerLabel)
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
