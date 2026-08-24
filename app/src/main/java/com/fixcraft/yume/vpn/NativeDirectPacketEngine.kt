package com.fixcraft.yume.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.transport.YumePacketStreamOpen
import com.fixcraft.yume.transport.YumePacketBulk
import com.fixcraft.yume.transport.SharedYumeNative
import com.fixcraft.yume.transport.SharedYumePacketStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileDescriptor
import java.io.IOException
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.nio.ByteBuffer

enum class NativeDirectEngineStatus {
    Tun2SocksFallback,
    PacketNative,
}

data class NativeDirectEngineSnapshot(
    val status: NativeDirectEngineStatus,
    val packetEngineActive: Boolean,
    val ipv6FailClosed: Boolean,
    val tcpFlowCount: Int? = null,
    val udpFlowCount: Int? = null,
    val queueCapacity: Int? = null,
    val tcpFlowWindowBytes: Int? = null,
    val udpFlowBufferBytes: Int? = null,
)

interface NativeDirectPacketEngine {
    suspend fun start()
    fun stop()
    fun setVerificationMode(enabled: Boolean)
    fun stats(): TechniqueStats?
    fun snapshot(): NativeDirectEngineSnapshot
}

/**
 * Android owns only TUN I/O and caller-owned batching buffers. libyume owns
 * packet-bulk framing, sequences, authenticated encryption, ratchets, and its
 * bounded queues.
 */
