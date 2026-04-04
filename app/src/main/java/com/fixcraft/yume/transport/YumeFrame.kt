package com.fixcraft.yume.transport

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

enum class YumeFrameType(val wireValue: Int) {
    AUTH(1),
    OPEN(2),
    DATA(3),
    CLOSE(4),
    EXEC(5),
    ANON(6),
    RLISTEN(7),
    ROPEN(8),
    PING(9),
    PONG(10),
    CONTROL(11),
    SOPEN(12);

    companion object {
        fun fromWireValue(value: Int): YumeFrameType? = entries.firstOrNull { it.wireValue == value }
    }
}

data class YumeFrame(
    val type: YumeFrameType,
    val streamId: Int = 0,
    val flags: Int = 0,
    val payload: ByteArray = ByteArray(0),
)

object YumeFrameCodec {
    private const val HEADER_SIZE = 8
    private const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    fun readFrame(input: InputStream): YumeFrame {
        val header = readExact(input, HEADER_SIZE)
        val payloadLength = ((header[0].toInt() and 0xFF) shl 24) or
            ((header[1].toInt() and 0xFF) shl 16) or
            ((header[2].toInt() and 0xFF) shl 8) or
            (header[3].toInt() and 0xFF)
        if (payloadLength < 0 || payloadLength > MAX_PAYLOAD_BYTES) {
            throw YumeTransportException("Invalid Yume frame length: $payloadLength")
        }
        val frameType = YumeFrameType.fromWireValue(header[4].toInt() and 0xFF)
            ?: throw YumeTransportException("Unknown Yume frame type: ${header[4].toInt() and 0xFF}")
        val streamId = header[5].toInt() and 0xFF
        val flags = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
        val payload = if (payloadLength == 0) {
            ByteArray(0)
        } else {
            readExact(input, payloadLength)
        }
        return YumeFrame(
            type = frameType,
            streamId = streamId,
            flags = flags,
            payload = payload,
        )
    }

    fun writeFrame(output: OutputStream, frame: YumeFrame) {
        output.write(encodeFrame(frame))
    }

    fun encodeFrame(frame: YumeFrame): ByteArray {
        return encodeFrame(
            type = frame.type,
            streamId = frame.streamId,
            flags = frame.flags,
            payload = frame.payload,
        )
    }

    fun encodeFrame(
        type: YumeFrameType,
        streamId: Int = 0,
        flags: Int = 0,
        payload: ByteArray = ByteArray(0),
        payloadOffset: Int = 0,
        payloadLength: Int = payload.size,
    ): ByteArray {
        require(streamId in 0..0xFF) { "streamId out of range" }
        require(flags in 0..0xFFFF) { "flags out of range" }
        require(payloadOffset in 0..payload.size) { "payloadOffset out of range" }
        require(payloadLength >= 0 && payloadOffset + payloadLength <= payload.size) { "payloadLength out of range" }
        require(payloadLength <= MAX_PAYLOAD_BYTES) { "payload too large" }

        val encoded = ByteArray(HEADER_SIZE + payloadLength)
        encoded[0] = ((payloadLength ushr 24) and 0xFF).toByte()
        encoded[1] = ((payloadLength ushr 16) and 0xFF).toByte()
        encoded[2] = ((payloadLength ushr 8) and 0xFF).toByte()
        encoded[3] = (payloadLength and 0xFF).toByte()
        encoded[4] = (type.wireValue and 0xFF).toByte()
        encoded[5] = (streamId and 0xFF).toByte()
        encoded[6] = ((flags ushr 8) and 0xFF).toByte()
        encoded[7] = (flags and 0xFF).toByte()
        if (payloadLength > 0) {
            payload.copyInto(
                destination = encoded,
                destinationOffset = HEADER_SIZE,
                startIndex = payloadOffset,
                endIndex = payloadOffset + payloadLength,
            )
        }
        return encoded
    }

    private fun readExact(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(out, offset, length - offset)
            if (read < 0) {
                throw EOFException("Reached EOF while reading Yume frame")
            }
            offset += read
        }
        return out
    }
}

class YumeTransportException(message: String, cause: Throwable? = null) : Exception(message, cause)
