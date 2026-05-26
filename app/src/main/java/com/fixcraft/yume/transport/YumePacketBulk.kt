package com.fixcraft.yume.transport

internal object YumePacketBulk {
    const val CAPABILITY = "packet_bulk_v1"
    const val OPEN_PROTO = "packet-bulk-v1"
    const val MAGIC = 0x59425031 // "YBP1"
    const val VERSION = 1
    const val HEADER_BYTES = 16
    const val PACKET_LENGTH_BYTES = 2
    const val MAX_PACKET_BYTES = 65_535
    const val MAX_PACKETS_PER_BATCH = 64
    const val DEFAULT_MAX_BATCH_BYTES = 128 * 1024
    const val DEFAULT_FLUSH_DELAY_MICROS = 2_000

    private const val MAX_SEQUENCE = Long.MAX_VALUE

    data class Batch(
        val sequence: Long,
        val flags: Int = 0,
        val packets: List<ByteArray>,
    )

    fun encodedSize(batch: Batch): Int {
        validateBatch(batch)
        var total = HEADER_BYTES
        for (packet in batch.packets) {
            total = Math.addExact(total, PACKET_LENGTH_BYTES + packet.size)
        }
        return total
    }

    fun canAppendPacket(
        currentEncodedBytes: Int,
        currentPacketCount: Int,
        nextPacketBytes: Int,
        maxEncodedBytes: Int = DEFAULT_MAX_BATCH_BYTES,
    ): Boolean {
        if (currentPacketCount >= MAX_PACKETS_PER_BATCH || nextPacketBytes <= 0 || nextPacketBytes > MAX_PACKET_BYTES) {
            return false
        }
        val base = if (currentPacketCount == 0) HEADER_BYTES else currentEncodedBytes
        if (base < HEADER_BYTES || maxEncodedBytes < HEADER_BYTES) {
            return false
        }
        return base <= maxEncodedBytes - PACKET_LENGTH_BYTES - nextPacketBytes
    }

    fun encode(batch: Batch): ByteArray {
        val size = encodedSize(batch)
        val out = ByteArray(size)
        var offset = 0
        putBe32(out, offset, MAGIC)
        offset += 4
        out[offset++] = VERSION.toByte()
        out[offset++] = (batch.flags and 0xFF).toByte()
        putBe16(out, offset, batch.packets.size)
        offset += 2
        putBe64(out, offset, batch.sequence)
        offset += 8
        for (packet in batch.packets) {
            putBe16(out, offset, packet.size)
            offset += 2
            packet.copyInto(out, destinationOffset = offset)
            offset += packet.size
        }
        return out
    }

    fun decode(payload: ByteArray): Batch {
        if (payload.size < HEADER_BYTES) {
            throw YumeTransportException("Packet-bulk payload is shorter than the header")
        }
        if (readBe32(payload, 0) != MAGIC) {
            throw YumeTransportException("Packet-bulk magic mismatch")
        }
        if ((payload[4].toInt() and 0xFF) != VERSION) {
            throw YumeTransportException("Packet-bulk version mismatch")
        }
        val flags = payload[5].toInt() and 0xFF
        val packetCount = readBe16(payload, 6)
        if (packetCount == 0 || packetCount > MAX_PACKETS_PER_BATCH) {
            throw YumeTransportException("Packet-bulk packet count is invalid")
        }
        val sequence = readBe64(payload, 8)
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw YumeTransportException("Packet-bulk sequence exceeds v1 range")
        }
        val packets = ArrayList<ByteArray>(packetCount)
        var offset = HEADER_BYTES
        repeat(packetCount) {
            if (offset + PACKET_LENGTH_BYTES > payload.size) {
                throw YumeTransportException("Packet-bulk packet length is truncated")
            }
            val packetLength = readBe16(payload, offset)
            offset += PACKET_LENGTH_BYTES
            if (packetLength == 0) {
                throw YumeTransportException("Packet-bulk packet is empty")
            }
            if (offset + packetLength > payload.size) {
                throw YumeTransportException("Packet-bulk packet body is truncated")
            }
            packets.add(payload.copyOfRange(offset, offset + packetLength))
            offset += packetLength
        }
        if (offset != payload.size) {
            throw YumeTransportException("Packet-bulk payload has trailing bytes")
        }
        return Batch(
            sequence = sequence,
            flags = flags,
            packets = packets,
        )
    }

    private fun validateBatch(batch: Batch) {
        require(batch.sequence >= 0 && batch.sequence <= MAX_SEQUENCE) { "packet-bulk sequence exceeds v1 range" }
        require(batch.flags in 0..0xFF) { "packet-bulk flags out of range" }
        require(batch.packets.isNotEmpty()) { "packet-bulk batch is empty" }
        require(batch.packets.size <= MAX_PACKETS_PER_BATCH) { "packet-bulk batch has too many packets" }
        for (packet in batch.packets) {
            require(packet.isNotEmpty()) { "packet-bulk packet is empty" }
            require(packet.size <= MAX_PACKET_BYTES) { "packet-bulk packet is too large" }
        }
    }

    private fun putBe16(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 1] = (value and 0xFF).toByte()
    }

    private fun putBe32(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun putBe64(out: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            out[offset + i] = ((value ushr (56 - i * 8)) and 0xFF).toByte()
        }
    }

    private fun readBe16(input: ByteArray, offset: Int): Int {
        return ((input[offset].toInt() and 0xFF) shl 8) or
            (input[offset + 1].toInt() and 0xFF)
    }

    private fun readBe32(input: ByteArray, offset: Int): Int {
        return ((input[offset].toInt() and 0xFF) shl 24) or
            ((input[offset + 1].toInt() and 0xFF) shl 16) or
            ((input[offset + 2].toInt() and 0xFF) shl 8) or
            (input[offset + 3].toInt() and 0xFF)
    }

    private fun readBe64(input: ByteArray, offset: Int): Long {
        var value = 0L
        for (i in 0 until 8) {
            value = (value shl 8) or (input[offset + i].toLong() and 0xFFL)
        }
        return value
    }
}
