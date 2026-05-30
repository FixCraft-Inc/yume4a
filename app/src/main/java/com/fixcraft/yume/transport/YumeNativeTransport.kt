package com.fixcraft.yume.transport

import android.os.SystemClock
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
    private val maxConcurrentTcpOpens: Int,
    private val relayAcquireTimeoutMs: Long,
    private val relayAcquireSliceMs: Long,
    private val idleTcpEvictionMs: Long,
    private val tcpEvictionGraceMs: Long,
    private val tcpEvictionBatchSize: Int,
    private val maxBufferedTcpBytes: Int,
    private val maxBufferedUdpBytes: Int,
    private val protectedTcpDestinationBlockedProvider: (String, Int) -> Boolean,
    private val mappedHostResolver: (String) -> String?,
    private val log: (VpnLogLevel, String) -> Unit,
) {
    private sealed interface NativeStreamHandle {
        fun read(timeoutMs: Int): ByteArray?
        fun readInto(buffer: ByteBuffer, timeoutMs: Int): Int
        fun write(data: ByteArray): Int
        fun writeBuffer(buffer: ByteBuffer, length: Int): Int
        fun finishInput(reason: String): Int
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

        override fun finishInput(reason: String): Int = -1

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

        // Identity-compared marker enqueued onto writeQueue to mark "upload is
        // half-closed; send the stream FIN once everything ahead of this point
        // has been flushed to the carrier". Routing the FIN through the SAME
        // ordered queue as DATA is what prevents it from overtaking in-flight
        // upload bytes (which made the server SHUT_WR early and truncate the
        // upload). Never carries data — length is always 0.
        private val uploadFinSentinel = ByteArray(0)
        @Volatile
        private var pendingFinReason: String? = null

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
            val chunk = readNextChunk(timeoutMs)
            if (chunk != null) {
                return chunk
            }
            return if (isInputClosed()) {
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
                val pending = pendingReadChunk
                val chunk = pending ?: readNextChunk(remainingTimeoutMs)
                if (chunk == null) {
                    return if (copied > 0) {
                        copied
                    } else if (isInputClosed()) {
                        -1
                    } else {
                        0
                    }
                }
                if (chunk.isEmpty()) {
                    return copied
                }
                val offset = if (pending != null) pendingReadOffset else 0
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

        override fun finishInput(reason: String): Int {
            if (closed.get()) {
                return -1
            }
            // Do NOT send the FIN inline here (relay thread). The last upload
            // DATA may still be sitting in writeQueue, drained asynchronously by
            // runWriteLoop. Enqueue the FIN as an ordered sentinel so runWriteLoop
            // emits it only after the final DATA chunk has been queued to the
            // carrier — preserving app-TCP byte order and stopping the server
            // from half-closing the upstream before the last bytes arrive.
            pendingFinReason = reason.ifBlank { "native TCP upload finished" }
            return if (writeQueue.offer(uploadFinSentinel)) 0 else -1
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

        // finFollows: the upload FIN sentinel was dequeued at or immediately
        // after this batch, so runWriteLoop must send the stream FIN once this
        // batch is on the carrier (and not before).
        private data class MergedWrite(val buffer: ByteArray, val length: Int, val finFollows: Boolean = false)

        private suspend fun mergePendingWrites(first: ByteArray): MergedWrite {
            if (first === uploadFinSentinel) {
                return MergedWrite(first, 0, finFollows = true)
            }
            if (writeQueue.isEmpty()) {
                val delayed = if (first.size < NATIVE_TCP_COALESCE_WAIT_BELOW_BYTES) {
                    writeQueue.receiveWithin(NATIVE_TCP_COALESCE_DELAY_MS)
                } else {
                    null
                }
                if (delayed == null) {
                    return MergedWrite(first, first.size)
                }
                if (delayed === uploadFinSentinel) {
                    return MergedWrite(first, first.size, finFollows = true)
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
            var finFollows = false
            while (total < MAX_NATIVE_TCP_MERGE_BYTES) {
                val next = writeQueue.pollNow() ?: break
                if (next === uploadFinSentinel) {
                    finFollows = true
                    break
                }
                merged += next
                total += next.size
            }
            if (merged.size == 1) {
                val only = merged[0]
                return MergedWrite(only, only.size, finFollows)
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
            return MergedWrite(scratch, total, finFollows)
        }

        private fun markClosed() {
            settleOpen()
            if (closed.compareAndSet(false, true)) {
                writeQueue.close()
                workerScope.cancel()
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
                onClosed()
            } else {
                writeQueue.close()
                writeLock.withLock {
                    writeSpaceAvailable.signalAll()
                }
            }
        }

        private fun readNextChunk(timeoutMs: Int): ByteArray? {
            val stream = currentStreamOrNull() ?: return null
            if (timeoutMs <= 0) {
                return stream.tryReadChunk()
            }
            return runBlocking {
                withTimeoutOrNull(timeoutMs.toLong().coerceAtLeast(1L)) {
                    stream.readChunk()
                }
            }
        }

        private fun isInputClosed(): Boolean {
            return closed.get() || currentStreamOrNull()?.isInputClosed == true
        }

        private fun settleOpen() {
            if (openSettled.compareAndSet(false, true)) {
                onOpenSettled()
            }
        }

        private suspend fun runWriteLoop(stream: YumeTcpStream) {
            var halfClosed = false
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
                            session.sendTcpUpload(
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
                    if (merged.finFollows) {
                        // Every DATA chunk ahead of the FIN sentinel has now been
                        // queued to the carrier (sendTcpUpload above), so the FIN
                        // can no longer overtake the upload. Send it and stop the
                        // upload loop — but DO NOT markClosed(): this is a TCP
                        // half-close; the download direction must keep running.
                        runCatching {
                            stream.finishUpload(pendingFinReason ?: "native TCP upload finished")
                        }
                        halfClosed = true
                        break
                    }
                }
            } catch (_: Throwable) {
            } finally {
                if (!halfClosed) {
                    markClosed()
                }
            }
        }

        private fun currentStreamOrNull(): YumeTcpStream? {
            return currentStream
        }
    }

    private val stateLock = Any()
    private val warningLock = Any()
    private val streams = mutableMapOf<Int, NativeStreamHandle>()
    private val tcpHandles = linkedMapOf<Int, ActiveTcpHandle>()
    private val warnedKeys = linkedSetOf<String>()
    private val tcpStreamLimiter = Semaphore(maxConcurrentTcpStreams.coerceAtLeast(1), false)
    private val tcpOpenLimiter = Semaphore(
        maxConcurrentTcpOpens.coerceIn(1, maxConcurrentTcpStreams.coerceAtLeast(1)),
        false,
    )
    private var nextHandle = 1

    private data class ActiveTcpHandle(
        val handle: Int,
        val host: String,
        val port: Int,
        val stream: NativeStreamHandle,
        val createdAtMs: Long = SystemClock.elapsedRealtime(),
        @Volatile var lastActivityAtMs: Long = createdAtMs,
    ) {
        fun touch(nowMs: Long = SystemClock.elapsedRealtime()) {
            lastActivityAtMs = nowMs
        }
    }

    fun setWarmupMode(enabled: Boolean) {
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
                "Native direct ${normalizedProto.uppercase(Locale.US)} $effectiveHost:$port blocked on Android because this Yume path is IPv4-only.",
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
        val streamPermitAcquired = acquireTcpStreamPermit(effectiveHost, port)
        if (!streamPermitAcquired) {
            val activeCount = synchronized(stateLock) { tcpHandles.size }
            warnOnce(
                "stream-cap:$effectiveHost:$port",
                VpnLogLevel.Warn,
                "Native direct TCP $effectiveHost:$port blocked because the Android native beta reached its concurrent TCP stream limit ($activeCount/$maxConcurrentTcpStreams active).",
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

        val id = synchronized(stateLock) { allocateHandleLocked() }
        val handle = TcpHandle(
            session = session,
            host = effectiveHost,
            port = port,
            timeoutMs = timeoutMs,
            maxBufferedWriteBytes = maxBufferedTcpBytes,
            onOpenSettled = { tcpOpenLimiter.release() },
            onClosed = { releaseTcpHandle(id) },
        )

        synchronized(stateLock) {
            streams[id] = handle
            tcpHandles[id] = ActiveTcpHandle(
                handle = id,
                host = effectiveHost,
                port = port,
                stream = handle,
            )
        }
        val opened = runBlocking { handle.awaitOpen(timeoutMs) }
        if (!opened) {
            synchronized(stateLock) {
                removeHandleLocked(id)
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
        }.getOrNull()?.also { data ->
            if (data.isNotEmpty()) {
                touchTcpHandle(handle)
            }
        }
    }

    fun readIntoInternal(handle: Int, buffer: ByteBuffer, timeoutMs: Int): Int {
        if (!buffer.isDirect) {
            return -1
        }
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        val result = runCatching {
            stream.readInto(buffer, timeoutMs)
        }.onFailure {
            discardHandle(handle, "native transport read failed")
        }.getOrDefault(-1)
        if (result > 0) {
            touchTcpHandle(handle)
        }
        return result
    }

    fun sendDataInternal(handle: Int, data: ByteArray): Int {
        if (data.isEmpty()) {
            return 0
        }
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        val result = runCatching {
            stream.write(data)
        }.onFailure {
            discardHandle(handle, "native transport write failed")
        }.getOrDefault(-1)
        if (result > 0) {
            touchTcpHandle(handle)
        }
        return result
    }

    fun sendDirectInternal(handle: Int, buffer: ByteBuffer, length: Int): Int {
        if (length <= 0) {
            return 0
        }
        if (!buffer.isDirect) {
            return -1
        }
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        val result = runCatching {
            stream.writeBuffer(buffer, length)
        }.onFailure {
            discardHandle(handle, "native transport write failed")
        }.getOrDefault(-1)
        if (result > 0) {
            touchTcpHandle(handle)
        }
        return result
    }

    fun closeStreamInternal(handle: Int, reason: String): Int {
        val stream = synchronized(stateLock) {
            removeHandleLocked(handle)
        } ?: return 0
        return runCatching {
            stream.abort(reason)
            0
        }.getOrDefault(-1)
    }

    fun finishStreamInputInternal(handle: Int, reason: String): Int {
        val stream = synchronized(stateLock) { streams[handle] } ?: return -1
        val result = runCatching {
            stream.finishInput(reason)
        }.getOrDefault(-1)
        if (result == 0) {
            touchTcpHandle(handle)
        }
        return result
    }

    fun isAliveInternal(): Int {
        val session = sessionProvider()
        return if (session != null && session.isAlive) 1 else 0
    }

    fun closeInternal(): Int {
        val toClose = synchronized(stateLock) {
            val copy = streams.toMap()
            streams.clear()
            tcpHandles.clear()
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
            removeHandleLocked(handle)
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

    private fun removeHandleLocked(handle: Int): NativeStreamHandle? {
        tcpHandles.remove(handle)
        return streams.remove(handle)
    }

    private fun releaseTcpHandle(handle: Int) {
        if (handle > 0) {
            synchronized(stateLock) {
                removeHandleLocked(handle)
            }
        }
        tcpStreamLimiter.release()
    }

    private fun touchTcpHandle(handle: Int) {
        synchronized(stateLock) {
            tcpHandles[handle]?.touch()
        }
    }

    private fun acquireTcpStreamPermit(host: String, port: Int): Boolean {
        val timeoutMs = relayAcquireTimeoutMs.coerceAtLeast(1L)
        val sliceMs = relayAcquireSliceMs.coerceAtLeast(1L)
        val deadlineMs = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val remainingMs = deadlineMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                return false
            }
            val waitMs = minOf(remainingMs, sliceMs)
            val acquired = runCatching {
                tcpStreamLimiter.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
            }.getOrDefault(false)
            if (acquired) {
                return true
            }
            evictStaleTcpStreamsForPressure(host, port)
        }
    }

    private fun evictStaleTcpStreamsForPressure(requestHost: String, requestPort: Int) {
        val batchSize = tcpEvictionBatchSize.coerceAtLeast(0)
        if (batchSize == 0) {
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val idleMs = idleTcpEvictionMs.coerceAtLeast(0L)
        val graceMs = tcpEvictionGraceMs.coerceAtLeast(0L)
        val evicted = synchronized(stateLock) {
            tcpHandles.values
                .asSequence()
                .filter { relay ->
                    nowMs - relay.createdAtMs >= graceMs &&
                        nowMs - relay.lastActivityAtMs >= idleMs
                }
                .sortedWith(
                    compareByDescending<ActiveTcpHandle> {
                        pressureEvictionScore(it, requestHost, requestPort)
                    }.thenBy { it.lastActivityAtMs },
                )
                .take(batchSize)
                .toList()
                .also { candidates ->
                    candidates.forEach { candidate ->
                        removeHandleLocked(candidate.handle)
                    }
                }
        }
        evicted.forEach { relay ->
            log(
                VpnLogLevel.Warn,
                "Evicting stale native-direct TCP ${relay.host}:${relay.port} to preserve Yume stream capacity for $requestHost:$requestPort.",
            )
            runCatching { relay.stream.abort("evicted for native-direct stream pressure") }
        }
    }

    private fun pressureEvictionScore(relay: ActiveTcpHandle, requestHost: String, requestPort: Int): Int {
        var score = 0
        if (isLikelyAdOrTrackerHost(relay.host)) {
            score += 100
        }
        if (requestPort != 443 && relay.port == 443) {
            score += 20
        }
        if (relay.port == requestPort) {
            score += 5
        }
        if (isLikelyMeasurementHost(relay.host) && !isLikelyMeasurementHost(requestHost)) {
            score -= 100
        }
        return score
    }

    private fun isLikelyMeasurementHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US)
        return "speedtest" in normalized ||
            "ookla" in normalized ||
            "nperf" in normalized ||
            "measurement-lab" in normalized ||
            ".mlab-" in normalized ||
            normalized == "fast.com" ||
            normalized.endsWith(".fast.com")
    }

    private fun isLikelyAdOrTrackerHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US)
        val labels = normalized.split('.')
        return labels.any { label ->
            label == "ad" ||
                label == "ads" ||
                label.startsWith("ad-") ||
                label.endsWith("-ad") ||
                label.startsWith("ads-") ||
                label.endsWith("-ads")
        } ||
            "doubleclick" in normalized ||
            "googlesyndication" in normalized ||
            "scorecardresearch" in normalized ||
            "rubiconproject" in normalized ||
            "criteo" in normalized ||
            "pubmatic" in normalized ||
            "openx" in normalized ||
            "adsrvr" in normalized ||
            "imrworldwide" in normalized ||
            "casalemedia" in normalized ||
            "sharethrough" in normalized ||
            "id5-sync" in normalized ||
            "bidswitch" in normalized ||
            "rtb" in labels
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
        private const val MAX_NATIVE_TCP_FRAME_BYTES = 128 * 1024
        private const val MAX_NATIVE_TCP_MERGE_BYTES = 256 * 1024
        private const val NATIVE_TCP_COALESCE_DELAY_MS = 1L
        private const val NATIVE_TCP_COALESCE_WAIT_BELOW_BYTES = 32 * 1024
    }
}
