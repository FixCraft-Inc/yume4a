package com.fixcraft.yume.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.core.YumeClient
import com.fixcraft.yume.core.YumeLibrary
import com.fixcraft.yume.data.KitBundle
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Runs the real packaged native parser; the explicit identity stub proves policy only. */
@RunWith(AndroidJUnit4::class)
class ProviderKitValidatorTest {
    private val identity = "a".repeat(64)
    private val serverPublic = "synthetic public file, not a transport key".toByteArray()
    private val server = ProviderCodec.Server("server", "Server", "edge.example", 443, sha256(serverPublic))

    private fun config(): JSONObject = JSONObject(
        """{
          "schema":1,"role":"client","endpoint":{"host":"edge.example","port":443},
          "suite":{"id":"ytp1-tls13-h2","secure_channel":"tls13-native","front_door":"h2-web","carrier":"h2-duplex","session":"ytp1-hybrid"},
          "credentials":{
            "composite_key":{"file":"credentials/client.pem"},
            "access_psk":{"file":"credentials/access.psk"},
            "admission_key":{"file":"credentials/admission.key"},
            "server_trust":{"file":"credentials/trust.pem"},
            "server_identity":{"file":"credentials/server.pub.pem"},
            "server_mlkem":{"file":"credentials/server-kem.pem"}
          },
          "cover":{"profile":"chrome151-node24-v1"},
          "services":[{"name":"tcp","kind":"stream","max_concurrent_streams":256},{"name":"udp","kind":"packet","max_concurrent_streams":256}],
          "adapters":[{"kind":"socks5","service":"tcp","listen_address":"127.0.0.1","listen_port":1080,"udp_service":"udp"}],
          "limits":{"max_frame_bytes":262144,"max_streams":256,"max_queued_bytes":4194304,"max_pending_opens":64,"max_rekey_jobs":4,"max_control_messages":128,"max_packet_bytes":65535,"max_packet_batch":64}
        }""".trimIndent(),
    )

    private fun files(document: JSONObject = config()): List<KitFile> = listOf(
        KitFile("credentials/client.pem", false, "not a private key".toByteArray()),
        KitFile("credentials/access.psk", false, ByteArray(32) { 1 }),
        KitFile("credentials/admission.key", false, ByteArray(32) { 2 }),
        KitFile("credentials/trust.pem", false, "not a certificate".toByteArray()),
        KitFile("credentials/server.pub.pem", false, serverPublic.copyOf()),
        KitFile("credentials/server-kem.pem", false, "not a KEM key".toByteArray()),
        KitFile("start-client", true, "#!/bin/sh\nexit 1\n".toByteArray()),
        KitFile("yume.json", false, document.toString().toByteArray()),
    )

    private fun check(
        document: JSONObject = config(),
        selected: ProviderCodec.Server = server,
        verifier: YumeProviderNative.NativeIdentityVerifier = YumeProviderNative.NativeIdentityVerifier { _, _ -> identity },
        mutate: (List<KitFile>) -> List<KitFile> = { it },
    ): ByteArray {
        val original = files(document)
        val changed = mutate(original)
        return try {
            ProviderKitValidator(YumeProviderNative(verifier)).validateOpened(changed, identity, selected)
        } finally {
            original.forEach { it.bytes.fill(0) }
            changed.forEach { it.bytes.fill(0) }
        }
    }

    @Test
    fun syntaxAcceptanceAndEndpointCreationDoNotValidateCredentialFiles() {
        val document = config().toString().toByteArray()
        try {
            // These exact bytes name absent credential files. Parsing accepts the
            // references, so it cannot establish a local identity fingerprint.
            assertEquals(YumeLibrary.ROLE_CLIENT, YumeLibrary.checkConfig(document))
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = Files.createTempDirectory(context.noBackupFilesDir.toPath(), "provider-native-check-").toFile()
            try {
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                YumeClient.create(directory, document) { false }.use { client ->
                    val status = client.status()
                    assertNotNull(status)
                    assertEquals(1, status!!.state)
                    assertEquals(0, status.session)
                }
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            } finally {
                assertTrue(directory.delete())
            }
        } finally {
            document.fill(0)
        }
    }

    @Test
    fun aNativeIdentityVerifierIsRequiredBeforeAnyBundleCanBePublished() {
        var called = false
        val refusal = assertThrows(ProviderCodec.Refusal::class.java) {
            check(verifier = YumeProviderNative.NativeIdentityVerifier { _, _ ->
                called = true
                throw ProviderCodec.Refusal("unsupported")
            })
        }
        assertTrue(called)
        assertEquals("unsupported", refusal.code)
    }

    @Test
    fun theIdentityMustMatchTheGrant() {
        assertEquals("binding", assertThrows(ProviderCodec.Refusal::class.java) {
            check(verifier = YumeProviderNative.NativeIdentityVerifier { _, _ -> "b".repeat(64) })
        }.code)
    }

    @Test
    fun cancellationDoesNotBecomeAnEnrollmentRefusal() {
        assertThrows(CancellationException::class.java) {
            check(verifier = YumeProviderNative.NativeIdentityVerifier { _, _ -> throw CancellationException() })
        }
    }

