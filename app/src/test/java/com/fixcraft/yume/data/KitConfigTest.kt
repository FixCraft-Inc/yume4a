package com.fixcraft.yume.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KitConfigTest {
    // The client document `yume-setup init` writes, with its values.
    private val kit = """
        {
          "adapters": [
            {"kind": "socks5", "listen_address": "127.0.0.1", "listen_port": 1080,
             "service": "tcp", "udp_service": "udp"}
          ],
          "control": {"socket": "/run/user/1000/yume/control.sock"},
          "cover": {"profile": "chrome151-node24-v1"},
          "credentials": {"access_psk": {"file": "credentials/client-access.psk"}},
          "endpoint": {"host": "vpn.example.org", "port": 443},
          "limits": {"max_pending_opens": 64, "max_streams": 256, "max_epoch_bytes": 1048576},
          "role": "client",
          "schema": 1,
          "services": [
            {"kind": "stream", "max_concurrent_streams": 256, "name": "tcp"},
            {"kind": "packet", "max_concurrent_streams": 256, "name": "udp"}
          ],
          "suite": {"id": "ytp1-tls13-h2"}
        }
    """.trimIndent().toByteArray()

    private fun edited(change: (JSONObject) -> Unit): ByteArray =
        JSONObject(String(kit)).also(change).toString().toByteArray()

    @Test
    fun theKitsOwnServicesAreUsed() {
        val described = KitConfig.describe(kit)
        assertEquals("vpn.example.org", described.host)
        assertEquals(443, described.port)
        assertEquals("tcp", described.streamService)
        assertEquals("udp", described.packetService)
        assertNull(described.connectAddress)
        assertFalse(described.usesCircuits)
        assertFalse(described.usesProxy)
    }

    @Test
    fun aKitWithoutAUdpServiceCarriesNoUdp() {
        val described = KitConfig.describe(edited { root ->
            root.getJSONArray("adapters").getJSONObject(0).remove("udp_service")
        })
        assertEquals("tcp", described.streamService)
        assertNull(described.packetService)
    }

    @Test
    fun aKitWithoutASocksAdapterOffersItsFirstServices() {
        val described = KitConfig.describe(edited { it.put("adapters", org.json.JSONArray()) })
        assertEquals("tcp", described.streamService)
        assertEquals("udp", described.packetService)
    }

    @Test
    fun circuitsAProxyAndAFixedAddressAreSeen() {
        assertTrue(KitConfig.describe(edited { it.put("circuits", JSONObject().put("hops", 3)) }).usesCircuits)
        val proxied = KitConfig.describe(edited {
            it.getJSONObject("endpoint").put("socks5_proxy", JSONObject().put("address", "10.0.0.1").put("port", 1080))
        })
        assertTrue(proxied.usesProxy)
        val fixed = KitConfig.describe(edited { it.getJSONObject("endpoint").put("connect_address", "192.0.2.7") })
        assertEquals("192.0.2.7", fixed.connectAddress)
    }

    @Test
    fun aDocumentThatNamesNoServerOrServiceIsRefused() {
        assertThrows(KitConfigException::class.java) { KitConfig.describe("[]".toByteArray()) }
        assertThrows(KitConfigException::class.java) { KitConfig.describe(edited { it.remove("endpoint") }) }
        assertThrows(KitConfigException::class.java) {
            KitConfig.describe(edited { it.getJSONObject("endpoint").put("port", 0) })
        }
        assertThrows(KitConfigException::class.java) {
            KitConfig.describe(edited { it.put("services", org.json.JSONArray()) })
        }
    }

    // The server must see what a yume client of the same kit offers, so only
    // the local adapters, the control socket and the dialled address change.
    @Test
    fun thePhonesDocumentChangesOnlyWhatDiffersOnAPhone() {
        val before = JSONObject(String(kit))
        val after = JSONObject(String(KitConfig.forDevice(kit, "203.0.113.5")))
        assertEquals(0, after.getJSONArray("adapters").length())
        assertFalse(after.has("control"))
        assertEquals("203.0.113.5", after.getJSONObject("endpoint").getString("connect_address"))
        assertEquals("vpn.example.org", after.getJSONObject("endpoint").getString("host"))
        for (key in listOf("cover", "credentials", "limits", "role", "schema", "services", "suite")) {
            assertEquals(key, before.get(key).toString(), after.get(key).toString())
        }
        assertEquals(before.length() - 1, after.length())
    }

    @Test
    fun withoutAnAddressTheEndpointIsLeftAsItIs() {
        val after = JSONObject(String(KitConfig.forDevice(kit, null)))
        assertFalse(after.getJSONObject("endpoint").has("connect_address"))
    }

    @Test
    fun onlyAddressLiteralsNeedNoLookup() {
        for (literal in listOf("192.0.2.1", "0.0.0.0", "255.255.255.255", "2001:db8::1", "::1", "::ffff:192.0.2.1")) {
            assertTrue(literal, KitConfig.isAddressLiteral(literal))
        }
        for (name in listOf("", "vpn.example.org", "192.0.2", "192.0.2.256", "1.2.3.4.5", "a.b.c.d", "example", "1.2.3.x")) {
            assertFalse(name, KitConfig.isAddressLiteral(name))
        }
    }

    @Test
    fun resolverListsAreParsedStrictly() {
        assertEquals(listOf("1.1.1.1", "9.9.9.9"), AppSettings.parseDnsServers("1.1.1.1, 9.9.9.9"))
        assertEquals(listOf("2606:4700:4700::1111"), AppSettings.parseDnsServers(" 2606:4700:4700::1111 "))
        assertEquals(listOf("1.1.1.1"), AppSettings.parseDnsServers("1.1.1.1\n1.1.1.1"))
        assertNull(AppSettings.parseDnsServers(""))
        assertNull(AppSettings.parseDnsServers("dns.example.org"))
        assertNull(AppSettings.parseDnsServers("1.1.1.1, 2.2.2.2, 3.3.3.3, 4.4.4.4, 5.5.5.5"))
    }
}
