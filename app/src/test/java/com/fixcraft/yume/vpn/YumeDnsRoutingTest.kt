package com.fixcraft.yume.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class YumeDnsRoutingTest {
    @Test
    fun routeKeyIncludesResolverTransactionAndQuestion() {
        val payload = buildDnsQuery(
            transactionId = 0x1234,
            name = "WWW.Example.COM",
            type = 1,
        )

        val key = YumeDnsRouting.buildRouteKey("204.12.233.121", payload)

        assertEquals("204.12.233.121|4660|www.example.com|1|1", key)
    }

    @Test
    fun routeKeyFallsBackToTransactionIdForMalformedQuestion() {
        val payload = byteArrayOf(
            0x12, 0x34,
            0x01, 0x00,
            0x00, 0x01,
            0x00, 0x00,
            0x00, 0x00,
            0x00, 0x00,
            0x03, 'w'.code.toByte(),
        )

        val key = YumeDnsRouting.buildRouteKey("Resolver.Example", payload)

        assertEquals("resolver.example|4660", key)
    }

    private fun buildDnsQuery(
        transactionId: Int,
        name: String,
        type: Int,
    ): ByteArray {
        val labels = name.split('.')
        val question = buildList<Byte> {
            labels.forEach { label ->
                add(label.length.toByte())
                label.forEach { char -> add(char.code.toByte()) }
            }
            add(0x00)
            add(((type ushr 8) and 0xFF).toByte())
            add((type and 0xFF).toByte())
            add(0x00)
            add(0x01)
        }.toByteArray()

        return byteArrayOf(
            ((transactionId ushr 8) and 0xFF).toByte(),
            (transactionId and 0xFF).toByte(),
            0x01,
            0x00,
            0x00,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
        ) + question
    }
}
