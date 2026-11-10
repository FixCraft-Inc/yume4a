package com.fixcraft.yume.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitIndexCodec
import com.fixcraft.yume.data.KitSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Real Android Keystore/filesystem and coroutine lifecycle. HTTP responses,
 * account metadata and kit adapter are synthetic; these are not TLS, browser,
 * native identity or real-operator qualification tests.
 */
@RunWith(AndroidJUnit4::class)
class ProviderClientTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val identity = UUID.randomUUID().toString()
        private val directory = File(base.noBackupFilesDir, "provider-client-test-$identity").apply { check(mkdir()) }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$name-provider-client-test-$identity"
            preferences += isolated
            return super.getSharedPreferences(isolated, mode)
        }
        fun erase() {
            check(directory.deleteRecursively())
            preferences.forEach { check(deleteSharedPreferences(it)) }
        }
    }

    private class Clock : ProviderClock {
        @Volatile var seconds = 1_800_000_000L
        @Volatile var elapsedMillis = 100_000L
        @Volatile var trusted = true
        @Volatile var beforeRead: (() -> Unit)? = null
        override fun now(): ProviderClock.Reading? {
            beforeRead?.invoke()
            return if (trusted) ProviderClock.Reading(seconds, elapsedMillis) else null
        }
        fun advance(seconds: Long) { this.seconds += seconds; elapsedMillis += seconds * 1000 }
    }

    /** This adapter deliberately asserts no native format or fingerprint correctness. */
    private class SyntheticKits : ProviderKitAdapter {
        var beforeValidation: (() -> Unit)? = null
        val calls = AtomicInteger()
        override fun validate(sealed: ByteArray, openingCode: CharArray, grant: ProviderCodec.Grant,
                              server: ProviderCodec.Server): ProviderBundle {
            calls.incrementAndGet()
            beforeValidation?.invoke()
            assertEquals(grant.kitSize, sealed.size)
            assertEquals(grant.kitSha256, digest(sealed))
            assertEquals(grant.request.serverId, server.id)
            openingCode.fill('\u0000')
            return object : ProviderBundle {
                override val bundle = "synthetic-bundle-${grant.request.provider}-${grant.grantId}".toByteArray()
                override fun close() { bundle.fill(0) }
            }
        }
    }

    private class SyntheticTransport(private val clock: Clock) : ProviderTransport {
        val catalogBearers = mutableMapOf<String, String>()
        val alphaProofs = AtomicInteger()
        val unprovenAlpha = mutableListOf<String>()
        val grants = mutableListOf<Map<String, Any?>>()
        val revocations = mutableListOf<String>()
        val refreshCalls = AtomicInteger()
        val codeCalls = AtomicInteger()
        var offline = false
        var failRefresh = false
        var failGrants = false
        var refreshEntered: CompletableDeferred<Unit>? = null
        var refreshRelease: CompletableDeferred<Unit>? = null
        private var closed = false

        @Suppress("UNCHECKED_CAST")
        private fun fields(bytes: ByteArray): Map<String, Any?> {
            val value = ProviderCodec.decode(bytes)
            assertTrue(value is Map<*, *> && value.keys.all { it is String })
            return value as Map<String, Any?>
        }

        fun descriptor(provider: String): ProviderCodec.Descriptor = ProviderCodec.Descriptor(provider,
            if (provider == ALPHA) "Synthetic alpha" else "Synthetic beta", clock.seconds + 3600,
            if (provider == ALPHA) listOf("sealed-kit-1", "device-grants-1", "browser-pkce-s256", ProviderCodec.DEVICE_BOUND)
            else listOf("sealed-kit-1", "device-grants-1", "anonymous-device-1"),
            listOf("vpn"), if (provider == ALPHA) ProviderCodec.Auth(ISSUER, "$ISSUER/authorize", "$ISSUER/token", "$ISSUER/revoke",
                REGISTRATION.clientId, REGISTRATION.callback) else null)

        override suspend fun request(origin: String, endpoint: String, body: ByteArray?, bearer: ProviderBearer?,
                                     limit: Int, responseType: String, requestType: String, proof: String?): ByteArray {
            assertFalse(closed)
            ProviderCodec.endpoint(endpoint, origin)
            assertTrue(bearer == null || bearer.origin == origin)
            // Proof contents are verified by the shared library's own tests.
            // Here every alpha token or API request must carry one, and beta none.
            val alpha = endpoint == "$ISSUER/token" || (origin == ALPHA && !endpoint.endsWith("/.well-known/yume-provider"))
            if (alpha && proof != null) alphaProofs.incrementAndGet()
            if (alpha && proof == null) synchronized(unprovenAlpha) { unprovenAlpha += endpoint }
            if (origin == BETA) assertNull(proof)
            assertTrue(body == null || body.size <= ProviderCodec.SMALL_LIMIT)
            if (endpoint.endsWith("/revoke")) revocations += endpoint
            if (offline) throw ProviderCodec.Refusal("unavailable")
            val result = when {
                endpoint.endsWith("/.well-known/yume-provider") -> ProviderCodec.encode(descriptor(origin).fields())
                endpoint == "$ISSUER/token" -> {
                    val form = body!!.toString(Charsets.US_ASCII).split('&').associate { part ->
                        val pair = part.split('=', limit = 2)
                        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
                    }
                    assertEquals("$ALPHA/provider/v1", form["resource"])
                    assertEquals(REGISTRATION.clientId, form["client_id"])
                    if (form["grant_type"] == "refresh_token") {
                        refreshCalls.incrementAndGet()
                        assertEquals("SYNTHETIC-REFRESH-ONE", form["refresh_token"])
                        refreshEntered?.complete(Unit)
                        refreshRelease?.await()
                        if (failRefresh) throw ProviderCodec.Refusal("unavailable")
                    } else codeCalls.incrementAndGet()
                    ProviderCodec.encode(mapOf("token_type" to "DPoP", "access_token" to "SYNTHETIC-ACCESS-TWO",
                        "refresh_token" to "SYNTHETIC-REFRESH-TWO", "expires_in" to 60L, "refresh_expires_in" to 3600L,
                        "scope" to "provider.enroll"))
                }
                endpoint.endsWith("/catalog") -> {
                    val fields = fields(body!!)
                    synchronized(catalogBearers) { catalogBearers[origin] = bearer!!.value }
                    ProviderCodec.encode(fields + mapOf("issued_at" to clock.seconds, "expires_at" to clock.seconds + 300,
                        "services" to listOf(mapOf("id" to "service", "label" to "Synthetic service", "role" to "vpn",
                            "required" to emptyList<String>(), "servers" to listOf(mapOf("id" to "server", "label" to "Synthetic server",
                                "host" to if (origin == ALPHA) "edge.alpha.example" else "edge.beta.example", "port" to 443L,
                                "server_key_sha256" to "f".repeat(64)))))))
                }
                endpoint.endsWith("/grants") -> {
                    val fields = fields(body!!)
                    synchronized(grants) { grants += fields }
                    if (failGrants) throw ProviderCodec.Refusal("unavailable")
                    val sealed = ByteArray(1068) { if (origin == ALPHA) 1 else 2 }
                    ProviderCodec.encode(fields + mapOf("grant_id" to "grant-${grants.size}", "generation" to 1L,
                        "issued_at" to clock.seconds, "refresh_after" to clock.seconds + 60, "expires_at" to clock.seconds + 600,
                        "client_identity" to if (origin == ALPHA) "a".repeat(64) else "b".repeat(64),
                        "kit_sha256" to digest(sealed), "kit_size" to sealed.size.toLong()))
                }
                endpoint.endsWith("/kit") -> {
                    val request = synchronized(grants) { grants.last { it["provider"] == origin } }
                    assertEquals(request["device_id"], bearer!!.deviceId)
                    ByteArray(1068) { if (origin == ALPHA) 1 else 2 }
                }
                endpoint.endsWith("/opening-code") -> ProviderCodec.encode(fields(body!!) + ("opening_code" to "7777777777777777777777777"))
                endpoint == "$ISSUER/revoke" -> ProviderCodec.encode(emptyMap<String, Any?>())
                endpoint.endsWith("/revoke") -> ProviderCodec.encode(fields(body!!) + ("status" to "revoked"))
                else -> throw AssertionError("Unexpected synthetic endpoint")
            }
            assertTrue(result.size <= limit)
            return result
        }
        override fun close() { closed = true }
    }

    private lateinit var context: IsolatedContext
    private val clock = Clock()
    private val stopped = mutableListOf<String>()
    private val kits = SyntheticKits()
    private lateinit var store: ProviderStore
    private lateinit var transport: SyntheticTransport
    private lateinit var authorization: ProviderAuthorization
    private lateinit var client: ProviderClient
    private var storageFault: ProviderStore.Checkpoint? = null

    @Before
    fun prepare() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        AppVaultManager.lock()
        assertTrue(AppVaultManager.ensureReady(context))
        reopen()
    }

    private fun reopen() {
        store = ProviderStore(File(context.noBackupFilesDir, "provider-state"), YumeProviderBackend(context)) { point -> if (point == storageFault) throw IOException("Injected provider write failure") }
        transport = SyntheticTransport(clock)
        authorization = ProviderAuthorization(clock)
        client = ProviderClient(context, REGISTRATION, store, kits, clock,
            ProviderClient.Connections { provider -> synchronized(stopped) { stopped += provider } },
            http = transport, authorization = authorization)
    }

    @After
    fun clean() {
        clock.beforeRead = null
        storageFault = null
        client.close()
        assertTrue(transport.unprovenAlpha.isEmpty())
        AndroidProviderProofKeys().retain(emptySet())
        AppVaultManager.lock()
        context.erase()
    }

    private suspend fun outcome(block: suspend () -> Unit): String = try {
        block()
        "success"
    } catch (refusal: ProviderCodec.Refusal) { refusal.code }

    private fun metadata(provider: String): JSONObject = store.read(provider, store.epoch(provider)!!)!!.use {
        JSONObject(it.metadata.toString(Charsets.UTF_8))
    }

    /** Seeds a device-bound account without pretending that an Android browser signed it in. */
    private fun seedAccount(expired: Boolean = false) {
        val epoch = store.epoch(ALPHA)!!
        AndroidProviderProofKeys().create(epoch.value)
        store.read(ALPHA, epoch)!!.use { snapshot ->
            val metadata = JSONObject(snapshot.metadata.toString(Charsets.UTF_8))
            metadata.put("account", JSONObject().put("type", "DPoP").put("access", "SYNTHETIC-ACCESS-ONE").put("refresh", "SYNTHETIC-REFRESH-ONE")
                .put("accessUntil", clock.seconds + if (expired) -1 else 60).put("refreshUntil", clock.seconds + 3600))
            val bytes = metadata.toString().toByteArray()
            try { store.commit(ALPHA, epoch, bytes, snapshot.bundles) } finally { bytes.fill(0) }
        }
    }

    private suspend fun addAlpha(expired: Boolean = false) {
        client.add(ALPHA, ISSUER)
        seedAccount(expired)
    }

    private suspend fun prepare(provider: String, code: () -> CharArray = { "7777777777777777777777777".toCharArray() }) =
        client.prepare(provider, renew = true) { code() }

    /** Chooses the synthetic server, as a tap does, and prepares it, as Connect does. */
    private suspend fun install(provider: String): ProviderCodec.Grant {
        client.choose(provider, client.catalog(provider), "service", "server")
        return prepare(provider).grant
    }

    @Test
    fun twoProviderAuthoritiesRemainIndependentAndOfflineRemovalPreservesManualRecords() = runBlocking {
        addAlpha()
        client.add(BETA, null)
        val alpha = install(ALPHA)
        val beta = install(BETA)
        assertNotEquals(alpha.clientIdentity, beta.clientIdentity)
        assertNotEquals(transport.catalogBearers.getValue(ALPHA), transport.catalogBearers.getValue(BETA))
        assertEquals("SYNTHETIC-ACCESS-ONE", transport.catalogBearers.getValue(ALPHA))
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(transport.catalogBearers.getValue(BETA)))
        val betaBefore = store.read(BETA, store.epoch(BETA)!!)!!
        betaBefore.use {
            val manualId = UUID.randomUUID().toString()
            val manualBytes = "synthetic-independent-manual-bundle".toByteArray()
            val index = KitIndexCodec.encode(listOf(KitSummary(manualId, "Manual", "manual.example", 443, 1)), manualId)
                .toString().toByteArray()
            try {
                AppVaultManager.writeBlob(context, "kit-$manualId", manualBytes)
                AppVaultManager.writeBlob(context, "kits", index)
                transport.offline = true
                assertFalse(client.remove(ALPHA))
                assertNull(store.epoch(ALPHA))
                assertTrue(stopped.contains(ALPHA))
                assertTrue(transport.revocations.contains("$ALPHA/provider/v1/revoke"))
                assertTrue(transport.revocations.contains("$ISSUER/revoke"))
                store.read(BETA, store.epoch(BETA)!!)!!.use { after ->
                    assertArrayEquals(betaBefore.metadata, after.metadata)
                    assertEquals(betaBefore.bundles.keys, after.bundles.keys)
                    betaBefore.bundles.forEach { (id, bytes) -> assertArrayEquals(bytes, after.bundles.getValue(id)) }
                }
                assertArrayEquals(manualBytes, AppVaultManager.readBlob(context, "kit-$manualId"))
                assertArrayEquals(index, AppVaultManager.readBlob(context, "kits"))
                client.lease(BETA).use { assertTrue(it.bundle.isNotEmpty()) }
                val removedPrefix = "provider-${digest(ALPHA.toByteArray())}-"
                assertTrue(File(context.noBackupFilesDir, "app-vault").listFiles().orEmpty().none { it.name.startsWith(removedPrefix) })
            } finally { manualBytes.fill(0); index.fill(0) }
        }
    }

    @Test
    fun accountRefreshIsSerializedAndAbsenceIsDurableBeforeSingleUse() = runBlocking {
        addAlpha(expired = true)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        transport.refreshEntered = entered
        transport.refreshRelease = release
        val first = async(start = CoroutineStart.UNDISPATCHED) { client.catalog(ALPHA) }
        withTimeout(5_000) { entered.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { client.catalog(ALPHA) }
        assertEquals(1, transport.refreshCalls.get())
        assertFalse(metadata(ALPHA).has("account"))
        release.complete(Unit)
        withTimeout(5_000) { listOf(first, second).awaitAll() }
        assertEquals(1, transport.refreshCalls.get())
        val account = metadata(ALPHA).getJSONObject("account")
        assertEquals("SYNTHETIC-REFRESH-TWO", account.getString("refresh"))
        assertEquals("SYNTHETIC-ACCESS-TWO", transport.catalogBearers.getValue(ALPHA))
    }

    @Test
    fun lostRefreshReplyCannotReplayTheOldTokenAfterReopen() = runBlocking {
        addAlpha(expired = true)
        transport.failRefresh = true
        assertEquals("unavailable", outcome { client.catalog(ALPHA) })
        assertFalse(metadata(ALPHA).has("account"))
        assertEquals("unauthorized", outcome { client.catalog(ALPHA) })
        assertEquals(1, transport.refreshCalls.get())
        client.close()
        reopen()
        assertEquals("unauthorized", outcome { client.catalog(ALPHA) })
        assertEquals(0, transport.refreshCalls.get())
    }

    @Test
    fun aConsumedCallbackQueuedBeforeCancellationCannotAdoptTheNewGeneration() = runBlocking {
        addAlpha(expired = true)
        val record = metadata(ALPHA)
        val binding = ProviderCodec.Context(ALPHA, record.getString("device"), "vpn", REGISTRATION.clientId, REGISTRATION.callback, ISSUER)
        val state = ProviderTestAccess.beginState(authorization, binding, transport.descriptor(ALPHA),
            store.epoch(ALPHA)!!.value, REGISTRATION, 0)
        val redirect = "${REGISTRATION.callback}?iss=${URLEncoder.encode(ISSUER, "UTF-8")}&state=$state&code=SYNTHETIC-CODE"
        val entered = CompletableDeferred<Unit>()
        transport.refreshEntered = entered
        transport.refreshRelease = CompletableDeferred()
        val active = launch(start = CoroutineStart.UNDISPATCHED) { client.catalog(ALPHA) }
        withTimeout(5_000) { entered.await() }
        val queued = async(start = CoroutineStart.UNDISPATCHED) { outcome { client.callback(redirect) } }
        client.cancel(ALPHA)
        withTimeout(5_000) { active.join() }
        assertEquals("cancelled", withTimeout(5_000) { queued.await() })
        assertEquals(0, transport.codeCalls.get())
        assertFalse(metadata(ALPHA).has("account"))
    }

    @Test
    fun removalPreventsAPausedLeaseFromPublishingCredentialBytes() = runBlocking {
        client.add(BETA, null)
        install(BETA)
        val reads = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicReference<ProviderClient.Lease?>()
        clock.beforeRead = {
            if (reads.incrementAndGet() == 2) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        val lease = launch(Dispatchers.Default) {
            try { published.set(client.lease(BETA)) } catch (_: ProviderCodec.Refusal) { }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            transport.offline = true
            assertFalse(client.remove(BETA))
            assertNull(store.epoch(BETA))
        } finally {
            clock.beforeRead = null
            release.countDown()
        }
        withTimeout(5_000) { lease.join() }
        published.get()?.close()
        assertNull(published.get())
        assertTrue(stopped.contains(BETA))
    }

    @Test
    fun untrustedClockAndRollbackStopExistingTransportAuthority() = runBlocking {
        client.add(BETA, null)
        install(BETA)
        client.lease(BETA).close()
        clock.advance(5)
        client.lease(BETA).close()
        synchronized(stopped) { stopped.clear() }
        clock.seconds--
        assertEquals("stale", outcome { client.lease(BETA).close() })
        assertTrue(stopped.contains(BETA))
        clock.seconds++
        synchronized(stopped) { stopped.clear() }
        clock.trusted = false
        assertEquals("stale", outcome { client.lease(BETA).close() })
        assertTrue(stopped.contains(BETA))
    }

    @Test
    fun accountExpiryAndFailedRefreshDoNotInventTransportExpiry() = runBlocking {
        addAlpha()
        val grant = install(ALPHA)
        clock.advance(61)
        assertTrue(clock.seconds < grant.expiresAt)
        client.lease(ALPHA).close()
        transport.failRefresh = true
        assertEquals("unavailable", outcome { client.catalog(ALPHA) })
        client.lease(ALPHA).use { assertEquals(grant.expiresAt, it.expiresAt) }
    }

    @Test
    fun anUnansweredGrantRequestIsRepeatedExactlyAcrossReopenForEveryExplicitRetry() = runBlocking {
        client.add(BETA, null)
        client.choose(BETA, client.catalog(BETA), "service", "server")
        transport.failGrants = true
        assertEquals("unavailable", outcome { prepare(BETA) { CharArray(25) } })
        val first = transport.grants.single()
        assertEquals(0, kits.calls.get())
        client.close()
        reopen()
        transport.failGrants = true
        // Each explicit attempt repeats the stored body. No new grant request is ever minted.
        repeat(2) { assertEquals("unavailable", outcome { prepare(BETA) { CharArray(25) } }) }
        assertEquals(listOf(first, first), transport.grants)
        transport.failGrants = false
        prepare(BETA)
        assertEquals(listOf(first, first, first), transport.grants)
        assertEquals(1, kits.calls.get())
    }

    @Test
    fun failedFinalStorageCommitCannotPublishAValidatedReplacement() = runBlocking {
        client.add(BETA, null)
        client.choose(BETA, client.catalog(BETA), "service", "server")
        kits.beforeValidation = { storageFault = ProviderStore.Checkpoint.AfterStageSync }
        try {
            prepare(BETA)
            throw AssertionError("Expected injected storage failure")
        } catch (_: IOException) { }
        storageFault = null
        // Nothing was published, and the unanswered request stays for an explicit retry.
        assertFalse(metadata(BETA).getBoolean("suspended"))
        assertTrue(metadata(BETA).isNull("grant"))
        assertFalse(metadata(BETA).isNull("pending"))
        assertEquals("state", outcome { client.lease(BETA).close() })
        assertEquals(1, kits.calls.get())
    }

    private companion object {
        const val ALPHA = "https://alpha.example"
        const val BETA = "https://beta.example"
        const val ISSUER = "https://login.alpha.example"
        val REGISTRATION = ProviderRegistration("com.fixcraft.yume", "synthetic-client", "https://client.example/oauth/callback")
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
