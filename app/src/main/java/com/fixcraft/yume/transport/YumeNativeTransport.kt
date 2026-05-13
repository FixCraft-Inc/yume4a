package com.fixcraft.yume.transport
import com.fixcraft.yume.vpn.VpnLogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class YumeNativeTransport(
    private val sessionProvider: () -> YumeBootstrapSession?,
    private val maxConcurrentTcpStreams: Int,
    private val relayAcquireTimeoutMs: Long,
    private val maxBufferedTcpBytes: Int,
    private val maxBufferedUdpBytes: Int,
    private val warmupTcpDestinationAllowedProvider: (String, Int) -> Boolean,
    private val protectedTcpDestinationBlockedProvider: (String, Int) -> Boolean,
    private val mappedHostResolver: (String) -> String?,
    private val log: (VpnLogLevel, String) -> Unit,
) {
    private sealed interface NativeStreamHandle {
        fun read(timeoutMs: Int): ByteArray?
        fun readInto(buffer: ByteBuffer, timeoutMs: Int): Int
        fun write(data: ByteArray): Int
        fun writeBuffer(buffer: ByteBuffer, length: Int): Int
        fun close(reason: String)
        fun abort(reason: String)
    }

    private class UdpHandle(
        private val session: YumeBootstrapSession,
        host: String,
        port: Int,
        timeoutMs: Int,
        private val maxBufferedWriteBytes: Int,
        private val onClosed: () -> Unit,
    ) : NativeStreamHandle {
        private val closed = AtomicBoolean(false)
        private val pendingWriteBytes = AtomicInteger(0)
        private val readQueue = LinkedBlockingQueue<ByteArray>()
        private val writeQueue = LinkedBlockingQueue<ByteArray>()
        private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val streamReady = CompletableDeferred<YumeUdpStream>()
        @Volatile
        private var currentStream: YumeUdpStream? = null
        private val writeLock = ReentrantLock()
        private val writeSpaceAvailable = writeLock.newCondition()

        init {
            workerScope.launch {
                try {
                    val stream = session.openUdpStream(host, port, timeoutMs)
                    if (closed.get()) {
                        streamReady.completeExceptionally(IllegalStateException("native udp transport already closed"))
                        runCatching { stream.close("native udp transport already closed") }
                        return@launch
                    }
                    currentStream = stream
                    streamReady.complete(stream)
                    launch {
                        runReadLoop(stream)
                    }
                    launch {
                        runWriteLoop(stream)
                    }
                } catch (throwable: Throwable) {
                    streamReady.completeExceptionally(throwable)
                    markClosed()
                }
            }
        }

        suspend fun awaitOpen(timeoutMs: Int): Boolean {
            return runCatching {
                withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1L)) {
                    streamReady.await()
                    true
                } == true
            }.getOrDefault(false)
        }

        override fun read(timeoutMs: Int): ByteArray? {
            val datagram = readQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (datagram != null) {
                return datagram
            }
            return if (closed.get() && readQueue.isEmpty()) {
                null
            } else {
                ByteArray(0)
            }
        }

        override fun readInto(buffer: ByteBuffer, timeoutMs: Int): Int {
            if (!buffer.hasRemaining()) {
                return 0
            }
            val datagram = read(timeoutMs) ?: return -1
            if (datagram.isEmpty()) {
                return 0
            }
            val copied = datagram.size.coerceAtMost(buffer.remaining())
            buffer.put(datagram, 0, copied)
            return copied
        }

        override fun write(data: ByteArray): Int {
            if (data.isEmpty()) {
                return 0
            }
            if (closed.get()) {
                return -1
            }
            val copy = data.copyOf()
            writeLock.withLock {
                while (!closed.get() && pendingWriteBytes.get() + copy.size > maxBufferedWriteBytes) {
                    writeSpaceAvailable.await(5, TimeUnit.MILLISECONDS)
                }
                if (closed.get()) {
                    return -1
                }
                pendingWriteBytes.addAndGet(copy.size)
            }
            if (!writeQueue.offer(copy)) {
                writeLock.withLock {
                    pendingWriteBytes.addAndGet(-copy.size)
                    writeSpaceAvailable.signalAll()
                }
                return -1
            }
            return copy.size
        }

        override fun writeBuffer(buffer: ByteBuffer, length: Int): Int {
            if (length <= 0) {
                return 0
            }
            if (length > buffer.remaining()) {
                return -1
            }
            val copy = ByteArray(length)
            val source = buffer.duplicate()
            source.get(copy, 0, length)
            return write(copy)
        }

        override fun close(reason: String) {
            val shouldClose = closed.compareAndSet(false, true)
            workerScope.cancel()
            writeLock.withLock {
                writeSpaceAvailable.signalAll()
            }
            if (!shouldClose) {
                return
            }
            try {
                val stream = currentStream
                if (stream != null) {
                    runBlocking {
                        stream.close(reason)
                    }
                }
            } finally {
                onClosed()
            }
        }

        override fun abort(reason: String) {
            val shouldClose = closed.compareAndSet(false, true)
            workerScope.cancel()
            writeLock.withLock {
                writeSpaceAvailable.signalAll()
            }
            if (!shouldClose) {
                return
            }
            try {
                currentStream?.closeFromRemote(reason.ifBlank { null })
            } finally {
                onClosed()
            }
        }

        private fun markClosed() {
            if (closed.compareAndSet(false, true)) {
                workerScope.cancel()
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
                onClosed()
            } else {
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
            }
        }

        private suspend fun runReadLoop(stream: YumeUdpStream) {
            try {
                while (true) {
                    val datagram = stream.readDatagram() ?: break
                    if (datagram.isNotEmpty()) {
                        readQueue.put(datagram)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                markClosed()
            }
        }

        private suspend fun runWriteLoop(stream: YumeUdpStream) {
            try {
                while (true) {
                    val datagram = writeQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (datagram == null) {
                        if (closed.get()) {
                            break
                        }
                        continue
                    }
                    try {
                        session.sendDataQueued(stream.streamId, datagram)
                    } finally {
                        writeLock.withLock {
                            pendingWriteBytes.addAndGet(-datagram.size)
                            writeSpaceAvailable.signalAll()
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                markClosed()
            }
        }
    }

    private class TcpHandle(
        private val session: YumeBootstrapSession,
        host: String,
        port: Int,
        timeoutMs: Int,
        private val maxBufferedWriteBytes: Int,
        private val onOpenSettled: () -> Unit,
        private val onClosed: () -> Unit,
    ) : NativeStreamHandle {
        private val closed = AtomicBoolean(false)
        private val openSettled = AtomicBoolean(false)
        private val pendingWriteBytes = AtomicInteger(0)
        private val readQueue = LinkedBlockingQueue<ByteArray>()
        private val writeQueue = LinkedBlockingQueue<ByteArray>()
        private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val streamReady = CompletableDeferred<YumeTcpStream>()
        @Volatile
        private var currentStream: YumeTcpStream? = null
        @Volatile
        private var pendingReadChunk: ByteArray? = null
        @Volatile
        private var pendingReadOffset = 0
        private val writeLock = ReentrantLock()
        private val writeSpaceAvailable = writeLock.newCondition()

        init {
            workerScope.launch {
                try {
                    val stream = session.openTcpStreamOptimistic(host, port, timeoutMs)
                    settleOpen()
                    if (closed.get()) {
                        runCatching { stream.close("native transport already closed") }
                        return@launch
                    }
                    currentStream = stream
                    if (!streamReady.complete(stream)) {
                        runCatching { stream.close("native transport stream registration failed") }
                        return@launch
                    }
                    launch {
                        runReadLoop(stream)
                    }
                    launch {
                        runWriteLoop(stream)
                    }
                } catch (throwable: Throwable) {
                    streamReady.completeExceptionally(throwable)
                    settleOpen()
                    markClosed()
                }
            }
        }

        suspend fun awaitOpen(timeoutMs: Int): Boolean {
            return runCatching {
                withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1L)) {
                    streamReady.await()
                    true
                } == true
            }.getOrDefault(false)
        }

        override fun read(timeoutMs: Int): ByteArray? {
            val pending = pendingReadChunk
            if (pending != null) {
                val offset = pendingReadOffset
                pendingReadChunk = null
                pendingReadOffset = 0
                return if (offset == 0) {
                    pending
                } else {
                    pending.copyOfRange(offset, pending.size)
                }
            }
            val chunk = readQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (chunk != null) {
                return chunk
            }
            return if (closed.get() && readQueue.isEmpty()) {
                null
            } else {
                ByteArray(0)
            }
        }

        override fun readInto(buffer: ByteBuffer, timeoutMs: Int): Int {
            if (!buffer.hasRemaining()) {
                return 0
            }
            var copied = 0
            var remainingTimeoutMs = timeoutMs
            while (buffer.hasRemaining()) {
                val chunk = pendingReadChunk ?: readQueue.poll(
                    remainingTimeoutMs.toLong(),
                    TimeUnit.MILLISECONDS,
                )
                if (chunk == null) {
                    return if (copied > 0) {
                        copied
                    } else if (closed.get() && readQueue.isEmpty()) {
                        -1
                    } else {
                        0
                    }
                }
                if (chunk.isEmpty()) {
                    return copied
                }
                val offset = if (chunk === pendingReadChunk) pendingReadOffset else 0
                val available = chunk.size - offset
                if (available <= 0) {
                    pendingReadChunk = null
                    pendingReadOffset = 0
                    remainingTimeoutMs = 0
                    continue
                }
                val consumed = available.coerceAtMost(buffer.remaining())
                buffer.put(chunk, offset, consumed)
                copied += consumed
                if (offset + consumed < chunk.size) {
                    pendingReadChunk = chunk
                    pendingReadOffset = offset + consumed
                    return copied
                }
                pendingReadChunk = null
                pendingReadOffset = 0
                remainingTimeoutMs = 0
            }
            return copied
        }

        override fun write(data: ByteArray): Int {
            if (data.isEmpty()) {
                return 0
            }
            if (closed.get()) {
                return -1
            }
            val copy = data.copyOf()
            writeLock.withLock {
                while (!closed.get() && pendingWriteBytes.get() + copy.size > maxBufferedWriteBytes) {
                    writeSpaceAvailable.await(5, TimeUnit.MILLISECONDS)
                }
                if (closed.get()) {
                    return -1
                }
                pendingWriteBytes.addAndGet(copy.size)
            }
            if (!writeQueue.offer(copy)) {
                writeLock.withLock {
                    pendingWriteBytes.addAndGet(-copy.size)
                    writeSpaceAvailable.signalAll()
                }
                return -1
            }
            return copy.size
        }

        override fun writeBuffer(buffer: ByteBuffer, length: Int): Int {
            if (length <= 0) {
                return 0
            }
            if (length > buffer.remaining()) {
                return -1
            }
            val copy = ByteArray(length)
            val source = buffer.duplicate()
            source.get(copy, 0, length)
            return write(copy)
        }

        override fun close(reason: String) {
            val shouldClose = closed.compareAndSet(false, true)
            workerScope.cancel()
            settleOpen()
            writeLock.withLock {
                writeSpaceAvailable.signalAll()
            }
            if (!shouldClose) {
                return
            }
            try {
                val stream = currentStreamOrNull()
                if (stream != null) {
                    runBlocking {
                        stream.close(reason)
                    }
                }
            } finally {
                onClosed()
            }
        }

        override fun abort(reason: String) {
            val shouldClose = closed.compareAndSet(false, true)
            workerScope.cancel()
            settleOpen()
            writeLock.withLock {
                writeSpaceAvailable.signalAll()
            }
            if (!shouldClose) {
                return
            }
            try {
                currentStreamOrNull()?.closeFromRemote(reason.ifBlank { null })
            } finally {
                onClosed()
            }
        }

        private fun mergePendingWrites(first: ByteArray): ByteArray {
            if (writeQueue.isEmpty()) {
                return first
            }
            val merged = ArrayList<ByteArray>(4)
            merged += first
            var total = first.size
            while (total < MAX_NATIVE_TCP_MERGE_BYTES) {
                val next = writeQueue.poll() ?: break
                merged += next
                total += next.size
            }
            if (merged.size == 1) {
                return first
            }
            val buffer = ByteArray(total)
            var offset = 0
            for (chunk in merged) {
                chunk.copyInto(buffer, destinationOffset = offset)
                offset += chunk.size
            }
            return buffer
        }

        private fun markClosed() {
            settleOpen()
            if (closed.compareAndSet(false, true)) {
                workerScope.cancel()
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
                onClosed()
            } else {
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
            }
        }

        private fun settleOpen() {
            if (openSettled.compareAndSet(false, true)) {
                onOpenSettled()
            }
        }

        private suspend fun runReadLoop(stream: YumeTcpStream) {
            try {
                while (true) {
                    val chunk = stream.readChunk() ?: break
                    if (chunk.isNotEmpty()) {
                        readQueue.put(chunk)
                    }
                }
            } catch (_: Throwable) {
            } finally {
                markClosed()
            }
        }

        private suspend fun runWriteLoop(stream: YumeTcpStream) {
            try {
                while (true) {
                    val first = writeQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (first == null) {
                        if (closed.get()) {
                            break
                        }
                        continue
                    }
                    val merged = mergePendingWrites(first)
                    var offset = 0
                    var queuedBytes = 0
                    try {
                        while (offset < merged.size) {
                            val chunkSize = (merged.size - offset).coerceAtMost(MAX_NATIVE_TCP_FRAME_BYTES)
                            session.sendDataQueued(
                                streamId = stream.streamId,
                                data = merged,
                                offset = offset,
                                length = chunkSize,
                            )
                            queuedBytes += chunkSize
                            writeLock.withLock {
                                pendingWriteBytes.addAndGet(-chunkSize)
                                writeSpaceAvailable.signalAll()
                            }
                            offset += chunkSize
                            if (offset < merged.size) {
                                yield()
                            }
                        }
                    } finally {
                        val unsentBytes = merged.size - queuedBytes
                        if (unsentBytes > 0) {
                            writeLock.withLock {
                                pendingWriteBytes.addAndGet(-unsentBytes)
                                writeSpaceAvailable.signalAll()
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                markClosed()
            }
        }

        private fun currentStreamOrNull(): YumeTcpStream? {
            return currentStream
        }
    }

    private val stateLock = Any()
    private val warningLock = Any()
    private val streams = mutableMapOf<Int, NativeStreamHandle>()
    private val warnedKeys = linkedSetOf<String>()
    private val tcpStreamLimiter = Semaphore(maxConcurrentTcpStreams.coerceAtLeast(1), false)
    private val tcpOpenLimiter = Semaphore(deriveTcpOpenConcurrency(maxConcurrentTcpStreams), false)
    private var nextHandle = 1
    @Volatile
    private var startupWarmupMode = false

    fun setWarmupMode(enabled: Boolean) {
        startupWarmupMode = enabled
        if (!enabled) {
            synchronized(warningLock) {
                warnedKeys.clear()
            }
        }
    }

    fun openStreamInternal(host: String, port: Int, proto: String, timeoutMs: Int): Int {
        val session = sessionProvider() ?: return -1
        if (!session.isAlive) {
            return -1
        }
        val normalizedProto = proto.lowercase(Locale.US)
        val effectiveHost = mappedHostResolver(host)?.takeIf { it.isNotBlank() } ?: host

        if (isIpv6LiteralHost(effectiveHost)) {
            warnOnce(
                "ipv6:$normalizedProto:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct ${normalizedProto.uppercase(Locale.US)} $effectiveHost:$port blocked on Android because Yume is operating in IPv6 fail-closed mode.",
            )
            return -1
        }
        if (normalizedProto == "tcp" && protectedTcpDestinationBlockedProvider(effectiveHost, port)) {
            warnOnce(
                "protected:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct TCP $effectiveHost:$port blocked to avoid routing the active Yume server endpoint back through the Yume tunnel.",
            )
            return -1
        }
        if (startupWarmupMode && (normalizedProto != "tcp" || !warmupTcpDestinationAllowedProvider(effectiveHost, port))) {
            warnOnce(
                "warmup:$normalizedProto:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct ${normalizedProto.uppercase(Locale.US)} $effectiveHost:$port blocked during Android tunnel warm-up. Yume is only allowing verification traffic until routed protection is confirmed.",
            )
            return -1
        }
        if (normalizedProto == "udp") {
            val handle = UdpHandle(
                session = session,
                host = effectiveHost,
                port = port,
                timeoutMs = timeoutMs,
                maxBufferedWriteBytes = maxBufferedUdpBytes,
                onClosed = {},
            )
            val id = synchronized(stateLock) {
                allocateHandleLocked().also { candidate ->
                    streams[candidate] = handle
                }
            }
            val opened = runBlocking { handle.awaitOpen(timeoutMs) }
            if (!opened) {
                synchronized(stateLock) {
                    streams.remove(id)
                }
                runCatching { handle.abort("native udp open failed") }
                return -1
            }
            return id
        }
        if (normalizedProto != "tcp") {
            warnOnce(
                "proto:$normalizedProto:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct ${normalizedProto.uppercase(Locale.US)} $effectiveHost:$port is not implemented; flow closed.",
            )
            return -1
        }
        val streamPermitAcquired = runCatching {
            tcpStreamLimiter.tryAcquire(relayAcquireTimeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!streamPermitAcquired) {
            warnOnce(
                "stream-cap:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct TCP $effectiveHost:$port blocked because the Android native beta reached its concurrent TCP stream limit.",
            )
            return -1
        }

        val openPermitAcquired = runCatching {
            tcpOpenLimiter.tryAcquire(relayAcquireTimeoutMs, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!openPermitAcquired) {
            tcpStreamLimiter.release()
            warnOnce(
                "pressure:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct TCP $effectiveHost:$port deferred because Yume is smoothing Android TCP fan-out to keep the authenticated session stable.",
            )
            return -1
        }

        val handle = TcpHandle(
            session = session,
            host = effectiveHost,
            port = port,
            timeoutMs = timeoutMs,
            maxBufferedWriteBytes = maxBufferedTcpBytes,
            onOpenSettled = { tcpOpenLimiter.release() },
            onClosed = { tcpStreamLimiter.release() },
        )

        val id = synchronized(stateLock) {
            allocateHandleLocked().also { candidate ->
                streams[candidate] = handle
            }
        }
        val opened = runBlocking { handle.awaitOpen(timeoutMs) }
        if (!opened) {
            synchronized(stateLock) {
                streams.remove(id)
            }
            runCatching { handle.abort("native tcp open failed") }
            return -1
        }
        return id
    }

    fun readStreamInternal(handle: Int, timeoutMs: Int): ByteArray? {
        val stream = synchronized(stateLock) { streams[handle] } ?: return null
        return runCatching {
            stream.read(timeoutMs)
        }.onFailure {
            discardHandle(handle, "native transport read failed")
        }.getOrNull()
    }

    fun readIntoInternal(handle: Int, buffer: ByteBuffer, timeoutMs: Int): Int {
        if (!buffer.isDirect) {
            return -1
        }
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        return runCatching {
            stream.readInto(buffer, timeoutMs)
        }.onFailure {
            discardHandle(handle, "native transport read failed")
        }.getOrDefault(-1)
    }

    fun sendDataInternal(handle: Int, data: ByteArray): Int {
        if (data.isEmpty()) {
            return 0
        }
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        return runCatching {
            stream.write(data)
        }.onFailure {
            discardHandle(handle, "native transport write failed")
        }.getOrDefault(-1)
    }

    fun sendDirectInternal(handle: Int, buffer: ByteBuffer, length: Int): Int {
        if (length <= 0) {
            return 0
        }
        if (!buffer.isDirect) {
            return -1
        }
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        return runCatching {
            stream.writeBuffer(buffer, length)
        }.onFailure {
            discardHandle(handle, "native transport write failed")
        }.getOrDefault(-1)
    }

    fun closeStreamInternal(handle: Int, reason: String): Int {
        val stream = synchronized(stateLock) {
            streams.remove(handle)
        } ?: return 0
        return runCatching {
            stream.abort(reason)
            0
        }.getOrDefault(-1)
    }

    fun isAliveInternal(): Int {
        val session = sessionProvider()
        return if (session != null && session.isAlive) 1 else 0
    }

    fun closeInternal(): Int {
        val toClose = synchronized(stateLock) {
            val copy = streams.toMap()
            streams.clear()
            copy
        }
        toClose.forEach { (handle, stream) ->
            runCatching {
                stream.abort("native transport shutdown")
            }.onFailure {
                synchronized(stateLock) {
                    streams.remove(handle)
                }
            }
        }
        return 0
    }

    private fun discardHandle(handle: Int, reason: String) {
        val stream = synchronized(stateLock) {
            streams.remove(handle)
        }
        stream ?: return
        runCatching {
            stream.abort(reason)
        }
    }

    private fun allocateHandleLocked(): Int {
        repeat(Int.MAX_VALUE) {
            val candidate = nextHandle++
            if (candidate > 0 && candidate !in streams) {
                return candidate
            }
        }
        error("No native transport handles are available")
    }

    private fun isIpv6LiteralHost(host: String): Boolean {
        return ':' in host &&
            !host.contains('.') &&
            !host.contains('%')
    }

    private fun warnOnce(key: String, level: VpnLogLevel, message: String) {
        val shouldLog = synchronized(warningLock) {
            warnedKeys.add(key)
        }
        if (shouldLog) {
            log(level, message)
        }
    }

    companion object {
        private const val MAX_NATIVE_TCP_FRAME_BYTES = 64 * 1024
        private const val MAX_NATIVE_TCP_MERGE_BYTES = 256 * 1024

        private fun deriveTcpOpenConcurrency(maxConcurrentTcpStreams: Int): Int {
            val capped = maxConcurrentTcpStreams.coerceAtLeast(1)
            return (capped / 6).coerceIn(12, 24)
        }
    }
}
