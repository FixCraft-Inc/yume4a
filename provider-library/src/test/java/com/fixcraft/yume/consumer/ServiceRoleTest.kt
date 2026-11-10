package com.fixcraft.yume.consumer

import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun consumerRoleIsPersistedWithTheRouteAndMustMatchTheGrantAndConsumer() {
        for (role in listOf("vpn", "relay")) {
            val route = ServiceRoute("https://operator.example", "84f6c2e2-ab9c-4ec8-9538-83456f08325f", role, null)
            assertEquals(route, ServiceRoute.decode(route.encode()))
            val selected = selection(role)
            lease(role).use { current ->
                assertTrue(selected.matches(current, role))
                val other = if (role == "vpn") "relay" else "vpn"
                assertFalse(selected.matches(current, other))
                assertFalse(selected.copy(role = other).matches(current, other))
            }
        }
    }

    @Test fun aStoredRouteIsOnlyItsProviderEpochAndRole() {
        val chosen = ProviderClient.Choice("access", ProviderCodec.Server("edge", "Server", "edge.example", 443, "b".repeat(64)))
        val route = ServiceRoute("https://operator.example", "84f6c2e2-ab9c-4ec8-9538-83456f08325f", "vpn", chosen)
        assertEquals(setOf("provider", "epoch", "role"), JSONObject(route.encode()).keys().asSequence().toSet())
        assertEquals(route.copy(choice = null), ServiceRoute.decode(route.encode()))
    }

    @Test fun missingUnknownAndExtraFieldsNameNoRoute() {
        val encoded = ServiceRoute("https://operator.example", "84f6c2e2-ab9c-4ec8-9538-83456f08325f", "relay", null).encode()
        for (changed in listOf(JSONObject(encoded).apply { remove("role") }, JSONObject(encoded).put("role", "unknown"),
                JSONObject(encoded).put("fallback", "vpn"), JSONObject(encoded).put("provider", "http://operator.example"),
                JSONObject(encoded).put("epoch", "not-an-epoch"))) {
            assertNull(changed.toString(), ServiceRoute.decode(changed.toString()))
        }
        assertNull(ServiceRoute.decode("{"))
        assertNull(ServiceRoute.decode(" ".repeat(1_025)))
    }

    /** A selection stored by an older build bound a grant, which is no route: the person chooses again. */
    @Test fun aStoredGrantBindingNamesNoRoute() {
        val old = JSONObject().put("provider", "https://operator.example").put("epoch", "84f6c2e2-ab9c-4ec8-9538-83456f08325f")
            .put("device", "device").put("service", "access").put("server", "edge").put("grant", "grant")
            .put("identity", "a".repeat(64)).put("serverKey", "b".repeat(64)).put("host", "edge.example").put("port", 443)
            .put("label", "Server").put("role", "vpn")
        assertNull(ServiceRoute.decode(old.toString()))
    }
}
