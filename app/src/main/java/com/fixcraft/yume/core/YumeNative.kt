package com.fixcraft.yume.core

import androidx.annotation.Keep

/** Called on a libyume thread before an outbound socket connects. */
@Keep
fun interface SocketProtector {
    /** False fails the connection closed. */
    fun protect(descriptor: Int): Boolean
}

/** Receives each file of an opened kit, in path order. */
@Keep
fun interface KitSink {
    fun add(path: String, executable: Boolean, bytes: ByteArray)
}

/**
 * The JNI entries of `yume_android_jni.cpp`, which registers them by name and
 * signature. Every entry returns a `yume_status` code unless it says
 * otherwise, and writes a failure's JSON pointer and text into `result`.
 */
@Keep
internal object YumeNative {
    const val RESULT_SIZE = 2
    const val STATUS_NUMBERS = 18
    const val MESSAGE_NUMBERS = 3

    // Keep this wire-shaped JNI buffer self-documenting. These indexes mirror
    // yume_endpoint_status and are intentionally pinned by the native adapter.
    const val STATUS_STATE = 0
    const val STATUS_SESSION = 1
    const val STATUS_LAST_FAILURE = 2
    const val STATUS_EPOCH_BYTES = 3
    const val STATUS_CONNECTED_MS = 4
    const val STATUS_RETRY_MS = 5
    const val STATUS_SESSIONS = 6
    const val STATUS_FAILED_ATTEMPTS = 7
    const val STATUS_IDLE_EPOCH_ROTATION = 8
    const val STATUS_PAYLOAD_SENT = 9
    const val STATUS_PAYLOAD_RECEIVED = 10
    const val STATUS_RECORD_SENT = 11
    const val STATUS_RECORD_RECEIVED = 12
    const val STATUS_MAX_QUEUED = 13
    const val STATUS_MAX_EPOCH = 14
    const val STATUS_CREDIT_RETURNS = 15
    const val STATUS_TCP_CONNECTIONS = 16
    const val STATUS_UDP_DESTINATIONS = 17
    const val MESSAGE_SEQUENCE = 0
    const val MESSAGE_MISSED = 1
    const val MESSAGE_TIME = 2

    init {
        System.loadLibrary("yume")
        System.loadLibrary("yumeandroid")
    }

    @JvmStatic external fun nativeCompatibility(): Array<String?>?
    @JvmStatic external fun nativeStatusName(code: Int): String
    @JvmStatic external fun nativeCheckConfig(json: ByteArray, role: IntArray, result: Array<String?>): Int
    @JvmStatic external fun nativeValidateClientCredentials(baseDir: String, json: ByteArray, fingerprint: ByteArray, result: Array<String?>): Int
    @JvmStatic external fun nativeOpenKit(sealed: ByteArray, code: ByteArray, sink: KitSink, result: Array<String?>): Int
    @JvmStatic external fun nativeCreate(
        baseDir: String,
        json: ByteArray,
        protector: SocketProtector?,
        status: IntArray,
        result: Array<String?>,
    ): Long
    @JvmStatic external fun nativeSetDevice(
        handle: Long,
        descriptor: Int,
        mtu: Int,
        streamService: String?,
        packetService: String?,
        ipv4Address: String?,
        ipv4Peer: String?,
        ipv6Address: String?,
        ipv6Peer: String?,
        result: Array<String?>,
    ): Int
    @JvmStatic external fun nativeStart(handle: Long, timeoutMs: Int, result: Array<String?>): Int
    @JvmStatic external fun nativeStop(handle: Long): Int
    @JvmStatic external fun nativeRetryNow(handle: Long): Int
    @JvmStatic external fun nativeStatus(handle: Long, numbers: LongArray, texts: Array<String?>): Int
    @JvmStatic external fun nativeReadMessage(handle: Long, after: Long, numbers: LongArray, texts: Array<String?>): Int
    @JvmStatic external fun nativeDestroy(handle: Long)
}
