package com.fixcraft.yume.provider

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigInteger
import java.net.URLEncoder
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Device-bound enrollment through the real client, store and Android Keystore
 * keys. The issuer, provider API and kit adapter are synthetic. Every proof is
 * verified here with the public key it carries. This is not TLS, browser or
 * native-kit qualification.
 */
@RunWith(AndroidJUnit4::class)
class ProviderClientProofTest {
    private class Clock : ProviderClock {
        @Volatile var seconds = 1_800_000_000L
        @Volatile var elapsedMillis = 100_000L
        override fun now() = ProviderClock.Reading(seconds, elapsedMillis)
        fun advance(by: Long) { seconds += by; elapsedMillis += by * 1000 }
    }

    /** Test-only plaintext blobs. Consumers encrypt theirs. */
    private class PlainBackend(override val directory: File) : ProviderSecretBackend {
        override fun ensureReady() = true
        override fun isUnlocked() = true
        override fun readBlob(name: String): ByteArray? = File(directory, "$name.bin").takeIf { it.isFile }?.readBytes()
        override fun writeBlob(name: String, plaintext: ByteArray) {
            val staged = File(directory, "$name.tmp")
            staged.writeBytes(plaintext)
            check(staged.renameTo(File(directory, "$name.bin")))
        }
        override fun deleteBlob(name: String) { File(directory, "$name.bin").delete() }
    }

    private class Proof(val method: String, val url: String, val thumbprint: String, val ath: String?, val iat: Long, val jti: String)

    /** Pauses before the real Keystore mutation, without holding its key-owner monitor. */
    private class PausingKeys(private val owner: ProviderProofKeys) : ProviderProofKeys by owner {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        @Volatile var createdEpoch: String? = null

        override fun create(epoch: String): ProviderProofKey {
            entered.countDown()
            check(proceed.await(10, TimeUnit.SECONDS)) { "Paused proof-key creation was not released" }
            return owner.create(epoch).also { createdEpoch = epoch }
        }
    }

    private class Issuer(private val clock: Clock) : ProviderTransport {
        var deviceBound = true
        val proofs = mutableListOf<Proof>()
        val bareRequests = mutableListOf<String>()
        var family: String? = null
        var access = "SYNTHETIC-ACCESS-ONE"
        var codeRequests = 0
        var openingCodeBinding: Map<String, Any?> = emptyMap()
        var grantRequests = mutableListOf<Map<String, Any?>>()
        var generation = 0L
        // The real HTTP owner reports a 401 and an issuer's invalid_grant as `unauthorized`.
        var accountRefused = false
        var refreshRefused = false
        var descriptorRefusal: String? = null

        fun descriptor(): ProviderCodec.Descriptor = ProviderCodec.Descriptor(PROVIDER, "Synthetic alpha", clock.seconds + 3600,
            listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256") +
                if (deviceBound) listOf(ProviderCodec.DEVICE_BOUND, ProviderCodec.SERVER_LOCATION) else emptyList(),
            listOf("vpn"), ProviderCodec.Auth(ISSUER, "$ISSUER/authorize", "$ISSUER/token", "$ISSUER/revoke",
                REGISTRATION.clientId, REGISTRATION.callback))

        @Suppress("UNCHECKED_CAST")
        private fun fields(bytes: ByteArray) = ProviderCodec.decode(bytes) as Map<String, Any?>

        override suspend fun request(origin: String, endpoint: String, body: ByteArray?, bearer: ProviderBearer?,
                                     limit: Int, responseType: String, requestType: String, proof: String?): ByteArray {
            val method = if (body == null) "GET" else "POST"
            if (proof == null) bareRequests += endpoint
            else proofs += verify(proof, method, endpoint, bearer?.value)
            return when {
                endpoint == "$PROVIDER/.well-known/yume-provider" -> {
                    descriptorRefusal?.let { throw ProviderCodec.Refusal(it) }
                    ProviderCodec.encode(descriptor().fields())
                }
                endpoint == "$ISSUER/token" -> {
                    if (refreshRefused && body!!.toString(Charsets.US_ASCII).contains("grant_type=refresh_token")) {
                        throw ProviderCodec.Refusal("unauthorized")
                    }
                    val bound = requireNotNull(proofs.last().thumbprint)
                    if (family == null || body!!.toString(Charsets.US_ASCII).contains("grant_type=authorization_code")) family = bound
                    assertEquals(family, bound)
                    ProviderCodec.encode(mapOf("token_type" to "DPoP", "access_token" to access,
                        "refresh_token" to "SYNTHETIC-REFRESH-${proofs.size}", "expires_in" to 60L,
                        "refresh_expires_in" to 3600L, "scope" to "provider.enroll"))
                }
                endpoint == "$ISSUER/revoke" -> ProviderCodec.encode(emptyMap<String, Any?>())
                else -> {
                    if (accountRefused) throw ProviderCodec.Refusal("unauthorized")
                    assertEquals(family, proofs.last().thumbprint)
                    assertEquals(access, bearer!!.value)
                    api(endpoint, body)
                }
            }
        }

