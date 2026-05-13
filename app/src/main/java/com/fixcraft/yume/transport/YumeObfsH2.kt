package com.fixcraft.yume.transport

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * Kotlin port of the desktop client's HTTP/2 carrier obfs (see
 * src/core/obfs_h2.cpp + src/core/obfs_signal.cpp on the desktop tree).
 *
 * Sends a real Chrome-shaped H2 client preface + SETTINGS + WINDOW_UPDATE +
 * HEADERS frame on the cleartext side of an established TLS socket. The
 * `:path` is HMAC-derived from the SNI and the current hour, which lets the
 * server distinguish a YUME peer from a random web crawler.
 *
 * Replaces the legacy `CONNECT origin:port HTTP/1.1` preface that the new
 * desktop server no longer accepts.
 */
internal object YumeObfsH2 {

    const val TOKEN_HEX_LEN = 32        // 16 bytes HMAC truncated, hex-encoded
    const val NONCE_HEX_LEN = 16        // 8 random bytes hex
    const val PATH_LEN = 1 + TOKEN_HEX_LEN + 1 + NONCE_HEX_LEN  // /<token>/<nonce>

    private const val HOUR_SECONDS = 3600L
    private const val PATH_SALT = "yume-obfs-path"
    private const val PATH_INFO = "yume-obfs-v2-K"
    private const val TOKEN_TAG = "yume-obfs-v2"

    fun deriveSignalKey(secret: String): ByteArray {
        val ikm = if (secret.isEmpty()) byteArrayOf(0) else secret.toByteArray(StandardCharsets.UTF_8)
        return hkdfSha256(
            ikm = ikm,
            salt = PATH_SALT.toByteArray(StandardCharsets.US_ASCII),
            info = PATH_INFO.toByteArray(StandardCharsets.US_ASCII),
            length = 32,
        )
    }

