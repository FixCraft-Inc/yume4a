package com.fixcraft.yume.provider

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.MessageDigest

/** Synthetic native seam: proves consumer delegation, never native schema or credential acceptance. */
class ProviderKitValidatorTest {
    private val identity = "a".repeat(64)
    private val keys = listOf("composite_key", "access_psk", "admission_key", "server_trust", "server_identity", "server_mlkem")
    private val serverPublic = "synthetic server public file".toByteArray()
    private val server = ProviderCodec.Server("edge", "Edge", "edge.example", 443, sha256(serverPublic))

    private fun files(): List<ProviderFile> {
        val document = JSONObject().put("endpoint", JSONObject().put("host", server.host).put("port", server.port))
            .put("services", JSONArray().put(JSONObject().put("name", "named-data").put("kind", "stream")))
            .put("adapters", JSONArray()).put("credentials", JSONObject().also { credentials ->
                keys.forEach { key -> credentials.put(key, JSONObject().put("file", "credentials/$key")) }
            })
        return keys.mapIndexed { index, key ->
            ProviderFile("credentials/$key", false, if (key == "server_identity") serverPublic.copyOf() else ByteArray(32) { index.toByte() })
        } + ProviderFile("yume.json", false, document.toString().toByteArray())
    }

    private fun native(events: MutableList<String>, consumer: () -> Unit) = object : ProviderKitValidator.Native {
        override fun openKit(sealed: ByteArray, code: ByteArray): List<ProviderFile> = error("not used")
        override fun checkClientConfig(config: ByteArray) { events += "schema" }
        override fun checkConsumerConfig(config: ByteArray) { events += "consumer"; consumer() }
        override fun fingerprint(files: List<ProviderFile>, originalConfig: ByteArray): String { events += "identity"; return identity }
    }

    @Test fun aConsumerCanAuthorizeItsOwnNamedServiceWithoutVpnServiceNamesInTheLibrary() {
        val opened = files()
        val events = mutableListOf<String>()
        try {
            val bundle = ProviderKitValidator(native(events) {}).validateOpened(opened, identity, server)
            try { assertEquals(7, ProviderBundleCodec.decode(bundle).also { entries -> entries.forEach { it.bytes.fill(0) } }.size) }
            finally { bundle.fill(0) }
            assertEquals(listOf("schema", "consumer", "identity"), events)
        } finally { opened.forEach { it.bytes.fill(0) } }
    }

    @Test fun theMandatoryConsumerPolicyCanRefuseBeforePrivateCredentialVerification() {
        val opened = files()
        val events = mutableListOf<String>()
        try {
            assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
                ProviderKitValidator(native(events) { throw ProviderCodec.Refusal("unsupported") })
                    .validateOpened(opened, identity, server)
            }.code)
            assertEquals(listOf("schema", "consumer"), events)
        } finally { opened.forEach { it.bytes.fill(0) } }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