internal class SharedYumePacketEngine(
    private val tun: ParcelFileDescriptor,
    private val native: SharedYumeNative,
    private val assignment: SharedYumePacketStatus,
    private val log: (VpnLogLevel, String) -> Unit,
) : NativeDirectPacketEngine {
    private val running = AtomicBoolean(false)
    private val txPackets = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val rxPackets = AtomicLong(0)
    private val rxBytes = AtomicLong(0)
    private var tunRead: ParcelFileDescriptor? = null
    private var tunWrite: ParcelFileDescriptor? = null
    private var outboundThread: Thread? = null
    private var inboundThread: Thread? = null

    override suspend fun start() {
        if (!running.compareAndSet(false, true)) return
        tunRead = ParcelFileDescriptor.dup(tun.fileDescriptor)
        tunWrite = ParcelFileDescriptor.dup(tun.fileDescriptor)
        val input = FileInputStream(requireNotNull(tunRead).fileDescriptor)
        val output = requireNotNull(tunWrite).fileDescriptor
        outboundThread = Thread({ runTunToNative(input) }, "yume-tun-to-native").apply { start() }
        inboundThread = Thread({ runNativeToTun(output) }, "yume-native-to-tun").apply { start() }
        log(
            VpnLogLevel.Info,
            "Shared YUME 2.0 packet ABI is active: IPv4 ${assignment.ipv4}, MTU ${assignment.mtu}.",
        )
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { native.closePacket() }
        runCatching { tunRead?.close() }
        runCatching { tunWrite?.close() }
        outboundThread?.interrupt()
        inboundThread?.interrupt()
        joinWorker(outboundThread)
        joinWorker(inboundThread)
        outboundThread = null
        inboundThread = null
        tunRead = null
        tunWrite = null
    }

    override fun setVerificationMode(enabled: Boolean) = Unit

    override fun stats(): TechniqueStats = TechniqueStats(
        txPackets = txPackets.get(),
        txBytes = txBytes.get(),
        rxPackets = rxPackets.get(),
        rxBytes = rxBytes.get(),
    )

    override fun snapshot(): NativeDirectEngineSnapshot = NativeDirectEngineSnapshot(
        status = NativeDirectEngineStatus.PacketNative,
        packetEngineActive = running.get(),
        ipv6FailClosed = true,
        queueCapacity = SharedYumeNative.MAX_BATCH_PACKETS,
    )

    private fun runTunToNative(input: FileInputStream) {
        var packetCount = 0
        var usedBytes = 0
        var flushDeadlineNanos = 0L
        val mtu = assignment.mtu.coerceIn(576, SharedYumeNative.MAX_PACKET_BYTES)
        val scratch = ByteArray(mtu)
        val storage = ByteBuffer.allocateDirect(BATCH_STORAGE_BYTES)
        val offsets = IntArray(SharedYumeNative.MAX_BATCH_PACKETS)
        val lengths = IntArray(SharedYumeNative.MAX_BATCH_PACKETS)
        try {
            while (running.get()) {
                val read = try {
                    input.read(scratch)
                } catch (error: IOException) {
                    if (isWouldBlockMessage(error)) {
                        if (packetCount > 0 && System.nanoTime() >= flushDeadlineNanos) {
                            writeNativeBatch(storage, offsets, lengths, packetCount)
                            packetCount = 0
                            usedBytes = 0
                        }
                        Thread.sleep(1)
                        continue
                    }
                    throw error
                }
                if (read <= 0) continue
                if (!isCompleteIpv4(scratch, read)) continue
                if (packetCount == offsets.size || usedBytes + read > storage.capacity()) {
                    writeNativeBatch(storage, offsets, lengths, packetCount)
                    packetCount = 0
                    usedBytes = 0
                }
                offsets[packetCount] = usedBytes
                lengths[packetCount] = read
                storage.position(usedBytes)
                storage.put(scratch, 0, read)
                usedBytes += read
                packetCount += 1
                if (packetCount == 1) flushDeadlineNanos = System.nanoTime() + BATCH_FLUSH_NANOS
                if (packetCount == offsets.size || usedBytes >= BATCH_STORAGE_BYTES ||
                    System.nanoTime() >= flushDeadlineNanos
                ) {
                    writeNativeBatch(storage, offsets, lengths, packetCount)
                    packetCount = 0
                    usedBytes = 0
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            failWorker("TUN-to-native", error)
        } finally {
            runCatching { input.close() }
        }
    }

    private fun writeNativeBatch(
        storage: ByteBuffer,
        offsets: IntArray,
        lengths: IntArray,
        packetCount: Int,
    ) {
        if (packetCount == 0) return
        while (running.get()) {
            when (val status = native.writePacketBatch(
                storage, offsets, lengths, packetCount, PACKET_IO_TIMEOUT_MS,
            )) {
                SharedYumeNative.STATUS_OK -> {
                    txPackets.addAndGet(packetCount.toLong())
                    var batchBytes = 0L
                    for (index in 0 until packetCount) batchBytes += lengths[index]
                    txBytes.addAndGet(batchBytes)
                    return
                }
                SharedYumeNative.STATUS_WOULD_BLOCK,
                SharedYumeNative.STATUS_TIMEOUT -> continue
                SharedYumeNative.STATUS_NOT_RUNNING -> return
                else -> native.requirePacketStatus(status, "packet write")
            }
        }
    }

    private fun runNativeToTun(output: FileDescriptor) {
        var storage = ByteBuffer.allocateDirect(BATCH_STORAGE_BYTES)
        val offsets = IntArray(SharedYumeNative.MAX_BATCH_PACKETS)
        val lengths = IntArray(SharedYumeNative.MAX_BATCH_PACKETS)
        try {
            while (running.get()) {
                val result = native.readPacketBatch(
                    storage, offsets, lengths, PACKET_IO_TIMEOUT_MS,
                )
                when (result.status) {
                    SharedYumeNative.STATUS_OK -> {
                        for (index in 0 until result.packetCount) {
                            val offset = offsets[index]
                            val length = lengths[index]
                            if (!isCompleteIpv4(storage, offset, length)) continue
                            storage.position(offset)
                            storage.limit(offset + length)
                            writeTunPacket(output, storage.slice(), length)
                            storage.clear()
                            rxPackets.incrementAndGet()
                            rxBytes.addAndGet(length.toLong())
                        }
                    }
                    SharedYumeNative.STATUS_BUFFER_TOO_SMALL -> {
                        require(result.requiredStorage in 1..MAX_BATCH_STORAGE_BYTES) {
                            "Native packet storage request is out of bounds"
                        }
                        storage = ByteBuffer.allocateDirect(result.requiredStorage)
                    }
                    SharedYumeNative.STATUS_WOULD_BLOCK,
                    SharedYumeNative.STATUS_TIMEOUT -> Unit
                    SharedYumeNative.STATUS_NOT_RUNNING -> return
                    else -> native.requirePacketStatus(result.status, "packet read")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Throwable) {
            failWorker("native-to-TUN", error)
        }
    }

    private fun writeTunPacket(output: FileDescriptor, packet: ByteBuffer, length: Int) {
        while (running.get()) {
            packet.position(0)
            try {
                val written = Os.write(output, packet)
                if (written != length) {
                    throw IOException("Short Android TUN packet write: $written/$length bytes")
                }
                return
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EAGAIN) {
                    throw IOException("Android TUN write failed", error)
                }
                Thread.sleep(1)
            }
        }
    }

    private fun failWorker(label: String, error: Throwable) {
        if (running.get()) {
            log(VpnLogLevel.Error, "Shared YUME $label worker stopped: ${error.message ?: error.javaClass.simpleName}.")
            stop()
        }
    }

    private fun joinWorker(thread: Thread?) {
        if (thread == null || thread === Thread.currentThread()) return
        thread.join(WORKER_JOIN_TIMEOUT_MS)
        if (thread.isAlive) {
            log(VpnLogLevel.Error, "Native YUME packet worker did not stop within ${WORKER_JOIN_TIMEOUT_MS}ms.")
        }
    }

    private fun isCompleteIpv4(packet: ByteArray, length: Int): Boolean {
        if (length < 20 || (packet[0].toInt() ushr 4 and 0x0f) != 4) return false
        val headerLength = (packet[0].toInt() and 0x0f) * 4
        val totalLength = ((packet[2].toInt() and 0xff) shl 8) or
            (packet[3].toInt() and 0xff)
        return headerLength in 20..length && totalLength == length
    }

    private fun isCompleteIpv4(packet: ByteBuffer, offset: Int, length: Int): Boolean {
        if (offset < 0 || length < 20 || offset > packet.capacity() - length) return false
        val first = packet.get(offset).toInt() and 0xff
        if (first ushr 4 != 4) return false
        val headerLength = (first and 0x0f) * 4
        val totalLength = ((packet.get(offset + 2).toInt() and 0xff) shl 8) or
            (packet.get(offset + 3).toInt() and 0xff)
        return headerLength in 20..length && totalLength == length
    }

    private fun isWouldBlockMessage(error: IOException): Boolean {
        val message = error.message?.lowercase(Locale.US).orEmpty()
        return message.contains("eagain") || message.contains("try again") ||
            message.contains("would block") || message.contains("temporarily unavailable")
    }

    private companion object {
        const val BATCH_STORAGE_BYTES = 128 * 1024
        const val MAX_BATCH_STORAGE_BYTES = 4 * 1024 * 1024
        const val BATCH_FLUSH_NANOS = 500_000L
        const val PACKET_IO_TIMEOUT_MS = 250
        const val WORKER_JOIN_TIMEOUT_MS = 5_000L
    }
}

internal class PacketNativeTunnelPacketEngine(
    private val tun: ParcelFileDescriptor,
    private val packetOpen: YumePacketStreamOpen,
    private val log: (VpnLogLevel, String) -> Unit,
) : NativeDirectPacketEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val txPackets = AtomicLong(0)
    private val txBytes = AtomicLong(0)
    private val rxPackets = AtomicLong(0)
    private val rxBytes = AtomicLong(0)
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var sendJob: Job? = null
    private var tunRead: ParcelFileDescriptor? = null
    private var tunWrite: ParcelFileDescriptor? = null
    private val outboundBatches = Channel<YumePacketBulk.Batch>(OUTBOUND_BATCH_QUEUE_CAPACITY)

    override suspend fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        tunRead = ParcelFileDescriptor.dup(tun.fileDescriptor)
        tunWrite = ParcelFileDescriptor.dup(tun.fileDescriptor)
        // YumeVpnService creates the VPN fd with Builder.setBlocking(false);
        // the EAGAIN path below enforces the packet batch flush deadline.
        val input = FileInputStream(requireNotNull(tunRead).fileDescriptor)
        val output = FileOutputStream(requireNotNull(tunWrite).fileDescriptor)
        readJob = scope.launch {
            runTunToYume(input)
        }
        writeJob = scope.launch {
            runYumeToTun(output)
        }
        sendJob = scope.launch {
            runYumeBatchWriter()
        }
        log(
            VpnLogLevel.Info,
            "Packet-native Android data plane is active: one Yume TLS carrier, IPv4 ${packetOpen.assignment.ipv4}, MTU ${packetOpen.assignment.mtu}.",
        )
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        runCatching { packetOpen.stream.closeFromRemote("packet-native stopped") }
        outboundBatches.close()
        readJob?.cancel()
        writeJob?.cancel()
        sendJob?.cancel()
        scope.cancel()
        runCatching { tunRead?.close() }
        runCatching { tunWrite?.close() }
        tunRead = null
        tunWrite = null
    }

    override fun setVerificationMode(enabled: Boolean) {
        // Packet-native has no local proxy warm-up gate; startup probes use the
        // same TUN path once the stream is open.
        if (enabled) return
    }

    override fun stats(): TechniqueStats {
        return TechniqueStats(
            txPackets = txPackets.get(),
            txBytes = txBytes.get(),
            rxPackets = rxPackets.get(),
            rxBytes = rxBytes.get(),
        )
    }

    override fun snapshot(): NativeDirectEngineSnapshot {
        return NativeDirectEngineSnapshot(
            status = NativeDirectEngineStatus.PacketNative,
            packetEngineActive = running.get(),
            ipv6FailClosed = true,
            queueCapacity = OUTBOUND_BATCH_QUEUE_CAPACITY,
        )
    }

    private suspend fun runTunToYume(input: FileInputStream) {
        val mtu = packetOpen.assignment.mtu.coerceIn(576, YumePacketBulk.MAX_PACKET_BYTES)
        val scratch = ByteArray(mtu)
        val pending = ArrayList<ByteArray>(YumePacketBulk.MAX_PACKETS_PER_BATCH)
        var encodedBytes = YumePacketBulk.HEADER_BYTES
        var sequence = 0L
        var flushAtNanos = 0L
        try {
            while (running.get()) {
                currentCoroutineContext().ensureActive()
                val read = try {
                    input.read(scratch)
                } catch (throwable: IOException) {
                    if (isWouldBlock(throwable)) {
                        flushIfDue(pending, encodedBytes, flushAtNanos) { batchPackets ->
                            sequence = sendPacketBatch(sequence, batchPackets)
                            encodedBytes = YumePacketBulk.HEADER_BYTES
                            flushAtNanos = 0L
                        }
                        delay(1)
                        continue
                    }
                    throw throwable
                }
                if (read <= 0) {
                    flushIfDue(pending, encodedBytes, flushAtNanos) { batchPackets ->
                        sequence = sendPacketBatch(sequence, batchPackets)
                        encodedBytes = YumePacketBulk.HEADER_BYTES
                        flushAtNanos = 0L
                    }
                    delay(1)
                    continue
                }
                val packet = scratch.copyOf(read)
                if (!isIpv4Packet(packet)) {
                    continue
                }
                if (!YumePacketBulk.canAppendPacket(encodedBytes, pending.size, packet.size)) {
                    sequence = sendPacketBatch(sequence, pending)
                    pending.clear()
                    encodedBytes = YumePacketBulk.HEADER_BYTES
                    flushAtNanos = 0L
                }
                if (!YumePacketBulk.canAppendPacket(encodedBytes, pending.size, packet.size)) {
                    continue
                }
                pending += packet
                encodedBytes += YumePacketBulk.PACKET_LENGTH_BYTES + packet.size
                if (pending.size == 1) {
                    flushAtNanos = System.nanoTime() + YumePacketBulk.DEFAULT_FLUSH_DELAY_MICROS * 1_000L
                }
                if (pending.size >= YumePacketBulk.MAX_PACKETS_PER_BATCH ||
                    encodedBytes >= YumePacketBulk.DEFAULT_MAX_BATCH_BYTES
                ) {
                    sequence = sendPacketBatch(sequence, pending)
                    pending.clear()
                    encodedBytes = YumePacketBulk.HEADER_BYTES
                    flushAtNanos = 0L
                }
            }
        } catch (_: CancellationException) {
        } catch (throwable: Throwable) {
            if (running.get()) {
                log(VpnLogLevel.Error, "Packet-native TUN read path stopped: ${throwable.message ?: throwable.javaClass.simpleName}.")
                stop()
            }
        } finally {
            if (pending.isNotEmpty() && running.get()) {
                try {
                    sendPacketBatch(sequence, pending)
                } catch (_: Throwable) {
                }
            }
            runCatching { input.close() }
        }
    }

    private suspend fun runYumeToTun(output: FileOutputStream) {
        try {
            while (running.get()) {
                currentCoroutineContext().ensureActive()
                val batch = packetOpen.stream.readPacketBatch() ?: break
                for (packet in batch.packets) {
                    if (!isIpv4Packet(packet)) {
                        continue
                    }
                    writePacketToTun(output, packet)
                    rxPackets.incrementAndGet()
                    rxBytes.addAndGet(packet.size.toLong())
                }
                output.flush()
            }
        } catch (_: CancellationException) {
        } catch (throwable: Throwable) {
            if (running.get()) {
                log(VpnLogLevel.Error, "Packet-native downstream path stopped: ${throwable.message ?: throwable.javaClass.simpleName}.")
                stop()
            }
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun runYumeBatchWriter() {
        try {
            for (batch in outboundBatches) {
                currentCoroutineContext().ensureActive()
                packetOpen.stream.writePacketBatch(batch)
                txPackets.addAndGet(batch.packets.size.toLong())
                txBytes.addAndGet(batch.packets.sumOf { it.size }.toLong())
            }
        } catch (_: CancellationException) {
        } catch (throwable: Throwable) {
            if (running.get()) {
                log(VpnLogLevel.Error, "Packet-native upstream writer stopped: ${throwable.message ?: throwable.javaClass.simpleName}.")
                stop()
            }
        }
    }

    private suspend fun sendPacketBatch(sequence: Long, packets: MutableList<ByteArray>): Long {
        if (packets.isEmpty()) {
            return sequence
        }
        val batchPackets = packets.toList()
        outboundBatches.send(
            YumePacketBulk.Batch(
                sequence = sequence,
                packets = batchPackets,
            )
        )
        return if (sequence == Long.MAX_VALUE) 0L else sequence + 1L
    }

    private suspend fun writePacketToTun(output: FileOutputStream, packet: ByteArray) {
        while (running.get()) {
            currentCoroutineContext().ensureActive()
            try {
                output.write(packet)
                return
            } catch (throwable: IOException) {
                if (!isWouldBlock(throwable)) {
                    throw throwable
                }
                delay(1)
            }
        }
    }

    private suspend fun flushIfDue(
        pending: MutableList<ByteArray>,
        encodedBytes: Int,
        flushAtNanos: Long,
        flush: suspend (MutableList<ByteArray>) -> Unit,
    ) {
        if (pending.isEmpty() || encodedBytes <= YumePacketBulk.HEADER_BYTES || flushAtNanos == 0L) {
            return
        }
        if (System.nanoTime() >= flushAtNanos) {
            flush(pending)
            pending.clear()
        }
    }

    private fun isIpv4Packet(packet: ByteArray): Boolean {
        if (packet.size < 20) {
            return false
        }
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) {
            return false
        }
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || ihl > packet.size) {
            return false
        }
        val totalLength = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
        return totalLength == packet.size && totalLength >= ihl
    }

    private fun isWouldBlock(throwable: IOException): Boolean {
        val message = throwable.message?.lowercase(Locale.US).orEmpty()
        return message.contains("eagain") ||
            message.contains("try again") ||
            message.contains("would block") ||
            message.contains("temporarily unavailable")
    }

    private companion object {
        const val OUTBOUND_BATCH_QUEUE_CAPACITY = 256
    }
}

internal class NativeDirectTechniqueDataPlane(
    private val runtimeConfig: NativeDirectRuntimeConfig,
    private val packetEngine: NativeDirectPacketEngine,
) : TechniqueDataPlane {
    override val technique: ConnectionTechnique = ConnectionTechnique.SharedNativePacket
    override val summary: String = runtimeConfig.summary

    override suspend fun start() {
        packetEngine.start()
    }

    override fun stop() {
        packetEngine.stop()
    }

    override fun setVerificationMode(enabled: Boolean) {
        packetEngine.setVerificationMode(enabled)
    }

    override fun stats(): TechniqueStats? = packetEngine.stats()
}
