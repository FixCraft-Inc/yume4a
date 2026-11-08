package com.fixcraft.yume.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class YumeProviderServicePolicyTest {
    private fun service(name: String, kind: String) = JSONObject().put("name", name).put("kind", kind)
    private fun document() = JSONObject().put("services", JSONArray()
        .put(service("tcp", "stream")).put(service("udp", "packet"))).put("adapters", JSONArray())

    @Test fun vpnServicesAreAcceptedWithNoLocalAdapter() {
        YumeProviderNative.checkProviderServicePolicy(document())
    }

    @Test fun optionalSocksAdapterRetainsItsExactServiceBinding() {
        for (udp in listOf(false, true)) {
            val adapter = JSONObject().put("kind", "socks5").put("service", "tcp")
            if (udp) adapter.put("udp_service", "udp")
            YumeProviderNative.checkProviderServicePolicy(document().put("adapters", JSONArray().put(adapter)))
        }
    }

    @Test fun missingExtraAliasedAndWrongKindServicesAreRefused() {
        val candidates = listOf(
            JSONArray().put(service("tcp", "stream")),
            JSONArray().put(service("tcp", "stream")).put(service("udp", "packet")).put(service("other", "stream")),
            JSONArray().put(service("tcp", "stream")).put(service("tcp", "packet")),
            JSONArray().put(service("tcp", "packet")).put(service("udp", "stream")),
            JSONArray().put(service("named-relay", "stream")),
        )
        candidates.forEach { services ->
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                YumeProviderNative.checkProviderServicePolicy(document().put("services", services))
            }.code)
        }
    }

    @Test fun extraAdaptersAndForeignServiceBindingsAreRefused() {
        val socks = JSONObject().put("kind", "socks5").put("service", "tcp")
        val candidates = listOf(
            JSONArray().put(socks).put(socks),
            JSONArray().put(JSONObject().put("kind", "forward").put("service", "tcp")),
            JSONArray().put(JSONObject().put("kind", "socks5").put("service", "other")),
            JSONArray().put(JSONObject().put("kind", "socks5").put("service", "tcp").put("udp_service", "other")),
        )
        candidates.forEach { adapters ->
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                YumeProviderNative.checkProviderServicePolicy(document().put("adapters", adapters))
            }.code)
        }
    }
}