        private fun api(endpoint: String, body: ByteArray?): ByteArray = when {
            endpoint.endsWith("/catalog") -> ProviderCodec.encode(fields(body!!) + mapOf("issued_at" to clock.seconds,
                "expires_at" to clock.seconds + 300, "services" to listOf(mapOf("id" to "service", "label" to "Service",
                    "role" to "vpn", "required" to emptyList<String>(), "servers" to listOf(mapOf("id" to "server",
                        "label" to "Tokyo", "host" to "edge.alpha.example", "port" to 443L,
                        "server_key_sha256" to "f".repeat(64), "country" to "JP"))))), ProviderCodec.METADATA_LIMIT)
            endpoint.endsWith("/grants") -> {
                // Each grant has its own ID and identity. A renewal is the next generation.
                val request = fields(body!!).also { grantRequests += it }
                generation = if (request["previous_grant"] == null) 1L else generation + 1
                ProviderCodec.encode(request + mapOf("grant_id" to "grant-${grantRequests.size}",
                    "generation" to generation, "issued_at" to clock.seconds, "refresh_after" to clock.seconds + 60,
                    "expires_at" to clock.seconds + 600, "client_identity" to "%064x".format(grantRequests.size),
                    "kit_sha256" to digest(KIT), "kit_size" to KIT.size.toLong()))
            }
            endpoint.endsWith("/kit") -> KIT.copyOf()
            endpoint.endsWith("/opening-code") -> {
                codeRequests++
                ProviderCodec.encode(fields(body!!) + ("opening_code" to CODE) + openingCodeBinding)
            }
            endpoint.endsWith("/revoke") -> ProviderCodec.encode(fields(body!!) + ("status" to "revoked"))
            else -> throw AssertionError("Unexpected synthetic endpoint")
        }

        override fun close() {}
    }

    private val clock = Clock()
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var issuer: Issuer
    private lateinit var authorization: ProviderAuthorization
    private lateinit var store: ProviderStore
    private lateinit var client: ProviderClient
    private val keys = AndroidProviderProofKeys()
    private val validatedCodes = mutableListOf<String>()

