package com.fixcraft.yume.consumer

import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceRoleTest {
    private fun selection(role: String) = ServiceSelection("https://operator.example",
        "84f6c2e2-ab9c-4ec8-9538-83456f08325f", "device", "access", "edge", "grant",
        "a".repeat(64), "b".repeat(64), "edge.example", 443, "Server", role)

    private fun lease(role: String): ProviderClient.Lease {
        val selected = selection(role)
        val request = ProviderCodec.GrantRequest(selected.provider, selected.device, "request",
            selected.service, selected.server, role, null)
        val grant = ProviderCodec.Grant(request, selected.grant, 1, 1000, 1010, 1100,
            selected.identity, "c".repeat(64), 1068)
        return ProviderClient.Lease(byteArrayOf(1), 1100, 200000, selected.epoch, grant,
            ProviderCodec.Server(selected.server, selected.label, selected.host, selected.port, selected.serverKey))
    }

    @Test fun consumerRoleIsPersistedAndMustMatchTheGrantAndConsumer() {
        for (role in listOf("vpn", "relay")) {
            val selected = selection(role)
            assertEquals(selected, ServiceSelection.decode(selected.encode()))
            lease(role).use { current ->
                assertTrue(selected.matches(current, role))
                val other = if (role == "vpn") "relay" else "vpn"
                assertFalse(selected.matches(current, other))
                assertFalse(selected.copy(role = other).matches(current, other))
            }
        }
    }

    @Test fun missingUnknownAndExtraRoleFieldsHaveNoFallback() {
        val encoded = selection("relay").encode()
        assertThrows(Exception::class.java) {
            ServiceSelection.decode(JSONObject(encoded).apply { remove("role") }.toString())
        }
        assertThrows(Exception::class.java) {
            ServiceSelection.decode(JSONObject(encoded).put("role", "unknown").toString())
        }
        assertThrows(Exception::class.java) {
            ServiceSelection.decode(JSONObject(encoded).put("fallback", "vpn").toString())
        }
    }
}
