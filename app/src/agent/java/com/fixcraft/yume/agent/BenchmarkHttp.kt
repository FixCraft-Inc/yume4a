package com.fixcraft.yume.agent

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.SocketTimeoutException

/** A bounded HTTP/1 response reader for the controlled benchmark endpoint. */
internal object BenchmarkHttp {
    data class Result(val status: Int, val bytes: Long, val firstByteAtMs: Long?)

    fun read(input: InputStream, maxBytes: Long, deadlineMs: Long, now: () -> Long): Result {
        val stream = BufferedInputStream(input, 64 * 1024)
        var headerBytes = 0
        fun remaining() {
            if (now() >= deadlineMs) throw SocketTimeoutException("Benchmark deadline exceeded")
        }
        fun line(): String {
            val result = StringBuilder()
            while (true) {
                remaining()
                val byte = stream.read()
                if (byte < 0) throw EOFException("Incomplete HTTP response")
                require(++headerBytes <= 64 * 1024) { "HTTP headers are too large" }
                if (byte == '\n'.code) {
                    require(result.endsWith("\r")) { "Malformed HTTP line" }
                    return result.dropLast(1).toString()
                }
                require(byte == '\r'.code || byte == '\t'.code || byte in 32..126) { "Malformed HTTP header" }
                result.append(byte.toChar())
            }
        }
        val statusLine = line().split(' ', limit = 3)
        require(statusLine.size >= 2 && statusLine[0] in setOf("HTTP/1.0", "HTTP/1.1")) { "Invalid HTTP status" }
        val status = statusLine[1].toIntOrNull() ?: error("Invalid HTTP status")
        require(status in 200..299) { "Benchmark endpoint returned HTTP $status" }
        val headers = mutableMapOf<String, String>()
        while (true) {
            val value = line()
            if (value.isEmpty()) break
            val separator = value.indexOf(':')
            require(separator > 0) { "Invalid HTTP header" }
            val name = value.substring(0, separator).lowercase(java.util.Locale.ROOT)
            require(name.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) { "Invalid HTTP header name" }
            if (name in setOf("content-length", "transfer-encoding", "content-encoding")) {
                require(headers.put(name, value.substring(separator + 1).trim()) == null) { "Duplicate HTTP framing header" }
            }
        }
        require(headers["content-encoding"].let { it == null || it.equals("identity", true) }) { "Compressed benchmark response" }
        val transfer = headers["transfer-encoding"]
        require(transfer == null || transfer.equals("chunked", true)) { "Unsupported HTTP transfer encoding" }
        require(transfer == null || "content-length" !in headers) { "Ambiguous HTTP framing" }
        val contentLength = headers["content-length"]?.let {
            require(it.isNotEmpty() && it.all(Char::isDigit)) { "Invalid HTTP content length" }
            it.toLongOrNull()?.takeIf { length -> length >= 0 } ?: error("Invalid HTTP content length")
        }
        var total = 0L
        var firstByte: Long? = null
        val buffer = ByteArray(64 * 1024)
        fun body(count: Long, exact: Boolean) {
            var left = minOf(count, maxBytes - total)
            while (left > 0) {
                remaining()
                val received = stream.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                remaining()
                if (received < 0) {
                    if (exact) throw EOFException("Incomplete HTTP body")
                    break
                }
                if (received == 0) continue
                if (firstByte == null) firstByte = now()
                total += received
                left -= received
            }
        }
        try {
            if (transfer != null) {
                while (total < maxBytes) {
                    val chunk = line().substringBefore(';')
                    require(chunk.isNotEmpty() && chunk.all { it in "0123456789abcdefABCDEF" }) { "Invalid HTTP chunk" }
                    val size = chunk.toLongOrNull(16) ?: error("HTTP chunk is too large")
                    if (size == 0L) {
                        while (line().isNotEmpty()) { /* bounded trailers */ }
                        break
                    }
                    body(size, exact = true)
                    if (total == maxBytes) break
                    require(line().isEmpty()) { "Invalid HTTP chunk terminator" }
                }
            } else {
                body(contentLength ?: maxBytes, exact = contentLength != null)
            }
            remaining()
            return Result(status, total, firstByte)
        } finally {
            buffer.fill(0)
        }
    }
}
