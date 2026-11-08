package com.fixcraft.yume.consumer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Synthetic application selection bindings only. These bundles, fingerprints
 * and grants are not native kits or evidence of a validated transport identity.
 */
@RunWith(AndroidJUnit4::class)
class ServiceSelectionTest {
    private val selected = ServiceSelection(
        provider = "https://operator.example",
        epoch = "84f6c2e2-ab9c-4ec8-9538-83456f08325f",
        device = "synthetic-device",
        service = "synthetic-vpn",
        server = "synthetic-edge",
        grant = "synthetic-grant",
        identity = "a".repeat(64),
        serverKey = "b".repeat(64),
        host = "edge.operator.example",
        port = 443,
        label = "Synthetic server", role = "vpn",
    )

    private val request get() = ProviderCodec.GrantRequest(
        selected.provider, selected.device, "synthetic-request", selected.service,
        selected.server, "vpn", null,
    )
    private val grant get() = ProviderCodec.Grant(
        request, selected.grant, 1, 1_800_000_000, 1_800_000_010,
        1_800_000_300, selected.identity, "c".repeat(64), 1_068,
    )
    private val server get() = ProviderCodec.Server(
        selected.server, selected.label, selected.host, selected.port, selected.serverKey,
    )

    private fun lease(
        epoch: String = selected.epoch,
        grant: ProviderCodec.Grant = this.grant,
        server: ProviderCodec.Server? = this.server,
    ): ProviderClient.Lease = ProviderClient.Lease(
        "SYNTHETIC-NOT-A-NATIVE-KIT".toByteArray(Charsets.US_ASCII),
        grant.expiresAt, 400_000, epoch, grant, server,
    )

    @Test
    fun persistedSelectionRetainsEveryBindingAndSummary() {
        val restored = ServiceSelection.decode(selected.encode())
        assertEquals(selected, restored)
        assertEquals("provider:${selected.epoch}:${selected.grant}", restored.id)
        assertEquals(selected.id, restored.summary.id)
        assertEquals(selected.label, restored.summary.name)
        assertEquals(selected.host, restored.summary.host)
        assertEquals(selected.port, restored.summary.port)
        lease().use { assertTrue(restored.matches(it, "vpn")) }
    }

    @Test
    fun everyAuthorityOrEndpointSubstitutionRefusesTheLease() {
        val alternatives = mapOf(
            "provider" to selected.copy(provider = "https://other.example"),
            "epoch" to selected.copy(epoch = "94f6c2e2-ab9c-4ec8-9538-83456f08325f"),
            "device" to selected.copy(device = "other-device"),
            "service" to selected.copy(service = "other-service"),
            "server" to selected.copy(server = "other-server"),
            "grant" to selected.copy(grant = "other-grant"),
            "identity" to selected.copy(identity = "d".repeat(64)),
            "server key" to selected.copy(serverKey = "e".repeat(64)),
            "host" to selected.copy(host = "other.operator.example"),
            "port" to selected.copy(port = 8443),
        )
        lease().use { current ->
            alternatives.forEach { (field, replacement) ->
                assertFalse("Changed $field must not acquire the current grant", replacement.matches(current, "vpn"))
            }
        }
    }

    @Test
    fun labelsAreDisplayDataAndDoNotChooseAuthority() {
        lease().use { current ->
            assertTrue(selected.copy(label = "A renamed display label").matches(current, "vpn"))
        }
    }

    @Test
    fun aLeaseWithoutItsValidatedServerBindingIsRefused() {
        lease(server = null).use { assertFalse(selected.matches(it, "vpn")) }
    }

    @Test
    fun aDifferentServerIdCannotBorrowTheSameHostAndPublicKey() {
        lease(server = server.copy(id = "different-edge")).use {
            assertFalse(selected.matches(it, "vpn"))
        }
    }

    @Test
    fun aGrantForAnotherConsumerRoleCannotBecomeAVpnSelection() {
        lease(grant = grant.copy(request = request.copy(role = "relay"))).use {
            assertFalse(selected.matches(it, "vpn"))
        }
    }

    @Test
    fun anEnrollmentProducesTheExactCommittedGrantSelection() {
        val entry = ProviderClient.Enrollment(selected.provider, selected.epoch,
            "Synthetic operator", null, grant, server, false)
        assertEquals(selected, ServiceSelection.from(entry))
        assertRefused { ServiceSelection.from(entry.copy(grant = null)) }
        assertRefused { ServiceSelection.from(entry.copy(server = null)) }
    }

    @Test
    fun malformedOrIncompletePersistedBindingsAreRefused() {
        assertRefused { ServiceSelection.decode("{") }
        assertRefused { ServiceSelection.decode(" ".repeat(4_097)) }
        val missing = JSONObject(selected.encode()).apply { remove("grant") }
        assertRefused { ServiceSelection.decode(missing.toString()) }
        val extra = JSONObject(selected.encode()).put("fallback", "manual")
        assertRefused { ServiceSelection.decode(extra.toString()) }
        val invalid = mapOf(
            "provider" to "http://operator.example",
            "epoch" to "invalid-epoch",
            "device" to "device with spaces",
            "service" to "service/escape",
            "server" to "",
            "grant" to "grant?other",
            "identity" to "A".repeat(64),
            "serverKey" to "b".repeat(63),
            "host" to "127.0.0.1",
            "port" to 0,
            "label" to "",
        )
        invalid.forEach { (field, value) ->
            val changed = JSONObject(selected.encode()).put(field, value)
            assertRefused(field) { ServiceSelection.decode(changed.toString()) }
        }
    }

    private fun assertRefused(field: String = "binding", action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Invalid $field must be refused", failure is Exception)
    }
}
