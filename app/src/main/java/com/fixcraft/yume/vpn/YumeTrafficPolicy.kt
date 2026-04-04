package com.fixcraft.yume.vpn

internal object YumeTrafficPolicy {
    // Keep this to dedicated encrypted-DNS transports only. Port 443 is shared with
    // normal HTTPS, so treating a DNS resolver IP as "DNS bypass" breaks valid traffic
    // like curl/browser tests against 1.1.1.1.
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
            .lowercase()
    }
}
