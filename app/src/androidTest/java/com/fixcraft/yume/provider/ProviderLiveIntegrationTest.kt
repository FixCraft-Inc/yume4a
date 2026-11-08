package com.fixcraft.yume.provider

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitBundle
import com.fixcraft.yume.data.KitImport
import com.fixcraft.yume.data.KitStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Explicit real HTTPS/browser/native gate. The host owns the private handoff and services. */
@RequiresNativeFixtures
@RunWith(AndroidJUnit4::class)
class ProviderLiveIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val handoff = File(context.noBackupFilesDir, "provider-live-handoff")
    private val config by lazy { JSONObject(File(handoff, "config.json").readText()) }
    private val alpha get() = config.getString("alpha")
    private val beta get() = config.getString("beta")
    private val issuer get() = config.getString("issuer")
    private val registration get() = ProviderRegistration(context.packageName,
        config.getString("client_id"), config.getString("callback"))
    private val evidence = JSONObject()
    private val timings = JSONArray()

    /** Observe public timestamps only; requests and token responses never enter evidence. */
    private fun transport(): ProviderTransport = object : ProviderTransport {
        private val delegate = ProviderHttp()
        override suspend fun request(origin: String, endpoint: String, body: ByteArray?, bearer: ProviderBearer?,
                                     limit: Int, responseType: String, requestType: String, proof: String?): ByteArray {
            val raw = delegate.request(origin, endpoint, body, bearer, limit, responseType, requestType, proof)
            if (endpoint.endsWith("/catalog")) {
                val document = JSONObject(String(raw, Charsets.UTF_8))
                timings.put(JSONObject().put("provider", origin)
                    .put("issued_at", document.getLong("issued_at"))
                    .put("expires_at", document.getLong("expires_at"))
                    .put("trusted_now", requireNotNull(AndroidProviderClock.now()).seconds))
                File(handoff, "timing.json").writeText(timings.toString(2))
            }
            return raw
        }
        override fun close() = delegate.close()
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun host(action: String, grant: ProviderCodec.Grant? = null): File {
        val id = grant?.grantId ?: action
        val request = File(handoff, "$id.request")
        request.writeText(JSONObject().put("action", action).put("provider", grant?.request?.provider)
            .put("grant_id", grant?.grantId).toString())
        val reply = File(handoff, "$id.ready")
        withTimeout(30_000) { while (!reply.isFile) delay(100) }
        assertEquals("ready", reply.readText())
        assertTrue(reply.delete())
        return File(handoff, id)
    }

    private suspend fun openingCode(grant: ProviderCodec.Grant): CharArray {
        val prefix = host("opening-code", grant)
        return File(prefix.path + ".code").let { file ->
            file.readText().trim().toCharArray().also { assertTrue(file.delete()) }
        }
    }

    private fun adapter(): ProviderKitAdapter = ProviderKitAdapter { sealed, code, grant, server ->
        if (grant.request.provider == alpha) {
            // The host supplies independent expected bundle hashes, never the
            // device-bound opening code. The client fetched that over DPoP.
            runBlocking(Dispatchers.IO) { host("grant-metadata", grant) }
        }
        val expected = JSONObject(File(handoff, grant.grantId + ".json").readText())
        assertEquals(expected.getString("kit_sha256"), digest(sealed))
        assertEquals(expected.getInt("kit_size"), sealed.size)
        assertEquals(expected.getString("client_identity"), grant.clientIdentity)
        assertEquals(expected.getString("host"), server.host)
        assertEquals(expected.getInt("port"), server.port)
        assertEquals(expected.getString("server_key_sha256"), server.serverKeySha256)
        val validated = YumeProviderNative.validator(context).validate(sealed, code, grant, server)
        try {
            val files = KitBundle.decode(validated.bundle)
            try {
                val hashes = expected.getJSONObject("files")
                assertEquals(hashes.length(), files.size)
                files.forEach { assertEquals(hashes.getString(it.path), digest(it.bytes)) }
                val document = files.single { it.path == KitBundle.CONFIG_PATH }.bytes
                assertEquals(grant.clientIdentity, ProviderNativeIdentityVerifier(context).fingerprint(files, document))
            } finally { files.forEach { it.bytes.fill(0) } }
            evidence.put(grant.grantId, expected)
            validated
        } catch (error: Throwable) {
            validated.close()
            throw error
        }
    }

    private suspend fun refusal(code: String, action: suspend () -> Unit) {
        try { action(); fail("Expected refusal $code") }
        catch (refusal: ProviderCodec.Refusal) { assertEquals(code, refusal.code) }
    }

    @Test fun verifiedLinkRequiresEnabledHandling() {
        val expected = InstrumentationRegistry.getArguments().getString("expected") ?: "verified"
        if (expected == "disabled") {
            try { ProviderAppLinks.verify(context, registration); fail("Disabled handling was accepted") }
            catch (refusal: ProviderCodec.Refusal) { assertEquals("binding", refusal.code) }
        } else ProviderAppLinks.verify(context, registration)
    }

    @Test fun liveEnrollment() = runBlocking(Dispatchers.IO) {
        assertNotNull("Android trusted network time is required", AndroidProviderClock.now())
        AppVaultManager.lock()
        assertTrue(AppVaultManager.ensureReady(context))
        val store = yumeProviderStore(context)
        val stopped = mutableListOf<String>()
        val client = ProviderClient(context, registration, store, adapter(), connections =
            ProviderClient.Connections { synchronized(stopped) { stopped += it } }, http = transport())
        try {
            client.add(beta, null)
            val initialCatalog = client.catalog(beta)
            refusal("malformed") {
                client.install(beta, initialCatalog, "access", "edge") { grant ->
                    openingCode(grant).also { it[0] = if (it[0] == '0') '1' else '0' }
                }
            }
            refusal("state") { client.lease(beta).close() }
            val b1 = client.install(beta, initialCatalog, "access", "edge", ::openingCode)
            evidence.put("wrong_code_withheld_and_exact_retry", true)
            client.lease(beta).use { assertTrue(it.bundle.isNotEmpty()) }
            evidence.put("beta_initial", b1.grantId)

            // Manual import has its own native authority and never calls a provider.
            val manual = KitStore.get(context)
            val manualCode = File(handoff, "manual.code").readText().trim().toCharArray()
            val sealed = File(handoff, "manual.kit").readBytes()
            val imported = try { manual.importSealed(sealed, manualCode, "P3 manual") }
                finally { sealed.fill(0); manualCode.fill('\u0000') }
            assertTrue(imported is KitImport.Imported)
            val manualId = (imported as KitImport.Imported).kit.id
            val manualBlob = requireNotNull(AppVaultManager.readBlob(context, "kit-$manualId"))
            val manualExpected = JSONObject(File(handoff, "manual.json").readText())
            val manualHash = try {
                val files = KitBundle.decode(manualBlob)
                try {
                    val hashes = manualExpected.getJSONObject("files")
                    assertEquals(hashes.length(), files.size)
                    files.forEach { assertEquals(hashes.getString(it.path), digest(it.bytes)) }
                    assertEquals(manualExpected.getString("client_identity"), ProviderNativeIdentityVerifier(context)
                        .fingerprint(files, files.single { it.path == KitBundle.CONFIG_PATH }.bytes))
                } finally { files.forEach { it.bytes.fill(0) } }
                digest(manualBlob)
            } finally { manualBlob.fill(0) }
            evidence.put("manual", JSONObject(File(handoff, "manual.json").readText()))

            client.add(alpha, issuer)
            client.beginAuthorization(alpha)
            client.cancel(alpha)
            evidence.put("live_browser_attempt_cancelled", true)
            val returned = CompletableDeferred<Unit>()
            var delivered: String? = null // Process memory only, never written to the handoff or evidence.
            lateinit var callbackOwner: AutoCloseable
            callbackOwner = ProviderCallbacks.attach { uri ->
                // Callback delivery relaunches MainActivity immediately. Hand
                // callback ownership back before its application controller starts.
                callbackOwner.close()
                launch {
                    try {
                        refusal("binding") { client.callback(uri.replace(registration.callback, registration.callback + "/other")) }
                        refusal("binding") { client.callback(uri.replace("iss=", "iss=wrong")) }
                        client.callback(uri)
                        delivered = uri
                        refusal("state") { client.callback(uri) }
                        returned.complete(Unit)
                    } catch (error: Throwable) { returned.completeExceptionally(error) }
                }
            }
            callbackOwner.use {
                val intent = client.beginAuthorization(alpha)
                assertNotEquals(context.packageName, intent.`package`)
                evidence.put("browser_package", intent.`package`)
                instrumentation.runOnMainSync { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                host("browser-return")
                withTimeout(120_000) { returned.await() }
            }
            assertNotNull(delivered)
            delivered = null
            evidence.put("browser_return_and_replay_refusal", true)
            val alphaCatalog = client.catalog(alpha)
            assertEquals("US", alphaCatalog.services.single().servers.single().country)
            val noCodeHandoff: suspend (ProviderCodec.Grant) -> CharArray = {
                throw AssertionError("Device-bound enrollment requested a manual opening code")
            }
            val a1 = client.install(alpha, alphaCatalog, "access", "edge", noCodeHandoff)
            evidence.put("device_bound_code_delivery_without_host_code", true)
            evidence.put("operator_declared_country", "US")
            val betaRefreshWait = b1.refreshAfter - requireNotNull(AndroidProviderClock.now()).seconds
            if (betaRefreshWait > 0) delay(betaRefreshWait * 1000 + 1000)
            val b2 = client.install(beta, client.catalog(beta), "access", "edge", ::openingCode)
            assertEquals(b1.generation + 1, b2.generation)
            assertNotEquals(b1.clientIdentity, b2.clientIdentity)
            assertNotEquals(a1.clientIdentity, b2.clientIdentity)
            evidence.put("beta_refresh", b2.grantId)
            // The issuer's actual 60-second access token expires; transport remains valid.
            delay(62_000)
            client.lease(alpha).use { assertTrue(it.bundle.isNotEmpty()) }
            client.catalog(alpha) // Real rotating OAuth refresh over normal HTTPS.
            val alphaRefreshWait = a1.refreshAfter - requireNotNull(AndroidProviderClock.now()).seconds
            if (alphaRefreshWait > 0) delay(alphaRefreshWait * 1000 + 1000)
            val a2 = client.install(alpha, client.catalog(alpha), "access", "edge", noCodeHandoff)
            assertNotEquals(a1.clientIdentity, a2.clientIdentity)
            evidence.put("alpha_refresh", a2.grantId)
            evidence.put("account_expiry_preserves_transport", true)
            val untilExpiry = b2.expiresAt - requireNotNull(AndroidProviderClock.now()).seconds
            if (untilExpiry > 0) delay(untilExpiry * 1000 + 1000)
            refusal("revoked") { client.lease(beta).close() }
            refusal("unauthorized") { client.install(beta, client.catalog(beta), "access", "edge", ::openingCode) }
            evidence.put("actual_transport_expiry", true)
            store.read(alpha, store.epoch(alpha)!!)!!.use { snapshot ->
                val metadata = JSONObject(String(snapshot.metadata, Charsets.UTF_8))
                val fields = mapOf("v" to 1L, "provider" to alpha, "device_id" to metadata.getString("device"),
                    "audience" to "$alpha/provider/v1", "request_id" to ProviderAuthorization.randomIdentifier(),
                    "grant_id" to a2.grantId)
                val body = ProviderCodec.encode(fields)
                try {
                    val token = metadata.getJSONObject("account").getString("access")
                    val key = requireNotNull(AndroidProviderProofKeys().load(requireNotNull(store.epoch(alpha)).value))
                    val proof = ProviderProof.create(key, "POST", "$alpha/provider/v1/revoke",
                        requireNotNull(AndroidProviderClock.now()).seconds, token)
                    ProviderHttp().use { http ->
                        http.request(alpha, "$alpha/provider/v1/revoke", body,
                            ProviderBearer(alpha, token), proof = proof).fill(0)
                    }
                } finally { body.fill(0) }
            }
            refusal("unauthorized") { client.install(alpha, client.catalog(alpha), "access", "edge", noCodeHandoff) }
            refusal("revoked") { client.lease(alpha).close() }
            evidence.put("known_revocation_suspends", true)
            store.read(alpha, store.epoch(alpha)!!)!!.use { before ->
                host("offline-beta")
                assertFalse(client.remove(beta))
                assertNull(store.epoch(beta))
                store.read(alpha, store.epoch(alpha)!!)!!.use { after ->
                    assertArrayEquals(before.metadata, after.metadata)
                    assertEquals(before.bundles.keys, after.bundles.keys)
                    before.bundles.forEach { (id, bytes) -> assertArrayEquals(bytes, after.bundles.getValue(id)) }
                }
            }
            val manualAfter = requireNotNull(AppVaultManager.readBlob(context, "kit-$manualId"))
            try { assertEquals(manualHash, digest(manualAfter)) } finally { manualAfter.fill(0) }
            assertTrue(client.remove(alpha))
            assertNull(store.epoch(alpha))
            host("offline-all")
            manual.openSession(manualId)!!.use { assertEquals("offline.example", it.description.host) }
            val independentSealed = File(handoff, "manual.kit").readBytes()
            val independentCode = File(handoff, "manual.code").readText().trim().toCharArray()
            val independent = try { manual.importSealed(independentSealed, independentCode, "P3 offline manual") }
                finally { independentSealed.fill(0); independentCode.fill('\u0000') }
            assertTrue(independent is KitImport.Imported)
            manual.delete((independent as KitImport.Imported).kit.id)
            manual.delete(manualId)
            evidence.put("manual_import_with_all_services_stopped", true)
            evidence.put("offline_removal_isolated", true)
            evidence.put("online_removal", true)
            File(handoff, "evidence.json").writeText(evidence.toString(2))
        } finally {
            client.close()
            AppVaultManager.lock()
        }
    }
}