    fun derivePathToken(signalKey: ByteArray, sni: String, hourEpoch: Long): String {
        val msg = ByteArrayOutputStream(64).apply {
            write(sni.toByteArray(StandardCharsets.US_ASCII))
            write('|'.code)
            write(hourEpoch.toString().toByteArray(StandardCharsets.US_ASCII))
            write('|'.code)
            write(TOKEN_TAG.toByteArray(StandardCharsets.US_ASCII))
        }.toByteArray()
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(signalKey, "HmacSHA256"))
        }
        val tag = mac.doFinal(msg)
        return toHex(tag, 0, 16)
    }

    fun randomNonceHex(rng: SecureRandom = SecureRandom()): String {
        val raw = ByteArray(NONCE_HEX_LEN / 2)
        rng.nextBytes(raw)
        return toHex(raw, 0, raw.size)
    }

    fun buildPath(token: String, nonceHex: String): String =
        "/$token/$nonceHex"

    /**
     * Builds the full client-side handshake: preface + SETTINGS + WINDOW_UPDATE
     * + HEADERS frame on stream 1.
     */
    fun encodeClientHandshake(
        sni: String,
        path: String,
        userAgent: String,
    ): ByteArray {
        val out = ByteArrayOutputStream(512)

        // 1. H2 connection preface
        out.write(CLIENT_PREFACE)

        // 2. SETTINGS frame matching Chrome 135 defaults
        val settingsBody = ByteArrayOutputStream(30).apply {
            putBe16(1); putBe32(65536)         // HEADER_TABLE_SIZE
            putBe16(2); putBe32(0)             // ENABLE_PUSH
            putBe16(3); putBe32(1000)          // MAX_CONCURRENT_STREAMS
            putBe16(4); putBe32(6291456)       // INITIAL_WINDOW_SIZE
            putBe16(6); putBe32(262144)        // MAX_HEADER_LIST_SIZE
        }.toByteArray()
        appendFrameHeader(out, settingsBody.size, FRAME_SETTINGS, 0, 0)
        out.write(settingsBody)

        // 3. WINDOW_UPDATE on stream 0
        appendFrameHeader(out, 4, FRAME_WINDOW_UPDATE, 0, 0)
        out.putBe32(15663105)

        // 4. HEADERS frame on stream 1 with realistic Chrome-shaped headers
        val hb = ByteArrayOutputStream(256).apply {
            appendIndexed(this, 3)                                        // :method POST
            appendIndexed(this, 7)                                        // :scheme https
            appendLiteralIndexedName(this, 1, sni)                        // :authority
            appendLiteralIndexedName(this, 4, path)                       // :path
            appendLiteralIndexedName(this, 19, "application/grpc-web+proto")  // accept
            appendLiteralIndexedName(this, 22, "gzip, deflate, br, zstd")     // accept-encoding
            appendLiteralIndexedName(this, 23, "en-US,en;q=0.9")              // accept-language
            appendLiteralIndexedName(this, 50, "application/grpc-web+proto")  // content-type
            appendLiteralIndexedName(this, 58, userAgent)                     // user-agent
        }.toByteArray()
        appendFrameHeader(out, hb.size, FRAME_HEADERS, FLAG_END_HEADERS, 1)
        out.write(hb)

        return out.toByteArray()
    }

    /**
     * Stateless HPACK decoder that scans a HEADERS-frame block fragment for
     * the indexed-name {@code :path} entry. Used for round-trip self-tests
     * and (on the server side) to extract the client's path token; the
     * Android client only uses it for the inbound decoder below.
     */
    private fun scanHeadersBlock(block: ByteArray): String? {
        var pos = 0
        while (pos < block.size) {
            val b = block[pos].toInt() and 0xFF
            // Indexed entry — skip
            if ((b and 0x80) != 0) {
                val cons = decodeVarint(block, pos, 0x7F) ?: return null
                pos += cons.consumed
                continue
            }
            // Literal with incremental indexing — skip name+value
            if ((b and 0xC0) == 0x40) {
                val (idx, cn) = decodeVarint(block, pos, 0x3F) ?: return null
                pos += cn
                if (idx == 0L) {
                    pos += skipString(block, pos) ?: return null
                }
                pos += skipString(block, pos) ?: return null
                continue
            }
            // Dynamic table size update
            if ((b and 0xE0) == 0x20) {
                val cons = decodeVarint(block, pos, 0x1F) ?: return null
                pos += cons.consumed
                continue
            }
            // Literal without indexing OR never indexed
            val (idx, cn) = decodeVarint(block, pos, 0x0F) ?: return null
            pos += cn
            if (idx == 0L) {
                pos += skipString(block, pos) ?: return null
            }
            val (value, vc) = readString(block, pos) ?: return null
            pos += vc
            if (idx == 4L) return value
        }
        return null
    }

    /**
     * Tracks the H2 inbound state so the bootstrap can read past the server's
     * SETTINGS / SETTINGS-ACK / HEADERS reply and surface any bytes that
     * arrived after the HEADERS frame (typically the YUME AUTH challenge).
     */
    class H2InboundDecoder {
        private val inbound = ByteArrayOutputStream(512)
        private val replies = ByteArrayOutputStream(64)
        private var headersSeen: Boolean = false
        private var failed: String? = null

        val isFailed: Boolean get() = failed != null
        val errorMessage: String? get() = failed
        val sawHeaders: Boolean get() = headersSeen

        fun feed(data: ByteArray, off: Int = 0, len: Int = data.size) {
            if (failed != null) return
            inbound.write(data, off, len)
            processInbound()
        }

        fun pendingReplies(): ByteArray {
            val out = replies.toByteArray()
            replies.reset()
            return out
        }

        fun drainInbound(): ByteArray {
            val out = inbound.toByteArray()
            inbound.reset()
            return out
        }

        private fun processInbound() {
            while (true) {
                if (headersSeen) return
                val buf = inbound.toByteArray()
                if (buf.size < FRAME_HEADER_LEN) return
                val length = readBe24(buf, 0)
                val type = buf[3].toInt() and 0xFF
                val flags = buf[4].toInt() and 0xFF
                if (length > MAX_FRAME_LEN) {
                    failed = "frame length exceeds carrier max ($length)"
                    return
                }
                if (buf.size < FRAME_HEADER_LEN + length) return
                when (type) {
                    FRAME_SETTINGS -> {
                        if ((flags and FLAG_ACK) == 0) {
                            // Reply with SETTINGS-ACK
                            replies.write(buildFrameHeader(0, FRAME_SETTINGS, FLAG_ACK, 0))
                        }
                    }
                    FRAME_PING -> {
                        if (length == 8 && (flags and FLAG_ACK) == 0) {
                            val ackHeader = buildFrameHeader(8, FRAME_PING, FLAG_ACK, 0)
                            replies.write(ackHeader)
                            replies.write(buf, FRAME_HEADER_LEN, 8)
                        }
                    }
                    FRAME_HEADERS -> {
                        headersSeen = true
                    }
                    FRAME_WINDOW_UPDATE,
                    FRAME_PRIORITY,
                    FRAME_RST_STREAM,
                    FRAME_CONTINUATION -> Unit
                    FRAME_GOAWAY -> {
                        failed = "peer GOAWAY"
                        return
                    }
                    else -> Unit
                }
                consume(FRAME_HEADER_LEN + length)
                if (failed != null) return
            }
        }

        private fun consume(n: Int) {
            val rest = inbound.toByteArray().copyOfRange(n, inbound.size())
            inbound.reset()
            inbound.write(rest)
        }
    }

    // -----------------------------------------------------------------------
    // Encoding helpers
    // -----------------------------------------------------------------------

    private const val FRAME_HEADER_LEN = 9
    private const val MAX_FRAME_LEN = 16384 + 256

    private const val FRAME_DATA = 0x00
    private const val FRAME_HEADERS = 0x01
    private const val FRAME_PRIORITY = 0x02
    private const val FRAME_RST_STREAM = 0x03
    private const val FRAME_SETTINGS = 0x04
    private const val FRAME_PING = 0x06
    private const val FRAME_GOAWAY = 0x07
    private const val FRAME_WINDOW_UPDATE = 0x08
    private const val FRAME_CONTINUATION = 0x09

    private const val FLAG_ACK = 0x01
    private const val FLAG_END_HEADERS = 0x04

    private val CLIENT_PREFACE: ByteArray =
        "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)

    private fun appendFrameHeader(
        out: ByteArrayOutputStream,
        length: Int,
        type: Int,
        flags: Int,
        streamId: Int,
    ) {
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(type and 0xFF)
        out.write(flags and 0xFF)
        out.putBe32(streamId and 0x7FFFFFFF)
    }

    private fun buildFrameHeader(length: Int, type: Int, flags: Int, streamId: Int): ByteArray {
        val out = ByteArrayOutputStream(FRAME_HEADER_LEN)
        appendFrameHeader(out, length, type, flags, streamId)
        return out.toByteArray()
    }

    private fun appendIndexed(out: ByteArrayOutputStream, idx: Int) {
        out.write(0x80 or (idx and 0x7F))
    }

    private fun appendLiteralIndexedName(
        out: ByteArrayOutputStream,
        idx: Int,
        value: String,
    ) {
        if (idx < 16) {
            out.write(idx and 0x0F)
        } else {
            out.write(0x0F)
            var rem = idx - 15
            while (rem >= 0x80) {
                out.write((rem and 0x7F) or 0x80)
                rem = rem ushr 7
            }
            out.write(rem)
        }
        appendString(out, value)
    }

    private fun appendString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size <= 0x7F) {
            out.write(bytes.size and 0x7F)
            out.write(bytes)
        } else {
            out.write(0x7F)
            var rem = bytes.size - 0x7F
            while (rem >= 0x80) {
                out.write((rem and 0x7F) or 0x80)
                rem = rem ushr 7
            }
            out.write(rem)
            out.write(bytes)
        }
    }

    // -----------------------------------------------------------------------
    // HPACK helpers (decoder side, unused by client but needed for self-test)
    // -----------------------------------------------------------------------

    private data class VarintResult(val value: Long, val consumed: Int)

    private fun decodeVarint(buf: ByteArray, off: Int, prefixMask: Int): VarintResult? {
        if (off >= buf.size) return null
        var v = (buf[off].toInt() and prefixMask).toLong()
        var consumed = 1
        if (v < prefixMask) return VarintResult(v, consumed)
        var shift = 0
        while (off + consumed < buf.size) {
            val b = buf[off + consumed].toInt() and 0xFF
            consumed += 1
            v += (b and 0x7F).toLong() shl shift
            if ((b and 0x80) == 0) return VarintResult(v, consumed)
            shift += 7
            if (shift >= 56) return null
        }
        return null
    }

    private fun skipString(buf: ByteArray, off: Int): Int? {
        val rs = readString(buf, off) ?: return null
        return rs.second
    }

    private fun readString(buf: ByteArray, off: Int): Pair<String, Int>? {
        if (off >= buf.size) return null
        val huffman = (buf[off].toInt() and 0x80) != 0
        val lenRes = decodeVarint(buf, off, 0x7F) ?: return null
        val totalConsumed = lenRes.consumed + lenRes.value.toInt()
        if (off + totalConsumed > buf.size) return null
        if (huffman) return null  // we never emit huffman-encoded headers
        val s = String(buf, off + lenRes.consumed, lenRes.value.toInt(), StandardCharsets.US_ASCII)
        return Pair(s, totalConsumed)
    }

    // -----------------------------------------------------------------------
    // Byte helpers
    // -----------------------------------------------------------------------

    private fun ByteArrayOutputStream.putBe16(v: Int) {
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.putBe32(v: Int) {
        write((v ushr 24) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 8) and 0xFF)
        write(v and 0xFF)
    }

    private fun readBe24(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF) shl 16) or
            ((buf[off + 1].toInt() and 0xFF) shl 8) or
            (buf[off + 2].toInt() and 0xFF)
    }

    private fun toHex(bytes: ByteArray, off: Int, len: Int): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(len * 2)
        for (i in 0 until len) {
            val b = bytes[off + i].toInt() and 0xFF
            sb.append(hex[(b ushr 4) and 0x0F])
            sb.append(hex[b and 0x0F])
        }
        return sb.toString()
    }

    // HKDF-SHA256 (RFC 5869) — extract + expand. Pure JCA, no BC dependency.
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // Extract
        val saltKey = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltKey, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        // Expand
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter: Byte = 1
        while (pos < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val chunk = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, chunk)
            pos += chunk
            counter = (counter.toInt() + 1).toByte()
        }
        return out
    }
}
