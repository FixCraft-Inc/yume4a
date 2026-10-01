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

    init {
        System.loadLibrary("yume")
        System.loadLibrary("yumeandroid")
    }

    @JvmStatic external fun nativeCompatibility(): Array<String?>?
    @JvmStatic external fun nativeStatusName(code: Int): String
    @JvmStatic external fun nativeCheckConfig(json: ByteArray, role: IntArray, result: Array<String?>): Int
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
