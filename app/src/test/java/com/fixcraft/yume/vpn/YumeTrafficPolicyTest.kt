package com.fixcraft.yume.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YumeTrafficPolicyTest {
    @Test
    fun httpsToConfiguredDnsIpIsAllowed() {
        assertFalse(
            YumeTrafficPolicy.isEncryptedDnsBypass(
                host = "1.1.1.1",
                port = 443,
                dnsServers = listOf("1.1.1.1", "9.9.9.9"),
            )
        )
    }

    @Test
    fun dotToConfiguredDnsIpIsBlocked() {
        assertTrue(
            YumeTrafficPolicy.isEncryptedDnsBypass(
                host = "1.1.1.1",
                port = 853,
                dnsServers = listOf("1.1.1.1", "9.9.9.9"),
            )
        )
    }

    @Test
    fun dotToUnrelatedHostIsAllowed() {
        assertFalse(
            YumeTrafficPolicy.isEncryptedDnsBypass(
                host = "8.8.8.8",
                port = 853,
                dnsServers = listOf("1.1.1.1", "9.9.9.9"),
            )
        )
    }
}
