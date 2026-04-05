package com.fixcraft.yume.vpn

import java.util.Locale

internal object YumeDnsRouting {
    fun buildRouteKey(
        resolverHost: String,
        payload: ByteArray,
    ): String {
        val normalizedResolver = normalizeHost(resolverHost)
        if (payload.size < DNS_HEADER_BYTES) {
            return "$normalizedResolver|invalid"
        }
        val transactionId = readU16(payload, 0)
        val question = parseFirstQuestion(payload)
        return if (question == null) {
            "$normalizedResolver|$transactionId"
        } else {
            "$normalizedResolver|$transactionId|${question.name}|${question.type}|${question.clazz}"
        }
    }

    private fun parseFirstQuestion(payload: ByteArray): DnsQuestion? {
        if (payload.size < DNS_HEADER_BYTES) {
            return null
        }
        val questionCount = readU16(payload, 4)
        if (questionCount <= 0) {
            return null
        }
        val (name, offsetAfterName) = readName(payload, DNS_HEADER_BYTES) ?: return null
        if (offsetAfterName + 4 > payload.size) {
            return null
        }
        return DnsQuestion(
            name = name,
            type = readU16(payload, offsetAfterName),
            clazz = readU16(payload, offsetAfterName + 2),
        )
    }

    private fun readName(
        payload: ByteArray,
        start: Int,
        depth: Int = 0,
    ): Pair<String, Int>? {
        if (start !in payload.indices || depth > MAX_POINTER_DEPTH) {
            return null
        }

        var offset = start
        val labels = mutableListOf<String>()
        var terminalOffset = -1

        while (offset < payload.size) {
            val length = payload[offset].toInt() and 0xFF
            offset += 1
            when {
                length == 0 -> {
                    if (terminalOffset < 0) {
                        terminalOffset = offset
                    }
                    break
                }

                (length and POINTER_MASK) == POINTER_MASK -> {
                    if (offset >= payload.size) {
                        return null
                    }
                    val pointer = ((length and POINTER_VALUE_MASK) shl 8) or (payload[offset].toInt() and 0xFF)
                    offset += 1
                    if (terminalOffset < 0) {
                        terminalOffset = offset
                    }
                    val resolved = readName(payload, pointer, depth + 1) ?: return null
                    if (resolved.first.isNotEmpty()) {
                        labels += resolved.first.split('.')
                    }
                    break
                }

                offset + length > payload.size -> return null
                else -> {
                    labels += String(payload, offset, length, Charsets.UTF_8).lowercase(Locale.ROOT)
                    offset += length
                }
            }
        }

        if (terminalOffset < 0) {
            return null
        }
        return labels.joinToString(".") to terminalOffset
    }

    private fun normalizeHost(host: String): String {
        return host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .lowercase(Locale.ROOT)
    }

    private fun readU16(
        payload: ByteArray,
        offset: Int,
    ): Int {
        return ((payload[offset].toInt() and 0xFF) shl 8) or (payload[offset + 1].toInt() and 0xFF)
    }

    private data class DnsQuestion(
        val name: String,
        val type: Int,
        val clazz: Int,
    )

    private const val DNS_HEADER_BYTES = 12
    private const val POINTER_MASK = 0xC0
    private const val POINTER_VALUE_MASK = 0x3F
    private const val MAX_POINTER_DEPTH = 8
}
