/*
 * YUME - Yume Universal Multiprotocol Engine
 * Copyright (C) 2026  FixCraft Inc.
 * Licensed under the GNU General Public License v3.0.
 */

package com.fixcraft.yume.transport

import android.util.Log

/**
 * Thin Kotlin wrapper over libyumetransport.so. Provides a non-blocking
 * TLS pipe: writes return immediately after queueing, reads block until
 * data arrives. Replaces SSLSocket.outputStream.write() on the upload
 * hot path so the writer coroutine never blocks on kernel TCP buffer
 * fill.
 *
 * Lifecycle:
 *   - [tryLoad] is a one-shot probe — call it once at app start. If the
 *     .so isn't present (older device, dropped ABI), it returns false
 *     and all `create()` callers should fall back to SSLSocket.
 *   - [create] allocates a native handle; the caller owns it.
 *   - [start] takes ownership of an already-connected protected TCP
 *     fd. After start() succeeds, the worker thread is running.
 *   - [write] / [read] are the I/O fast path.
 *   - [close] sends close_notify and joins the worker thread.
 *   - [destroy] frees the native state. Must be called exactly once
 *     after close().
 */
object YumeNativeTlsPipe {
    private const val TAG = "YumeNativeTls"

    @Volatile
    private var loaded: Boolean = false

    @Volatile
    private var loadError: String? = null

    /**
     * One-shot probe. Idempotent. Safe to call from any thread. Returns
     * true if libyumetransport.so loaded successfully — caller should
     * then use [create] etc.; if false, fall back to the Kotlin
     * SSLSocket path. Failure is silent (returns false) to keep the
     * app boot path resilient when the native lib is missing for the
     * current ABI.
     */
    fun tryLoad(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("yumetransport")
                loaded = true
                loadError = null
                Log.i(TAG, "libyumetransport.so loaded — OpenSSL ${openSslVersion()}")
                true
            } catch (throwable: Throwable) {
                loadError = throwable.message ?: throwable::class.java.simpleName
                Log.w(TAG, "libyumetransport.so unavailable: $loadError")
                false
            }
        }
    }

    val isLoaded: Boolean get() = loaded
    val loadFailureReason: String? get() = loadError

    fun openSslVersion(): String =
        if (loaded) nativeOpenSslVersion() else ""

    /**
     * Allocates a native handle pre-configured with TLS context (trust
     * anchors, SNI, ALPN). The pipe is NOT yet attached to a socket —
     * call [start] with a connected fd to do the handshake.
     *
     * @param sniHost            server hostname for SNI + cert hostname verify
     * @param trustPemConcat     PEM-concatenated CA bundle; can include
     *                           system roots + the anonym CA chain. Empty
     *                           string means rely only on system trust.
     * @param useSystemTrust     additionally trust /system/etc/security/cacerts
     * @param alpn               single ALPN protocol (e.g. "http/1.1"),
     *                           empty for no ALPN
     */
    fun create(
        sniHost: String,
        trustPemConcat: String,
        useSystemTrust: Boolean,
        alpn: String,
    ): Long {
        require(loaded) { "libyumetransport.so not loaded; call tryLoad() first" }
        val handle = nativeCreate(sniHost, trustPemConcat, useSystemTrust, alpn)
        if (handle == 0L) error("nativeCreate returned a null handle")
        return handle
    }

    /**
     * Takes ownership of `fd` and drives the TLS handshake to completion.
     * On success the worker thread runs the post-handshake I/O loop;
     * the fd is closed by native code when the pipe is destroyed.
     *
     * Throws RuntimeException on handshake failure; the caller is then
     * responsible for closing `fd` itself before calling [destroy].
     */
    fun start(handle: Long, fd: Int, handshakeTimeoutMs: Int) {
        val rc = nativeStart(handle, fd, handshakeTimeoutMs)
        check(rc == 0) { "nativeStart returned $rc" }
    }

    /**
     * Enqueues plaintext bytes; returns immediately. Returns [length]
     * on success, -1 on transport error. Backpressure is internal:
     * native side enforces a 4 MiB pending-write cap; if exceeded the
     * call returns -1 and the caller should treat it as transport
     * failure (the frame boundary is broken at that point).
     */
    fun write(handle: Long, data: ByteArray, offset: Int, length: Int): Int {
        return nativeWrite(handle, data, offset, length)
    }

    /**
     * Blocks for up to [timeoutMs] (negative = wait forever, 0 = poll)
     * waiting for at least one plaintext byte. Returns positive byte
     * count on success, 0 on timeout, -1 on EOF, -2 on error (a
     * RuntimeException with the OpenSSL error string is thrown).
     */
    fun read(
        handle: Long,
        dst: ByteArray,
        offset: Int,
        maxLength: Int,
        timeoutMs: Int,
    ): Int {
        return nativeRead(handle, dst, offset, maxLength, timeoutMs)
    }

    fun close(handle: Long) = nativeClose(handle)
    fun destroy(handle: Long) = nativeDestroy(handle)

    fun tlsProtocol(handle: Long): String = nativeGetTlsProtocol(handle)
    fun tlsCipherSuite(handle: Long): String = nativeGetTlsCipherSuite(handle)
    fun alpnSelected(handle: Long): String = nativeGetAlpnSelected(handle)
    fun peerCertCount(handle: Long): Int = nativeGetPeerCertCount(handle)
    fun peerCertDer(handle: Long, index: Int): ByteArray? =
        nativeGetPeerCertDer(handle, index)

    @JvmStatic external fun nativeCreate(
        sniHost: String,
        trustPemConcat: String,
        useSystemTrust: Boolean,
        alpn: String,
    ): Long
    @JvmStatic external fun nativeStart(handle: Long, fd: Int, handshakeTimeoutMs: Int): Int
    @JvmStatic external fun nativeWrite(handle: Long, data: ByteArray, offset: Int, length: Int): Int
    @JvmStatic external fun nativeRead(handle: Long, dst: ByteArray, offset: Int, maxLength: Int, timeoutMs: Int): Int
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeGetTlsProtocol(handle: Long): String
    @JvmStatic external fun nativeGetTlsCipherSuite(handle: Long): String
    @JvmStatic external fun nativeGetAlpnSelected(handle: Long): String
    @JvmStatic external fun nativeGetPeerCertCount(handle: Long): Int
    @JvmStatic external fun nativeGetPeerCertDer(handle: Long, index: Int): ByteArray?
    @JvmStatic external fun nativeOpenSslVersion(): String
}
