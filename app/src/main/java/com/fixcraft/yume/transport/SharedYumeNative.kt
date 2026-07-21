package com.fixcraft.yume.transport

import androidx.annotation.Keep
import com.fixcraft.yume.BuildConfig
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class SharedYumeBuildInfo(
    val abiVersion: Int,
    val featureFlags: Int,
    val yumeVersion: String,
    val basefwxVersion: String,
    val pqBackend: String,
)

data class SharedYumeClientStatus(
    val running: Boolean,
    val ready: Boolean,
    val message: String,
    val serverTlsFingerprintSha256: String,
    val serverCapabilities: Set<String>,
    val packetBulkSupported: Boolean,
)

data class SharedYumePacketStatus(
    val ipv4: String,
    val mtu: Int,
    val dnsServers: List<String>,
    val stopped: Boolean,
    val stopReason: String,
    val outboundPackets: Long,
    val outboundBytes: Long,
    val inboundPackets: Long,
    val inboundBytes: Long,
)

data class SharedYumePacketRead(
    val status: Int,
    val packetCount: Int,
    val requiredStorage: Int,
)

@Keep
class SharedYumeNative(
    private val socketProtector: (Int) -> Boolean,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var handle: Long

    init {
        System.loadLibrary("yume")
        System.loadLibrary("yumeandroid")
        handle = nativeCreate(this)
        check(handle != 0L) { "Could not create the native YUME client" }
        try {
            validateRequiredBuild()
        } catch (error: Throwable) {
            val rejected = handle
            handle = 0L
            closed.set(true)
            nativeDestroy(rejected)
            throw error
        }
    }

    val buildInfo: SharedYumeBuildInfo
        get() = SharedYumeBuildInfo(
            abiVersion = nativeAbiVersion(),
            featureFlags = nativeFeatureFlags(),
            yumeVersion = nativeYumeVersion(),
            basefwxVersion = nativeBasefwxVersion(),
            pqBackend = nativePqBackend(),
        )

    fun start(configJson: String, baseDir: String, timeoutMs: Int) {
        require(timeoutMs > 0)
        checkStatus(nativeStart(requireHandle(), configJson, baseDir, timeoutMs), "start")
        val status = clientStatus()
        check(status.running && status.ready) {
            "Native YUME returned before the exact 2.0 session became ready"
        }
    }

    fun stop() {
        val current = synchronized(this) { handle }
        if (current == 0L) return
        val status = nativeStop(current)
        if (status != STATUS_OK && status != STATUS_NOT_RUNNING) {
            throw IOException(errorText(current, "stop", status))
        }
    }

    fun clientStatus(): SharedYumeClientStatus {
        val current = requireHandle()
        val json = nativeClientStatus(current)
            ?: throw IOException(errorText(current, "client status", STATUS_INTERNAL_ERROR))
        val value = JSONObject(json)
        val capabilities = value.optJSONArray("server_capabilities")
        val capabilitySet = buildSet {
            if (capabilities != null) {
                for (index in 0 until capabilities.length()) {
                    capabilities.optString(index).takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
        return SharedYumeClientStatus(
            running = value.optBoolean("running"),
            ready = value.optBoolean("ready"),
            message = value.optString("message"),
            serverTlsFingerprintSha256 = value.optString("server_tls_fingerprint_sha256"),
            serverCapabilities = capabilitySet,
            packetBulkSupported = value.optBoolean("packet_bulk_supported"),
        )
    }

    fun openPacket(timeoutMs: Int): SharedYumePacketStatus {
        require(timeoutMs > 0)
        val current = requireHandle()
        checkStatus(nativeOpenPacket(current, timeoutMs), "packet OPEN")
        return packetStatus().also { status ->
            require(status.ipv4.isIpv4Literal()) { "Server returned an invalid packet IPv4 address" }
            require(status.mtu in MIN_PACKET_MTU..MAX_PACKET_BYTES) {
                "Server returned an invalid packet MTU"
            }
        }
    }

    fun packetStatus(): SharedYumePacketStatus {
        val current = requireHandle()
        val json = nativePacketStatus(current)
            ?: throw IOException(errorText(current, "packet status", STATUS_INTERNAL_ERROR))
        val value = JSONObject(json)
        check(value.optString("protocol") == "packet-bulk-v1") {
            "Native YUME returned an unexpected packet protocol"
        }
        check(value.optString("capability") == PACKET_BULK_CAPABILITY) {
            "Native YUME returned an unexpected packet capability"
        }
        val dns = value.optJSONArray("dns")
        return SharedYumePacketStatus(
            ipv4 = value.getString("ipv4"),
            mtu = value.getInt("mtu"),
            dnsServers = buildList {
                if (dns != null) {
                    for (index in 0 until dns.length()) {
                        dns.optString(index).takeIf { it.isIpv4Literal() }?.let(::add)
                    }
                }
            },
            stopped = value.optBoolean("stopped"),
            stopReason = value.optString("stop_reason"),
            outboundPackets = value.optLong("outbound_packets"),
            outboundBytes = value.optLong("outbound_bytes"),
            inboundPackets = value.optLong("inbound_packets"),
            inboundBytes = value.optLong("inbound_bytes"),
        )
    }

    fun writePacketBatch(
        storage: ByteBuffer,
        offsets: IntArray,
        lengths: IntArray,
        packetCount: Int,
        timeoutMs: Int,
    ): Int {
        require(storage.isDirect) { "Packet storage must be a direct ByteBuffer" }
        require(packetCount in 1..MAX_BATCH_PACKETS)
        require(offsets.size >= packetCount && lengths.size >= packetCount)
        return nativeWritePacketBatch(
            requireHandle(), storage, offsets, lengths, packetCount, timeoutMs,
        )
    }

    fun readPacketBatch(
        storage: ByteBuffer,
        offsets: IntArray,
        lengths: IntArray,
        timeoutMs: Int,
    ): SharedYumePacketRead {
        require(storage.isDirect) { "Packet storage must be a direct ByteBuffer" }
        require(offsets.isNotEmpty() && offsets.size <= MAX_BATCH_PACKETS)
        require(lengths.size == offsets.size)
        val result = nativeReadPacketBatch(
            requireHandle(), storage, offsets, lengths, timeoutMs,
        ) ?: throw IOException("Native YUME packet read returned no result")
        require(result.size == 3)
        return SharedYumePacketRead(
            status = result[0].toInt(),
            packetCount = result[1].toInt(),
            requiredStorage = result[2].toInt(),
        )
    }

    fun closePacket() {
        val current = synchronized(this) { handle }
        if (current == 0L) return
        val status = nativeClosePacket(current)
        if (status != STATUS_OK && status != STATUS_NOT_RUNNING) {
            throw IOException(errorText(current, "packet close", status))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val current = synchronized(this) {
            handle.also { handle = 0L }
        }
        if (current != 0L) nativeDestroy(current)
    }

    fun requirePacketStatus(status: Int, operation: String) {
        if (status != STATUS_OK) throw IOException(errorText(requireHandle(), operation, status))
    }

    @Keep
    @Suppress("unused")
    private fun protectSocket(socketFd: Int): Boolean =
        !closed.get() && runCatching { socketProtector(socketFd) }.getOrDefault(false)

    private fun validateRequiredBuild() {
        val info = buildInfo
        check(info.abiVersion == REQUIRED_ABI_VERSION) {
            "Unsupported native YUME ABI ${info.abiVersion}; expected $REQUIRED_ABI_VERSION"
        }
        check(info.yumeVersion == REQUIRED_YUME_VERSION &&
            BuildConfig.YUME_PROTOCOL_VERSION == REQUIRED_YUME_VERSION
        ) {
            "Android requires exact YUME $REQUIRED_YUME_VERSION; native=${info.yumeVersion} source=${BuildConfig.YUME_PROTOCOL_VERSION}"
        }
        val required = FEATURE_PQ_MLKEM1024 or FEATURE_PACKET_BULK
        check(info.featureFlags and required == required) {
            "Native YUME lacks mandatory ML-KEM-1024 or packet-bulk support"
        }
        check(!info.pqBackend.equals("unavailable", ignoreCase = true)) {
            "Native YUME has no post-quantum backend"
        }
    }

    private fun checkStatus(status: Int, operation: String) {
        if (status != STATUS_OK) throw IOException(errorText(requireHandle(), operation, status))
    }

    private fun errorText(current: Long, operation: String, status: Int): String {
        val detail = nativeLastError(current).orEmpty().ifBlank { "status $status" }
        return "Native YUME $operation failed: $detail"
    }

    @Synchronized
    private fun requireHandle(): Long {
        check(!closed.get() && handle != 0L) { "Native YUME session is closed" }
        return handle
    }

    private external fun nativeCreate(protector: Any): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeAbiVersion(): Int
    private external fun nativeFeatureFlags(): Int
    private external fun nativeYumeVersion(): String
    private external fun nativeBasefwxVersion(): String
    private external fun nativePqBackend(): String
    private external fun nativeStart(handle: Long, configJson: String, baseDir: String, timeoutMs: Int): Int
    private external fun nativeStop(handle: Long): Int
    private external fun nativeClientStatus(handle: Long): String?
    private external fun nativeOpenPacket(handle: Long, timeoutMs: Int): Int
    private external fun nativePacketStatus(handle: Long): String?
    private external fun nativeWritePacketBatch(
        handle: Long,
        storage: ByteBuffer,
        offsets: IntArray,
        lengths: IntArray,
        packetCount: Int,
        timeoutMs: Int,
    ): Int
    private external fun nativeReadPacketBatch(
        handle: Long,
        storage: ByteBuffer,
        offsets: IntArray,
        lengths: IntArray,
        timeoutMs: Int,
    ): LongArray?
    private external fun nativeClosePacket(handle: Long): Int
    private external fun nativeLastError(handle: Long): String?

    companion object {
        const val STATUS_OK = 0
        const val STATUS_BUFFER_TOO_SMALL = -2
        const val STATUS_NOT_RUNNING = -4
        const val STATUS_TIMEOUT = -6
        const val STATUS_WOULD_BLOCK = -10
        const val MAX_BATCH_PACKETS = 64
        const val MAX_PACKET_BYTES = 65_535
        const val PACKET_BULK_CAPABILITY = "packet_bulk_v1"

        private const val STATUS_INTERNAL_ERROR = -3
        private const val REQUIRED_ABI_VERSION = 1
        private const val REQUIRED_YUME_VERSION = "2.0-dev1"
        private const val FEATURE_PQ_MLKEM1024 = 0x10
        private const val FEATURE_PACKET_BULK = 0x20
        private const val MIN_PACKET_MTU = 576
    }
}

private fun String.isIpv4Literal(): Boolean {
    val octets = split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() && octet.all(Char::isDigit) &&
            octet.length <= 3 && octet.toIntOrNull()?.let { it in 0..255 } == true
    }
}