    @Before fun open() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.noBackupFilesDir, "provider-proof-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        issuer = Issuer(clock)
        reopen()
    }

    private fun reopen(proofKeys: ProviderProofKeys = keys) {
        val secrets = File(root, "secrets").apply { mkdirs() }
        store = ProviderStore(File(root, "state"), PlainBackend(secrets))
        authorization = ProviderAuthorization(clock)
        client = ProviderClient(context, REGISTRATION, store, { sealed, code, grant, _ ->
            assertEquals(grant.kitSha256, digest(sealed))
            validatedCodes += String(code)
            object : ProviderBundle {
                // This plaintext-only fixture has no AEAD overhead. Keep the
                // synthetic bundle above the store's encrypted-file size floor.
                override val bundle = ByteArray(32) { 7 }
                override fun close() { bundle.fill(0) }
            }
        }, clock, ProviderClient.Connections { }, http = issuer, authorization = authorization, proofKeys = proofKeys)
    }

    @After fun close() {
        client.close()
        keys.retain(emptySet())
        root.deleteRecursively()
    }

    private fun epoch() = store.epoch(PROVIDER)!!.value

    private fun metadata(): JSONObject = store.read(PROVIDER, store.epoch(PROVIDER)!!)!!.use {
        JSONObject(it.metadata.toString(Charsets.UTF_8))
    }

    private suspend fun signIn() {
        val binding = ProviderCodec.Context(PROVIDER, metadata().getString("device"), "vpn", REGISTRATION.clientId,
            REGISTRATION.callback, ISSUER)
        val state = ProviderTestAccess.beginState(authorization, binding, issuer.descriptor(), epoch(), REGISTRATION, 0)
        client.callback("${REGISTRATION.callback}?iss=${URLEncoder.encode(ISSUER, "UTF-8")}&state=$state&code=SYNTHETIC-CODE")
    }

    private suspend fun outcome(block: suspend () -> Unit): String = try { block(); "success" }
        catch (refusal: ProviderCodec.Refusal) { refusal.code }

    @Test fun signInBindsADeviceKeyAndTheBoundDeviceFetchesItsOpeningCode() = runBlocking {
        client.add(PROVIDER, ISSUER)
        assertFalse(client.enrollments().single().authorized)
        signIn()
        val exchange = issuer.proofs.single()
        assertEquals("POST", exchange.method)
        assertEquals("$ISSUER/token", exchange.url)
        assertNull(exchange.ath)
        assertEquals(clock.seconds, exchange.iat)
        assertEquals(ProviderProof.thumbprint(keys.load(epoch())!!), exchange.thumbprint)
        assertTrue(client.enrollments().single().authorized)
        val catalog = client.catalog(PROVIDER)
        assertEquals("JP", catalog.services.single().servers.single().country)
        client.install(PROVIDER, catalog, "service", "server") { throw AssertionError("Device-bound providers deliver the code") }
        assertEquals(listOf(CODE), validatedCodes)
        assertEquals(1, issuer.codeRequests)
        val api = issuer.proofs.drop(1)
        assertEquals(listOf("POST" to "/catalog", "POST" to "/grants", "GET" to "/grants/grant-1/kit", "POST" to "/opening-code"),
            api.map { it.method to it.url.removePrefix("$PROVIDER/provider/v1") })
        val ath = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest("SYNTHETIC-ACCESS-ONE".toByteArray()))
        assertTrue(api.all { it.thumbprint == exchange.thumbprint && it.ath == ath })
        assertEquals(issuer.proofs.size, issuer.proofs.map { it.jti }.toSet().size)
        assertTrue(issuer.bareRequests.all { it.endsWith("/.well-known/yume-provider") })
        assertEquals("JP", client.enrollments().single().server?.country)
    }

    @Test fun refreshUsesTheBoundKeyAndALostKeyEndsTheLocalFamily() = runBlocking {
        client.add(PROVIDER, ISSUER)
        signIn()
        val bound = issuer.proofs.single().thumbprint
        clock.advance(61)
        issuer.access = "SYNTHETIC-ACCESS-TWO"
        client.catalog(PROVIDER)
        val refresh = issuer.proofs[1]
        assertEquals("$ISSUER/token" to bound, refresh.url to refresh.thumbprint)
        assertNull(refresh.ath)
        keys.delete(epoch())
        assertEquals("unauthorized", outcome { client.catalog(PROVIDER) })
        assertFalse(metadata().has("account"))
        assertFalse(client.enrollments().single().authorized)
    }

    private suspend fun installed() {
        client.add(PROVIDER, ISSUER)
        signIn()
        client.install(PROVIDER, client.catalog(PROVIDER), "service", "server") { throw AssertionError("not anonymous") }
        assertNotNull(client.enrollments().single().grant)
    }

    private fun assertSignInAndGrantForgotten() {
        val ended = client.enrollments().single()
        assertFalse(ended.authorized)
        assertNull(ended.grant)
        assertFalse(ended.suspended)
        assertFalse(metadata().has("account"))
    }

    @Test fun aRefusedAccountEndsTheSignInAndTheGrantItObtained() = runBlocking {
        installed()
        issuer.accountRefused = true
        assertEquals("unauthorized", outcome { client.catalog(PROVIDER) })
        assertSignInAndGrantForgotten()
        assertEquals("state", outcome { client.lease(PROVIDER).close() })
        // Signing in again leads to a fresh grant, never a renewal of the closed one.
        issuer.accountRefused = false
        signIn()
        client.install(PROVIDER, client.catalog(PROVIDER), "service", "server") { throw AssertionError("not anonymous") }
        assertNull(issuer.grantRequests.last()["previous_grant"])
        client.lease(PROVIDER).close()
    }

    @Test fun aRefusedRefreshEndsTheSignInAndTheGrantItObtained() = runBlocking {
        installed()
        clock.advance(61)
        issuer.refreshRefused = true
        assertEquals("unauthorized", outcome { client.catalog(PROVIDER) })
        assertSignInAndGrantForgotten()
    }

    @Test fun aDueLeaseRenewsItsGrantAndAnExpiredOneStartsFresh() = runBlocking {
        installed()
        // Each refresh returns new tokens, as a real issuer does.
        clock.advance(61)
        issuer.access = "SYNTHETIC-ACCESS-TWO"
        client.install(PROVIDER, client.catalog(PROVIDER), "service", "server") { throw AssertionError("not anonymous") }
        assertEquals("grant-1", issuer.grantRequests.last()["previous_grant"])
        assertEquals("grant-2", client.enrollments().single().grant?.grantId)
        // The provider refuses to renew an expired grant, so the client asks for a fresh one.
        clock.advance(601)
        issuer.access = "SYNTHETIC-ACCESS-THREE"
        client.install(PROVIDER, client.catalog(PROVIDER), "service", "server") { throw AssertionError("not anonymous") }
        assertNull(issuer.grantRequests.last()["previous_grant"])
        assertEquals("grant-3", client.enrollments().single().grant?.grantId)
        client.lease(PROVIDER).close()
    }

    @Test fun anOutageKeepsTheLeaseButAChangedProviderSuspendsIt() = runBlocking {
        installed()
        issuer.descriptorRefusal = "unavailable"
        assertEquals("unavailable", outcome { client.catalog(PROVIDER) })
        assertFalse(client.enrollments().single().suspended)
        client.lease(PROVIDER).close()
        issuer.descriptorRefusal = "binding"
        assertEquals("binding", outcome { client.catalog(PROVIDER) })
        assertTrue(client.enrollments().single().suspended)
        assertEquals("revoked", outcome { client.lease(PROVIDER).close() })
    }

    @Test fun anotherGrantsDeliveredCodeCannotReachNativeValidation() = runBlocking {
        client.add(PROVIDER, ISSUER)
        signIn()
        val catalog = client.catalog(PROVIDER)
        issuer.openingCodeBinding = mapOf("grant_id" to "another-grant")
        assertEquals("binding", outcome {
            client.install(PROVIDER, catalog, "service", "server") { throw AssertionError("not anonymous") }
        })
        assertEquals(1, issuer.codeRequests)
        assertTrue(validatedCodes.isEmpty())
        val enrollment = client.enrollments().single()
        assertNull(enrollment.grant)
        assertTrue(enrollment.suspended)
    }

    @Test fun eachSignInUsesAFreshKey() = runBlocking {
        client.add(PROVIDER, ISSUER)
        signIn()
        val first = issuer.proofs.single().thumbprint
        signIn()
        assertNotEquals(first, issuer.proofs.last().thumbprint)
        assertEquals(ProviderProof.thumbprint(keys.load(epoch())!!), issuer.proofs.last().thumbprint)
    }

    @Test fun removalRevokesWithAProofThenDeletesTheKey() = runBlocking {
        client.add(PROVIDER, ISSUER)
        signIn()
        val catalog = client.catalog(PROVIDER)
        client.install(PROVIDER, catalog, "service", "server") { throw AssertionError("not anonymous") }
        val removed = epoch()
        assertTrue(client.remove(PROVIDER))
        assertEquals("$PROVIDER/provider/v1/revoke", issuer.proofs.last().url)
        assertTrue(issuer.bareRequests.contains("$ISSUER/revoke"))
        assertNull(keys.load(removed))
    }

    @Test fun removalCannotLeaveAKeyCreatedByAnInFlightSignIn() = runBlocking {
        client.close()
        val paused = PausingKeys(keys)
        reopen(paused)
        client.add(PROVIDER, ISSUER)
        val removed = epoch()
        val callback = launch(Dispatchers.IO) {
            try { signIn() }
            catch (_: kotlinx.coroutines.CancellationException) { /* Removal cancels the attempt. */ }
            catch (refused: ProviderCodec.Refusal) { assertEquals("cancelled", refused.code) }
            catch (_: GeneralSecurityException) { /* Removal may delete the key before signing. */ }
        }
        val removalStarted = CountDownLatch(1)
        val removalThread = AtomicReference<Thread>()
        lateinit var removal: Deferred<Boolean>
        try {
            assertTrue("Sign-in did not reach key creation", paused.entered.await(5, TimeUnit.SECONDS))
            removal = async(Dispatchers.IO) {
                removalThread.set(Thread.currentThread())
                removalStarted.countDown()
                client.remove(PROVIDER)
            }
            assertTrue(removalStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            // With the guard, removal waits at the publication monitor. Without
            // it, removal completes and deletes the not-yet-created key. Wait
            // for one actual outcome before permitting key creation to finish.
            while (!removal.isCompleted && removalThread.get().state != Thread.State.BLOCKED &&
                System.nanoTime() < deadline) Thread.sleep(5)
            assertTrue("Removal neither completed nor waited for key creation",
                removal.isCompleted || removalThread.get().state == Thread.State.BLOCKED)
        } finally {
            paused.proceed.countDown()
        }
        withTimeout(10_000) {
            callback.join()
            removal.await()
        }
        assertEquals(removed, paused.createdEpoch)
        assertNull(store.epoch(PROVIDER))
        assertTrue(client.enrollments().isEmpty())
        assertNull("Removed enrollment retained a late-created proof key", keys.load(removed))
    }

    @Test fun bearerRecordsFromBeforeDeviceBindingAreSignedOut() = runBlocking {
        client.add(PROVIDER, ISSUER)
        val epoch = store.epoch(PROVIDER)!!
        val legacy = metadata().put("account", JSONObject().put("access", "OLD").put("refresh", "OLD-REFRESH")
            .put("accessUntil", clock.seconds + 60).put("refreshUntil", clock.seconds + 3600)).toString().toByteArray()
        store.read(PROVIDER, epoch)!!.use { store.commit(PROVIDER, epoch, legacy, it.bundles) }
        assertFalse(client.enrollments().single().authorized)
        assertEquals("unauthorized", outcome { client.catalog(PROVIDER) })
        assertTrue(issuer.proofs.isEmpty())
    }

    @Test fun aProviderWithoutDeviceBindingCannotCompleteSignIn() = runBlocking {
        client.add(PROVIDER, ISSUER)
        issuer.deviceBound = false
        assertEquals("unsupported", outcome { signIn() })
        assertTrue(issuer.proofs.isEmpty())
        assertNull(keys.load(epoch()))
    }

    @Test fun startupSweepsKeysOfRemovedEnrollments() = runBlocking {
        client.add(PROVIDER, ISSUER)
        signIn()
        val orphan = UUID.randomUUID().toString()
        keys.create(orphan)
        client.close()
        reopen()
        assertNull(keys.load(orphan))
        assertNotNull(keys.load(epoch()))
    }

    private companion object {
        const val PROVIDER = "https://alpha.example"
        const val ISSUER = "https://login.alpha.example"
        const val CODE = "0123456789ABCDEFGHJKMNPQR"
        val REGISTRATION = ProviderRegistration("com.fixcraft.yume.providerlibrary.test", "synthetic-public-client",
            "https://client.example/oauth/callback")
        val KIT = ByteArray(1068) { 7 }

        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** Independent RFC 9449 check: structure, claims and the ES256 signature over the carried key. */
        fun verify(proof: String, method: String, url: String, access: String?): Proof {
            val parts = proof.split('.')
            assertEquals(3, parts.size)
            val decoder = Base64.getUrlDecoder()
            val header = JSONObject(String(decoder.decode(parts[0])))
            val claims = JSONObject(String(decoder.decode(parts[1])))
            assertEquals(setOf("typ", "alg", "jwk"), header.keys().asSequence().toSet())
            assertEquals("dpop+jwt" to "ES256", header.getString("typ") to header.getString("alg"))
            val jwk = header.getJSONObject("jwk")
            assertEquals(setOf("kty", "crv", "x", "y"), jwk.keys().asSequence().toSet())
            val expectedClaims = if (access == null) setOf("jti", "htm", "htu", "iat") else setOf("jti", "htm", "htu", "iat", "ath")
            assertEquals(expectedClaims, claims.keys().asSequence().toSet())
            assertEquals(method to url, claims.getString("htm") to claims.getString("htu"))
            val params = (KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair().public as ECPublicKey).params
            val point = ECPoint(BigInteger(1, decoder.decode(jwk.getString("x"))), BigInteger(1, decoder.decode(jwk.getString("y"))))
            val key = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
            val raw = decoder.decode(parts[2])
            assertEquals(64, raw.size)
            fun integer(half: ByteArray): ByteArray = BigInteger(1, half).toByteArray()
            val r = integer(raw.copyOfRange(0, 32))
            val s = integer(raw.copyOfRange(32, 64))
            val der = byteArrayOf(0x30, (4 + r.size + s.size).toByte(), 0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(key)
            verifier.update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
            assertTrue(verifier.verify(der))
            val canonical = """{"crv":"P-256","kty":"EC","x":"${jwk.getString("x")}","y":"${jwk.getString("y")}"}"""
            val thumbprint = Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()))
            val ath = if (claims.has("ath")) claims.getString("ath") else null
            if (access != null) assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(access.toByteArray())), ath)
            return Proof(method, url, thumbprint, ath, claims.getLong("iat"), claims.getString("jti"))
        }
    }
}
