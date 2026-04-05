package com.fixcraft.yume.vpn

import java.util.Locale

internal object YumeTrafficPolicy {
    private val dedicatedEncryptedDnsPorts = setOf(853)

    fun isEncryptedDnsBypass(host: String, port: Int, dnsServers: List<String>): Boolean {
        if (port !in dedicatedEncryptedDnsPorts) {
            return false
        }
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isEmpty()) {
            return false
        }
        return dnsServers
            .asSequence()
            .map(::normalizeHost)
            .filter { it.isNotEmpty() }
            .any { it == normalizedHost }
    }

    private fun normalizeHost(host: String): String {
        return host
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .lowercase(Locale.ROOT)
    }
}
