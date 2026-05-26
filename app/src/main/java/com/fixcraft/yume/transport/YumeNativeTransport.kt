package com.fixcraft.yume.transport
import com.fixcraft.yume.vpn.VpnLogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.util.Locale
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

    private class NativeByteQueue {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        private val queuedChunks = AtomicInteger(0)

        fun offer(chunk: ByteArray): Boolean {
            queuedChunks.incrementAndGet()
            val result = channel.trySend(chunk)
            if (result.isSuccess) {
                return true
            }
            queuedChunks.decrementAndGet()
            return false
        }

        fun poll(timeoutMs: Int): ByteArray? {
            pollNow()?.let { return it }
            if (timeoutMs <= 0) {
                return null
            }
            return runBlocking {
                withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1L)) {
                    receive()
                }
            }
        }

        fun pollNow(): ByteArray? {
            val chunk = channel.tryReceive().getOrNull() ?: return null
            queuedChunks.decrementAndGet()
            return chunk
        }

        suspend fun receive(): ByteArray? {
            val chunk = channel.receiveCatching().getOrNull() ?: return null
            queuedChunks.decrementAndGet()
            return chunk
        }

        suspend fun receiveWithin(timeoutMs: Long): ByteArray? {
            pollNow()?.let { return it }
            if (timeoutMs <= 0L) {
                return null
            }
            return withTimeoutOrNull(timeoutMs) {
                receive()
            }
        }

        fun isEmpty(): Boolean = queuedChunks.get() == 0

        fun close() {
            channel.close()
        }
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
        private val readQueue = NativeByteQueue()
        private val writeQueue = NativeByteQueue()
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
            val datagram = readQueue.poll(timeoutMs)
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
            return enqueueOwned(data.copyOf())
        }

        override fun writeBuffer(buffer: ByteBuffer, length: Int): Int {
            if (length <= 0) {
                return 0
            }
            if (length > buffer.remaining()) {
                return -1
            }
            val owned = ByteArray(length)
            val source = buffer.duplicate()
            source.get(owned, 0, length)
            return enqueueOwned(owned)
        }

        // Takes ownership of `payload`. Used by both write paths so writeBuffer
        // doesn't allocate twice on the per-datagram hot path.
        private fun enqueueOwned(payload: ByteArray): Int {
            if (closed.get()) {
                return -1
            }
            writeLock.withLock {
                while (!closed.get() && pendingWriteBytes.get() + payload.size > maxBufferedWriteBytes) {
                    writeSpaceAvailable.await(5, TimeUnit.MILLISECONDS)
                }
                if (closed.get()) {
                    return -1
                }
                pendingWriteBytes.addAndGet(payload.size)
            }
            if (!writeQueue.offer(payload)) {
                writeLock.withLock {
                    pendingWriteBytes.addAndGet(-payload.size)
                    writeSpaceAvailable.signalAll()
                }
                return -1
            }
            return payload.size
        }

        override fun close(reason: String) {
            val shouldClose = closed.compareAndSet(false, true)
            readQueue.close()
            writeQueue.close()
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
            readQueue.close()
            writeQueue.close()
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
                readQueue.close()
                writeQueue.close()
                workerScope.cancel()
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
                onClosed()
            } else {
                readQueue.close()
                writeQueue.close()
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
                        if (!readQueue.offer(datagram)) {
                            break
                        }
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
                    val datagram = writeQueue.receive() ?: break
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
        private val readQueue = NativeByteQueue()
        private val writeQueue = NativeByteQueue()
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
                    val stream = session.openTcpStream(host, port, timeoutMs)
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
            val chunk = readQueue.poll(timeoutMs)
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
                val chunk = pendingReadChunk ?: readQueue.poll(remainingTimeoutMs)
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
            return enqueueOwned(data.copyOf())
        }

        override fun writeBuffer(buffer: ByteBuffer, length: Int): Int {
            if (length <= 0) {
                return 0
            }
            if (length > buffer.remaining()) {
                return -1
            }
            val owned = ByteArray(length)
            val source = buffer.duplicate()
            source.get(owned, 0, length)
            return enqueueOwned(owned)
        }

        // Takes ownership of `payload`. The TUN write path hammers this — every
        // duplicated allocation hurts throughput on large transfers.
        private fun enqueueOwned(payload: ByteArray): Int {
            if (closed.get()) {
                return -1
            }
            writeLock.withLock {
                while (!closed.get() && pendingWriteBytes.get() + payload.size > maxBufferedWriteBytes) {
                    writeSpaceAvailable.await(5, TimeUnit.MILLISECONDS)
                }
                if (closed.get()) {
                    return -1
                }
                pendingWriteBytes.addAndGet(payload.size)
            }
            if (!writeQueue.offer(payload)) {
                writeLock.withLock {
                    pendingWriteBytes.addAndGet(-payload.size)
                    writeSpaceAvailable.signalAll()
                }
                return -1
            }
            return payload.size
        }

        override fun close(reason: String) {
            val shouldClose = closed.compareAndSet(false, true)
            readQueue.close()
            writeQueue.close()
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
            readQueue.close()
            writeQueue.close()
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

        // Per-TcpHandle merge scratch buffer. Grows monotonically up to
        // MAX_NATIVE_TCP_MERGE_BYTES and is reused across merge calls
        // for the lifetime of this stream. Previously this was a fresh
        // ByteArray(total) per merge, which at high upload rates was
        // ~6 MiB/s of garbage per active stream — the dominant source
        // of GC pressure on the upload-direction TCP splice.
        @Volatile
        private var mergeScratch: ByteArray = ByteArray(0)

        private data class MergedWrite(val buffer: ByteArray, val length: Int)

        private suspend fun mergePendingWrites(first: ByteArray): MergedWrite {
            if (writeQueue.isEmpty()) {
                val delayed = if (first.size < NATIVE_TCP_COALESCE_WAIT_BELOW_BYTES) {
                    writeQueue.receiveWithin(NATIVE_TCP_COALESCE_DELAY_MS)
                } else {
                    null
                }
                if (delayed == null) {
                    return MergedWrite(first, first.size)
                }
                val merged = ArrayList<ByteArray>(8)
                merged += first
                merged += delayed
                val total = first.size + delayed.size
                return mergePendingWrites(merged, total)
            }
            val merged = ArrayList<ByteArray>(8)
            merged += first
            return mergePendingWrites(merged, first.size)
        }

        private fun mergePendingWrites(merged: ArrayList<ByteArray>, initialBytes: Int): MergedWrite {
            var total = initialBytes
            while (total < MAX_NATIVE_TCP_MERGE_BYTES) {
                val next = writeQueue.pollNow() ?: break
                merged += next
                total += next.size
            }
            if (merged.size == 1) {
                val only = merged[0]
                return MergedWrite(only, only.size)
            }
            var scratch = mergeScratch
            if (scratch.size < total) {
                scratch = ByteArray(maxOf(total, scratch.size * 2, 16 * 1024))
                mergeScratch = scratch
            }
            var offset = 0
            for (chunk in merged) {
                chunk.copyInto(scratch, destinationOffset = offset)
                offset += chunk.size
            }
            return MergedWrite(scratch, total)
        }

        private fun markClosed() {
            settleOpen()
            if (closed.compareAndSet(false, true)) {
                readQueue.close()
                writeQueue.close()
                workerScope.cancel()
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
                onClosed()
            } else {
                readQueue.close()
                writeQueue.close()
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
                        if (!readQueue.offer(chunk)) {
                            break
                        }
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
                    val first = writeQueue.receive() ?: break
                    val merged = mergePendingWrites(first)
                    val mergedBuffer = merged.buffer
                    val mergedLen = merged.length
                    var offset = 0
                    var queuedBytes = 0
                    try {
                        while (offset < mergedLen) {
                            val chunkSize = (mergedLen - offset).coerceAtMost(MAX_NATIVE_TCP_FRAME_BYTES)
                            session.sendDataQueued(
                                streamId = stream.streamId,
                                data = mergedBuffer,
                                offset = offset,
                                length = chunkSize,
                            )
                            queuedBytes += chunkSize
                            writeLock.withLock {
                                pendingWriteBytes.addAndGet(-chunkSize)
                                writeSpaceAvailable.signalAll()
                            }
                            offset += chunkSize
                            if (offset < mergedLen) {
                                yield()
                            }
                        }
                    } finally {
                        val unsentBytes = mergedLen - queuedBytes
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
        private const val MAX_NATIVE_TCP_FRAME_BYTES = 256 * 1024
        private const val MAX_NATIVE_TCP_MERGE_BYTES = 256 * 1024
        private const val NATIVE_TCP_COALESCE_DELAY_MS = 1L
        private const val NATIVE_TCP_COALESCE_WAIT_BELOW_BYTES = 32 * 1024

        private fun deriveTcpOpenConcurrency(maxConcurrentTcpStreams: Int): Int {
            val capped = maxConcurrentTcpStreams.coerceAtLeast(1)
            return (capped / 4).coerceIn(4, 8)
        }
    }
}
