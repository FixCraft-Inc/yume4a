package com.fixcraft.yume.vpn

import android.os.ParcelFileDescriptor
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.transport.YumePacketStreamOpen
import com.fixcraft.yume.transport.YumePacketBulk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
    private var tunRead: ParcelFileDescriptor? = null
    private var tunWrite: ParcelFileDescriptor? = null

    override suspend fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        tunRead = ParcelFileDescriptor.dup(tun.fileDescriptor)
        tunWrite = ParcelFileDescriptor.dup(tun.fileDescriptor)
        val input = FileInputStream(requireNotNull(tunRead).fileDescriptor)
        val output = FileOutputStream(requireNotNull(tunWrite).fileDescriptor)
        readJob = scope.launch {
            runTunToYume(input)
        }
        writeJob = scope.launch {
            runYumeToTun(output)
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
        readJob?.cancel()
        writeJob?.cancel()
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
            queueCapacity = YumePacketBulk.MAX_PACKETS_PER_BATCH,
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

    private suspend fun sendPacketBatch(sequence: Long, packets: MutableList<ByteArray>): Long {
        if (packets.isEmpty()) {
            return sequence
        }
        val batchPackets = packets.toList()
        packetOpen.stream.writePacketBatch(
            YumePacketBulk.Batch(
                sequence = sequence,
                packets = batchPackets,
            )
        )
        txPackets.addAndGet(batchPackets.size.toLong())
        txBytes.addAndGet(batchPackets.sumOf { it.size }.toLong())
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
}

internal class NativeDirectTechniqueDataPlane(
    private val runtimeConfig: NativeDirectRuntimeConfig,
    private val packetEngine: NativeDirectPacketEngine,
) : TechniqueDataPlane {
    override val technique: ConnectionTechnique = ConnectionTechnique.NativeDirectBeta
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

    override fun diagnosticsSummary(): String = runtimeConfig.diagnosticsSummary
}
