package com.fixcraft.yume.core

import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** `yume_endpoint_status`, as one consistent snapshot. */
data class YumeStatus(
    val state: Int,
    val session: Int,
    val lastFailure: Int,
    val epochBytes: Long,
    val connectedMs: Long,
    val retryMs: Long,
    val sessions: Long,
    val failedAttempts: Long,
    val idleEpochRotation: Boolean,
    val payloadBytesSent: Long,
    val payloadBytesReceived: Long,
    val recordBytesSent: Long,
    val recordBytesReceived: Long,
    val maxQueuedBytes: Long,
    val maxEpochBytes: Long,
    val creditReturnsPerWindow: Int,
    val tcpConnections: Int,
    val udpDestinations: Int,
    /** SHA-256 of the server's composite identity in hex, empty without a session. */
    val peerFingerprint: String,
    val lastFailureMessage: String,
) {
    companion object {
        const val STATE_RUNNING = 3
        const val STATE_FAILED = 6

        const val SESSION_NONE = 0
        const val SESSION_ACTIVE = 1
        const val SESSION_ENDED = 2
        const val SESSION_CONNECTING = 3
        const val SESSION_WAITING = 4
    }
}

/** One line of the endpoint's message feed. */
data class YumeMessage(
    val seq: Long,
    val missed: Long,
    val timeUnixMs: Long,
    val instance: String,
    val text: String,
)

/** The device a client endpoint carries (docs/ABI.md, "Device bridge"). */
data class YumeDevice(
    val descriptor: Int,
    val mtu: Int,
    val streamService: String,
    val packetService: String?,
    val ipv4Address: String?,
    val ipv4Peer: String?,
    val ipv6Address: String?,
    val ipv6Peer: String?,
)

/**
 * One client endpoint of libyume with its runtime.
 *
 * Every call may come from any thread. [stop] cancels a [start] that is in
 * progress on another thread, and [status] and [readMessage] never wait for
 * either. [close] waits for calls in progress, so it cancels a start first.
 */
class YumeClient private constructor(private var handle: Long) : Closeable {
    private val lock = ReentrantReadWriteLock()

    /** Attaches the VPN's device. Accepted before the first start and while stopped. */
    fun setDevice(device: YumeDevice) = call { handle, result ->
        YumeNative.nativeSetDevice(
            handle,
            device.descriptor,
            device.mtu,
            device.streamService,
            device.packetService,
            device.ipv4Address,
            device.ipv4Peer,
            device.ipv6Address,
            device.ipv6Peer,
            result,
        )
    }

    /**
     * Blocks until the first session is authenticated. Throws with
     * [YumeStatusCode.CANCELLED] when another thread stops the endpoint, and
     * with the attempt's failure otherwise.
     */
    fun start(timeoutMs: Int) = call { handle, result ->
        YumeNative.nativeStart(handle, timeoutMs, result)
    }

    /** Ends the session and the bridge. Also takes a failed endpoint back to stopped. */
    fun stop() {
        lock.read {
            if (handle != 0L) YumeNative.nativeStop(handle)
        }
    }

    /** Starts a waiting reconnection attempt now. Harmless in every other state. */
    fun retryNow() {
        lock.read {
            if (handle != 0L) YumeNative.nativeRetryNow(handle)
        }
    }

    fun status(): YumeStatus? = lock.read {
        if (handle == 0L) return null
        val numbers = LongArray(YumeNative.STATUS_NUMBERS)
        val texts = arrayOfNulls<String>(2)
        if (YumeNative.nativeStatus(handle, numbers, texts) != YumeStatusCode.OK) return null
        YumeStatus(
            state = numbers[0].toInt(),
            session = numbers[1].toInt(),
            lastFailure = numbers[2].toInt(),
            epochBytes = numbers[3],
            connectedMs = numbers[4],
            retryMs = numbers[5],
            sessions = numbers[6],
            failedAttempts = numbers[7],
            idleEpochRotation = numbers[8] != 0L,
            payloadBytesSent = numbers[9],
            payloadBytesReceived = numbers[10],
            recordBytesSent = numbers[11],
            recordBytesReceived = numbers[12],
            maxQueuedBytes = numbers[13],
            maxEpochBytes = numbers[14],
            creditReturnsPerWindow = numbers[15].toInt(),
            tcpConnections = numbers[16].toInt(),
            udpDestinations = numbers[17].toInt(),
            peerFingerprint = texts[0].orEmpty(),
            lastFailureMessage = texts[1].orEmpty(),
        )
    }

    /** The oldest kept line numbered above [after], or null when there is none yet. */
    fun readMessage(after: Long): YumeMessage? = lock.read {
        if (handle == 0L) return null
        val numbers = LongArray(YumeNative.MESSAGE_NUMBERS)
        val texts = arrayOfNulls<String>(2)
        if (YumeNative.nativeReadMessage(handle, after, numbers, texts) != YumeStatusCode.OK) return null
        YumeMessage(
            seq = numbers[0],
            missed = numbers[1],
            timeUnixMs = numbers[2],
            instance = texts[0].orEmpty(),
            text = texts[1].orEmpty(),
        )
    }

    override fun close() {
        stop()
        lock.write {
            val closing = handle
            handle = 0L
            if (closing != 0L) YumeNative.nativeDestroy(closing)
        }
    }

    private inline fun call(body: (Long, Array<String?>) -> Int) {
        lock.read {
            if (handle == 0L) {
                throw YumeException(
                    YumeStatusCode.INVALID_STATE,
                    YumeLibrary.statusName(YumeStatusCode.INVALID_STATE),
                    "",
                    "the client is closed",
                )
            }
            val result = arrayOfNulls<String>(YumeNative.RESULT_SIZE)
            val status = body(handle, result)
            if (status != YumeStatusCode.OK) throw YumeLibrary.failure(status, result)
        }
    }

    companion object {
        /**
         * Creates a client from a schema-1 document. Credential references in
         * it resolve against [baseDir]. [protector] is called for each
         * outbound socket until [close] returns.
         */
        fun create(baseDir: File, config: ByteArray, protector: SocketProtector): YumeClient {
            val status = IntArray(1)
            val result = arrayOfNulls<String>(YumeNative.RESULT_SIZE)
            val handle = YumeNative.nativeCreate(baseDir.absolutePath, config, protector, status, result)
            if (handle == 0L || status[0] != YumeStatusCode.OK) {
                if (handle != 0L) YumeNative.nativeDestroy(handle)
                throw YumeLibrary.failure(
                    if (status[0] == YumeStatusCode.OK) YumeStatusCode.INVALID_STATE else status[0],
                    result,
                )
            }
            return YumeClient(handle)
        }
    }
}
