package com.fixcraft.yume.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class YumePacketBulkTest {
    @Test
    fun batchRoundTrips() {
        val first = byteArrayOf(0x45, 0x00, 0x00, 0x14)
        val second = byteArrayOf(0x60, 0x00, 0x00, 0x00, 0x00)
        val batch = YumePacketBulk.Batch(
            sequence = 42L,
            flags = 3,
            packets = listOf(first, second),
        )

        val encoded = YumePacketBulk.encode(batch)
        val decoded = YumePacketBulk.decode(encoded)

        assertEquals(YumePacketBulk.encodedSize(batch), encoded.size)
        assertEquals(42L, decoded.sequence)
        assertEquals(3, decoded.flags)
        assertEquals(2, decoded.packets.size)
        assertArrayEquals(first, decoded.packets[0])
        assertArrayEquals(second, decoded.packets[1])
    }

    @Test
    fun canAppendPacketTracksBatchLimits() {
        assertTrue(YumePacketBulk.canAppendPacket(0, 0, 1500))
        assertFalse(
            YumePacketBulk.canAppendPacket(
                currentEncodedBytes = YumePacketBulk.HEADER_BYTES,
                currentPacketCount = YumePacketBulk.MAX_PACKETS_PER_BATCH,
                nextPacketBytes = 1,
            )
        )
        assertFalse(YumePacketBulk.canAppendPacket(0, 0, YumePacketBulk.MAX_PACKET_BYTES + 1))
    }

    @Test
    fun malformedBatchIsRejected() {
        val encoded = YumePacketBulk.encode(
            YumePacketBulk.Batch(
                sequence = 1L,
                packets = listOf(byteArrayOf(0x45, 0x00, 0x00, 0x14)),
            )
        )

        try {
            YumePacketBulk.decode(encoded.copyOf(encoded.size - 1))
            fail("truncated packet-bulk payload decoded successfully")
        } catch (_: YumeTransportException) {
        }
    }
}
