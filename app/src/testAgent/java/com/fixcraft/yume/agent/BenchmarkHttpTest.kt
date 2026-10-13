package com.fixcraft.yume.agent

import java.io.ByteArrayInputStream
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BenchmarkHttpTest {
    private fun response(value: String, maxBytes: Long = 1024) = BenchmarkHttp.read(
        ByteArrayInputStream(value.toByteArray()), maxBytes, 1000, { 0 },
    )

    @Test fun fixedLengthAndChunkedCountOnlyBody() {
        assertEquals(4L, response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndata").bytes)
        assertEquals(4L, response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nda\r\n2\r\nta\r\n0\r\n\r\n").bytes)
        assertEquals(2L, response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\ndata", 2).bytes)
    }

    @Test fun rejectsAmbiguousFramingAndTruncation() {
        assertThrows(IllegalArgumentException::class.java) {
            response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\nTransfer-Encoding: chunked\r\n\r\n")
        }
        assertThrows(java.io.EOFException::class.java) {
            response("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\na")
        }
    }

    @Test fun absoluteDeadlineIncludesHeadersAndFinalRead() {
        assertThrows(SocketTimeoutException::class.java) {
            BenchmarkHttp.read(ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\nbody".toByteArray()), 4, 2) { 2 }
        }
        var time = 0L
        val input = object : ByteArrayInputStream("HTTP/1.1 200 OK\r\n\r\nbody".toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                time = 3
                return super.read(bytes, offset, length)
            }
        }
        assertThrows(SocketTimeoutException::class.java) { BenchmarkHttp.read(input, 4, 2) { time } }
    }
}