    @Test
    fun policyValidatedFilesRemainAnInertBundleWithoutAFileSystemWrite() {
        val bundle = check()
        try {
            val decoded = KitBundle.decode(bundle)
            try {
                assertEquals(8, decoded.size)
                assertEquals(7, decoded.count { !it.executable })
                assertTrue(decoded.single { it.path == "start-client" }.executable)
                assertEquals(serverPublic.toList(), decoded.single { it.path == "credentials/server.pub.pem" }.bytes.toList())
            } finally {
                decoded.forEach { it.bytes.fill(0) }
            }
        } finally {
            bundle.fill(0)
        }
    }

    @Test
    fun theSelectedHostPortAndServerFileHashAreExact() {
        for (selected in listOf(server.copy(host = "other.example"), server.copy(port = 444), server.copy(serverKeySha256 = "c".repeat(64)))) {
            assertEquals("binding", assertThrows(ProviderCodec.Refusal::class.java) { check(selected = selected) }.code)
        }
    }

    @Test
    fun referencesCannotLeaveTheOpenedKitOrAliasAnotherCredential() {
        for (path in listOf("/private/key", "../key", "credentials/../key", "missing.pem", "yume.json", "credentials/server.pub.pem")) {
            val document = config()
            document.getJSONObject("credentials").getJSONObject("composite_key").put("file", path)
            assertThrows(ProviderCodec.Refusal::class.java) { check(document) }
        }
        assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) {
            check { source -> source.map { if (it.path == "credentials/client.pem") KitFile(it.path, true, it.bytes) else it } }
        }.code)
    }

    @Test
    fun providerPolicyRefusesUnexpectedServicesAndLocalNetworkAdapters() {
        val services = config()
        services.getJSONArray("services").put(JSONObject().put("name", "extra").put("kind", "stream").put("max_concurrent_streams", 2))
        assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) { check(services) }.code)
        val forward = config()
        forward.put("adapters", JSONArray().put(JSONObject().put("kind", "forward").put("service", "tcp").put("listen_address", "127.0.0.1").put("listen_port", 1080)))
        assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) { check(forward) }.code)
    }

    @Test
    fun proxyAndProviderChosenDialAddressAreRefused() {
        val proxy = config()
        proxy.getJSONObject("endpoint").put("socks5_proxy", JSONObject().put("address", "127.0.0.1").put("port", 9050))
        assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) { check(proxy) }.code)
        val dial = config()
        dial.getJSONObject("endpoint").put("connect_address", "127.0.0.1")
        assertEquals("unsupported", assertThrows(ProviderCodec.Refusal::class.java) { check(dial) }.code)
    }

    @Test
    fun equalOrMalformedAdmissionAndAccessSecretsAreRefused() {
        assertEquals("binding", assertThrows(ProviderCodec.Refusal::class.java) {
            check { source -> source.map { if (it.path == "credentials/admission.key") KitFile(it.path, false, ByteArray(32) { 1 }) else it } }
        }.code)
        assertEquals("malformed", assertThrows(ProviderCodec.Refusal::class.java) {
            check { source -> source.map { if (it.path == "credentials/access.psk") KitFile(it.path, false, ByteArray(31)) else it } }
        }.code)
    }

    @Test
    fun strictNativeValidationPrecedesTheIdentityVerifier() {
        var called = false
        val invalid = config().put("unknown", true)
        assertEquals("malformed", assertThrows(ProviderCodec.Refusal::class.java) {
            check(invalid, verifier = YumeProviderNative.NativeIdentityVerifier { _, _ -> called = true; identity })
        }.code)
        assertFalse(called)
    }

    @Test
    fun aCircuitCannotReachTheCredentialVerifier() {
        var called = false
        val routed = config().put("circuits", JSONObject())
        assertThrows(ProviderCodec.Refusal::class.java) {
            check(routed, verifier = YumeProviderNative.NativeIdentityVerifier { _, _ -> called = true; identity })
        }
        assertFalse(called)
    }

    @Test
    fun malformedSealedBytesAndHashSubstitutionWipeTheOpeningCode() {
        val request = ProviderCodec.GrantRequest("https://provider.example", "device", "request", "service", "server", "vpn", null)
        val ciphertext = ByteArray(ProviderKitValidator.MIN_SEALED_BYTES) { 7 }
        val grant = ProviderCodec.Grant(request, "grant", 1, 1, 1, 2, identity, sha256(ciphertext), ciphertext.size)
        for ((sealed, expected, metadata) in listOf(
            Triple(ByteArray(12), "malformed", grant),
            Triple(ciphertext, "binding", grant.copy(kitSha256 = "f".repeat(64))),
            Triple(ciphertext, "malformed", grant),
        )) {
            val code = "7777777777777777777777777".toCharArray()
            assertEquals(expected, assertThrows(ProviderCodec.Refusal::class.java) {
                ProviderKitValidator(YumeProviderNative { _, _ -> identity }).validate(sealed, code, metadata, server)
            }.code)
            assertTrue(code.all { it == '\u0000' })
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
