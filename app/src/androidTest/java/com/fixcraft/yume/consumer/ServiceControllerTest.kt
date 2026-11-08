package com.fixcraft.yume.consumer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.core.KitFile
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitBundle
import com.fixcraft.yume.data.KitIndexCodec
import com.fixcraft.yume.data.KitSession
import com.fixcraft.yume.provider.yumeProviderStore
import com.fixcraft.yume.data.KitSummary
import com.fixcraft.yume.provider.ProviderBearer
import com.fixcraft.yume.provider.ProviderBundle
import com.fixcraft.yume.provider.ProviderClient
import com.fixcraft.yume.provider.ProviderClock
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.provider.ProviderKitAdapter
import com.fixcraft.yume.provider.ProviderRegistration
import com.fixcraft.yume.provider.ProviderStore
import com.fixcraft.yume.provider.ProviderTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real encrypted vault/store and controller lifetime with synthetic HTTP and kit
 * validation. The inert bundle tests session ownership only: it has no transport
 * credentials and never enters native validation, a browser, or a VPN interface.
 * Closing/reopening the controller is not Android process-death qualification.
 */
@RunWith(AndroidJUnit4::class)
class ServiceControllerTest {
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val identity = UUID.randomUUID().toString()
        private val directory = File(base.noBackupFilesDir, "service-controller-test-$identity").apply { check(mkdir()) }
        private val preferences = mutableSetOf<String>()
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolated = "$name-service-controller-test-$identity"
            preferences += isolated
            return super.getSharedPreferences(isolated, mode)
        }
        fun erase() {
            check(directory.deleteRecursively())
            preferences.forEach { check(deleteSharedPreferences(it)) }
        }
    }

    private class Clock : ProviderClock {
        private val started = SystemClock.elapsedRealtime()
        @Volatile var secondsOffset = 0L
        @Volatile var elapsedOffset = 0L
        @Volatile var trusted = true
        @Volatile var beforeRead: (() -> Unit)? = null
        override fun now(): ProviderClock.Reading? {
            beforeRead?.invoke()
            if (!trusted) return null
            val elapsed = SystemClock.elapsedRealtime()
            return ProviderClock.Reading(1_800_000_000L + (elapsed - started) / 1000 + secondsOffset,
                elapsed + elapsedOffset)
        }
        fun advance(seconds: Long) { secondsOffset += seconds; elapsedOffset += seconds * 1000 }
    }

    /** No credential validation success is claimed by this explicit test adapter. */
    private class InertKits : ProviderKitAdapter {
        val calls = AtomicInteger()
        override fun validate(sealed: ByteArray, openingCode: CharArray, grant: ProviderCodec.Grant,
                              server: ProviderCodec.Server): ProviderBundle {
            calls.incrementAndGet()
            assertEquals(grant.kitSha256, digest(sealed))
            assertTrue(openingCode.all { it == '7' })
            val document = JSONObject().put("endpoint", JSONObject().put("host", server.host).put("port", server.port))
                .put("services", org.json.JSONArray().put(JSONObject().put("name", "tcp").put("kind", "stream")))
            val files = listOf(KitFile("yume.json", false, document.toString().toByteArray()),
                KitFile("synthetic.txt", false, "inert session ownership fixture".toByteArray()))
            val encoded = try { KitBundle.encode(files) } finally { files.forEach { it.bytes.fill(0) } }
            return object : ProviderBundle {
                override val bundle = encoded
                override fun close() { bundle.fill(0) }
            }
        }
    }

    private class Transport(private val clock: Clock) : ProviderTransport {
        val calls = AtomicInteger()
        val grants = AtomicInteger()
        @Volatile var offline = false
        @Volatile var grantsDown = false
        @Volatile var closed = false
        override suspend fun request(origin: String, endpoint: String, body: ByteArray?, bearer: ProviderBearer?,
                                     limit: Int, responseType: String, requestType: String, proof: String?): ByteArray {
            check(!closed)
            check(proof == null) // Anonymous providers send their device credential without a proof.
            calls.incrementAndGet()
            ProviderCodec.endpoint(endpoint, origin)
            if (offline || (grantsDown && endpoint.endsWith("/grants"))) throw ProviderCodec.Refusal("unavailable")
            val seconds = clock.now()!!.seconds
            @Suppress("UNCHECKED_CAST")
            val fields = if (body == null) emptyMap() else ProviderCodec.decode(body) as Map<String, Any?>
            val result = when {
                endpoint.endsWith("/.well-known/yume-provider") -> ProviderCodec.encode(ProviderCodec.Descriptor(origin,
                    "Synthetic service", seconds + 3600, listOf("sealed-kit-1", "device-grants-1", "anonymous-device-1"),
                    listOf("vpn"), null).fields())
                endpoint.endsWith("/catalog") -> ProviderCodec.encode(fields + mapOf(
                    "issued_at" to seconds, "expires_at" to seconds + 300,
                    "services" to listOf(mapOf("id" to "service", "label" to "Synthetic service", "role" to "vpn",
                        "required" to emptyList<String>(), "servers" to listOf(mapOf("id" to "server", "label" to "Synthetic server",
                            "host" to "edge.example", "port" to 443L, "server_key_sha256" to "f".repeat(64)))))))
                endpoint.endsWith("/grants") -> ProviderCodec.encode(fields + mapOf(
                    "grant_id" to "grant-${grants.incrementAndGet()}", "generation" to 1L,
                    "issued_at" to seconds, "refresh_after" to seconds + 60, "expires_at" to seconds + 600,
                    "client_identity" to "a".repeat(64), "kit_sha256" to digest(SEALED), "kit_size" to SEALED.size.toLong()))
                endpoint.endsWith("/kit") -> SEALED.copyOf()
                endpoint.endsWith("/revoke") -> ProviderCodec.encode(fields + ("status" to "revoked"))
                else -> throw AssertionError("Unexpected synthetic endpoint")
            }
            assertTrue(result.size <= limit)
            return result
        }
        override fun close() { closed = true }
    }

    private lateinit var context: IsolatedContext
    private val clock = Clock()
    private val kits = InertKits()
    private lateinit var transport: Transport
    private lateinit var controller: ServiceController<KitSession>
    private val clientsCreated = AtomicInteger()
    private val opened = mutableListOf<ServiceController<KitSession>.OpenedService>()
    private val manualId = UUID.randomUUID().toString()
    private val manualBundle = "independent manual sentinel".toByteArray()
    private lateinit var manualIndex: ByteArray

    @Before
    fun prepare() {
        context = IsolatedContext(InstrumentationRegistry.getInstrumentation().targetContext)
        AppVaultManager.lock()
        assertTrue(AppVaultManager.ensureReady(context))
        manualIndex = KitIndexCodec.encode(listOf(KitSummary(manualId, "Manual", "manual.example", 443, 1)), manualId)
            .toString().toByteArray()
        AppVaultManager.writeBlob(context, "kit-$manualId", manualBundle)
        AppVaultManager.writeBlob(context, "kits", manualIndex)
        reopen()
    }

    private fun reopen(registration: ProviderRegistration? = null) {
        transport = Transport(clock)
        controller = ServiceController(context, registration, "vpn", YumeServiceAdapter(context), clock) { connections ->
            clientsCreated.incrementAndGet()
            val store = yumeProviderStore(context)
            try { ProviderClient(context, registration, store, kits, clock, connections, http = transport) }
            catch (error: Throwable) { store.close(); throw error }
        }
    }

    @After
    fun clean() {
        clock.beforeRead = null
        opened.forEach { it.close() }
        controller.close()
        AppVaultManager.lock()
        context.erase()
        manualBundle.fill(0)
        manualIndex.fill(0)
    }

    private suspend fun await(predicate: (ServiceUiState) -> Boolean): ServiceUiState =
        withTimeout(10_000) { controller.state.first(predicate) }

    private suspend fun catalog(provider: String = ALPHA): ProviderCodec.Catalog {
        controller.add(provider, null)
        await { state -> state.entries.any { it.provider == provider } && provider !in state.busy }
        controller.catalog(provider)
        return await { provider in it.catalogs && provider !in it.busy }.catalogs.getValue(provider)
    }

    private suspend fun prompt(provider: String = ALPHA): CodePrompt {
        val catalog = catalog(provider)
        controller.install(provider, catalog, "service", "server")
        return requireNotNull(await { it.codePrompt?.provider == provider }.codePrompt)
    }

    private suspend fun install(provider: String = ALPHA): ServiceSelection {
        val prompt = prompt(provider)
        controller.submitCode(prompt.id, CODE.toCharArray())
        return requireNotNull(await { it.selection?.provider == provider && provider !in it.busy }.selection)
    }

    private fun assertManualUntouched() {
        val bundle = requireNotNull(AppVaultManager.readBlob(context, "kit-$manualId"))
        val index = requireNotNull(AppVaultManager.readBlob(context, "kits"))
        try { assertArrayEquals(manualBundle, bundle); assertArrayEquals(manualIndex, index) }
        finally { bundle.fill(0); index.fill(0) }
    }

    private suspend fun open(selection: ServiceSelection): ServiceController<KitSession>.OpenedService =
        controller.open(selection).also { opened += it }

    private suspend fun refusal(action: suspend () -> Unit): String = try {
        action()
        throw AssertionError("Expected a provider refusal")
    } catch (refused: ProviderCodec.Refusal) { refused.code }

    @Test
    fun absentAndExplicitManualRestartChoicesDoNotCreateAProviderClient() {
        assertNull(controller.selectedForRestart())
        controller.selectManual()
        assertNull(controller.selectedForRestart())
        assertEquals(0, clientsCreated.get())
        assertManualUntouched()
    }

    @Test
    fun provisionedSelectionSurvivesControllerReopenWithoutNetworkOrManualCopy() = runBlocking {
        val selected = install()
        assertEquals(selected, controller.selectedForRestart())
        controller.close()
        AppVaultManager.lock()
        reopen()
        assertEquals(selected, controller.selectedForRestart())
        assertEquals(0, transport.calls.get())
        controller.reload()
        val loaded = await { it.loaded && "local" !in it.busy }
        assertEquals(selected, loaded.selection)
        assertFalse(loaded.manual)
        assertEquals(selected, loaded.entries.single().selection)
        assertEquals(0, transport.calls.get())
        assertManualUntouched()
    }

    @Test
    fun corruptSelectionRefusesRestartAndReloadWithoutManualFallbackOrRewrite() = runBlocking {
        install()
        val malformed = "{broken authenticated selection".toByteArray()
        try { AppVaultManager.writeBlob(context, SELECTION, malformed) } finally { malformed.fill(0) }
        val stored = File(context.noBackupFilesDir, "app-vault/$SELECTION.bin").readBytes()
        controller.close()
        reopen()
        assertThrows(Exception::class.java) { controller.selectedForRestart() }
        controller.reload()
        val failed = await { it.error == "storage" && "local" !in it.busy }
        assertFalse(failed.loaded)
        assertFalse(failed.manual)
        assertNull(failed.selection)
        assertArrayEquals(stored, File(context.noBackupFilesDir, "app-vault/$SELECTION.bin").readBytes())
        assertManualUntouched()
    }

    @Test
    fun tamperedSelectionCiphertextRefusesRestartWithoutManualFallback() = runBlocking {
        install()
        val file = File(context.noBackupFilesDir, "app-vault/$SELECTION.bin")
        val tampered = file.readBytes()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        file.writeBytes(tampered)
        controller.close()
        reopen()
        assertThrows(Exception::class.java) { controller.selectedForRestart() }
        controller.reload()
        await { it.error == "storage" && "local" !in it.busy }
        assertFalse(controller.state.value.manual)
        assertArrayEquals(tampered, file.readBytes())
        assertManualUntouched()
    }

    @Test
    fun corruptProviderStorageCannotTurnThePersistedProviderChoiceIntoManual() = runBlocking {
        val selected = install()
        controller.close()
        val manifest = File(context.noBackupFilesDir, "app-vault/provider-${digest(ALPHA.toByteArray())}-manifest.bin")
        val tampered = manifest.readBytes()
        tampered[tampered.lastIndex] = (tampered.last().toInt() xor 1).toByte()
        manifest.writeBytes(tampered)
        reopen()
        assertEquals(selected, controller.selectedForRestart())
        controller.reload()
        await { it.error == "storage" && "local" !in it.busy }
        try { controller.open(selected).close(); throw AssertionError("Corrupt provider storage was accepted") }
        catch (_: Exception) { }
        assertFalse(controller.state.value.manual)
        assertArrayEquals(tampered, manifest.readBytes())
        assertManualUntouched()
    }

    @Test
    fun lockedSelectionRefusesRestartAndRunWithoutChangingManualRecords() = runBlocking {
        val selected = install()
        AppVaultManager.enablePassword(context, PASSWORD).getOrThrow()
        AppVaultManager.lock()
        assertEquals("storage", assertThrows(ProviderCodec.Refusal::class.java) { controller.selectedForRestart() }.code)
        controller.reload()
        await { it.error == "storage" && "local" !in it.busy }
        try { controller.open(selected).close(); throw AssertionError("Locked provider run was accepted") }
        catch (_: IllegalStateException) { }
        assertFalse(controller.state.value.manual)
        AppVaultManager.unlockWithPassword(context, PASSWORD).getOrThrow()
        assertEquals(selected, controller.selectedForRestart())
        assertManualUntouched()
    }

    @Test
    fun aManualChoiceDuringProvisioningCannotBeOverwrittenByLateCompletion() = runBlocking {
        val prompt = prompt()
        controller.selectManual()
        controller.submitCode(prompt.id, CODE.toCharArray())
        await { it.codePrompt == null && ALPHA !in it.busy }
        assertTrue(controller.state.value.manual)
        assertNull(controller.state.value.selection)
        assertNull(controller.selectedForRestart())
        assertManualUntouched()
    }

    @Test
    fun aManualChoiceWinsAgainstAPausedProviderSelection() = runBlocking {
        val selected = install()
        controller.selectManual()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        clock.beforeRead = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            controller.select(selected)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            controller.selectManual()
        } finally { clock.beforeRead = null; release.countDown() }
        await { it.error == "cancelled" && "selection" !in it.busy }
        assertTrue(controller.state.value.manual)
        assertNull(controller.selectedForRestart())
        assertManualUntouched()
    }

    @Test
    fun theLatestProviderChoiceReplacesAPausedSelection() = runBlocking {
        val alpha = install(ALPHA)
        val beta = install(BETA)
        controller.selectManual()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        clock.beforeRead = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            controller.select(alpha)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue("selection" in controller.state.value.busy)
            controller.select(beta)
            assertTrue("selection" in controller.state.value.busy)
        } finally { clock.beforeRead = null; release.countDown() }
        await { it.selection == beta && "selection" !in it.busy }
        assertEquals(beta, controller.selectedForRestart())
        assertNull(controller.state.value.error)
        assertManualUntouched()
    }

    @Test
    fun cancellationClearsThePromptWipesLateCodesAndKeepsManualData() = runBlocking {
        val prompt = prompt()
        controller.cancel(ALPHA)
        await { it.codePrompt == null && ALPHA !in it.busy }
        val late = CODE.toCharArray()
        controller.submitCode(prompt.id, late)
        assertTrue(late.all { it == '\u0000' })
        assertEquals(0, kits.calls.get())
        controller.reload()
        val loaded = await { it.entries.singleOrNull()?.suspended == true && "local" !in it.busy }
        assertTrue(loaded.entries.single().suspended)
        assertNull(loaded.entries.single().selection)
        assertManualUntouched()
    }

    @Test
    fun invalidCodeLengthIsWipedWhileTheCurrentPromptRemainsUsable() = runBlocking {
        val prompt = prompt()
        val short = CharArray(24) { '7' }
        controller.submitCode(prompt.id, short)
        assertTrue(short.all { it == '\u0000' })
        assertEquals(prompt, controller.state.value.codePrompt)
        controller.submitCode(prompt.id, CODE.toCharArray())
        await { it.selection != null && ALPHA !in it.busy }
        assertEquals(1, kits.calls.get())
        assertManualUntouched()
    }

    @Test
    fun removingDuringThePromptCannotPublishASelectionOrAcceptItsCode() = runBlocking {
        val prompt = prompt()
        transport.offline = true
        controller.remove(ALPHA)
        await { it.removed && it.entries.isEmpty() && it.codePrompt == null && ALPHA !in it.busy && "remove:$ALPHA" !in it.busy }
        val late = CODE.toCharArray()
        controller.submitCode(prompt.id, late)
        assertTrue(late.all { it == '\u0000' })
        assertEquals(0, kits.calls.get())
        assertNull(controller.state.value.selection)
        assertManualUntouched()
    }

    @Test
    fun removalInvalidatesOnlyItsProviderPermitAndNeverSelectsManual() = runBlocking {
        val alpha = install(ALPHA)
        val beta = install(BETA)
        val alphaRun = open(alpha)
        val betaRun = open(beta)
        assertTrue(alphaRun.permit.valid())
        assertTrue(betaRun.permit.valid())
        transport.offline = true
        controller.remove(ALPHA)
        assertFalse(alphaRun.permit.valid())
        await { it.removed && it.entries.none { entry -> entry.provider == ALPHA } && "remove:$ALPHA" !in it.busy }
        assertTrue(betaRun.permit.valid())
        assertFalse(controller.state.value.manual)
        assertEquals(beta, controller.selectedForRestart())
        assertEquals("state", refusal { controller.open(alpha).close() })
        assertManualUntouched()
    }

    @Test
    fun aRemovedProvidersChoiceShowsNoRouteAndNeverBecomesManual() = runBlocking {
        val selected = install()
        transport.offline = true
        controller.remove(ALPHA)
        val removed = await { it.removed && it.entries.isEmpty() && "remove:$ALPHA" !in it.busy }
        assertNull(removed.selection)
        assertFalse(removed.manual)
        // Restart still reads the stored choice and refuses it.
        assertEquals(selected, controller.selectedForRestart())
        assertEquals("state", refusal { controller.open(selected).close() })
        // Adding the provider again starts a new enrollment. The old choice stays unusable.
        transport.offline = false
        controller.add(ALPHA, null)
        val added = await { state -> state.entries.any { it.provider == ALPHA } && ALPHA !in state.busy }
        assertNull(added.selection)
        assertFalse(added.manual)
        assertManualUntouched()
    }

    @Test
    fun anAnonymousProviderNeverRenewsWithoutAPerson() = runBlocking {
        // Its opening code is typed by hand, so renewal is the person's step.
        assertNull(controller.renewAfter(install()))
    }

    @Test
    fun aFailedStepShowsTheSuspensionItSaved() = runBlocking {
        install()
        val catalog = controller.state.value.catalogs.getValue(ALPHA)
        clock.advance(61)
        transport.grantsDown = true
        controller.install(ALPHA, catalog, "service", "server")
        val failed = await { it.error == "unavailable" && ALPHA !in it.busy }
        assertTrue(failed.entries.single { it.provider == ALPHA }.suspended)
        assertManualUntouched()
    }

    @Test
    fun substitutedServerBindingCannotMaterializeARunOrSelectManual() = runBlocking {
        val selected = install()
        assertEquals("binding", refusal { controller.open(selected.copy(host = "substitution.example")).close() })
        assertEquals(selected, controller.selectedForRestart())
        assertFalse(controller.state.value.manual)
        assertManualUntouched()
    }

    @Test
    fun reEnrollmentCannotReviveTheOldSelectedEpoch() = runBlocking {
        val old = install()
        controller.remove(ALPHA)
        await { it.removed && it.entries.isEmpty() && "remove:$ALPHA" !in it.busy }
        val replacement = install()
        assertTrue(old.epoch != replacement.epoch)
        assertEquals("binding", refusal { controller.open(old).close() })
        open(replacement).use { assertTrue(it.permit.valid()) }
        assertEquals(replacement, controller.selectedForRestart())
        assertManualUntouched()
    }

    @Test
    fun lockedRemovalInvalidatesTheRunAndDoesNotFallBackToManualOnUnlock() = runBlocking {
        val selected = install()
        val run = open(selected)
        AppVaultManager.enablePassword(context, PASSWORD).getOrThrow()
        AppVaultManager.lock()
        controller.remove(ALPHA)
        assertFalse(run.permit.valid())
        await { it.removed && "remove:$ALPHA" !in it.busy }
        AppVaultManager.unlockWithPassword(context, PASSWORD).getOrThrow()
        assertEquals(selected, controller.selectedForRestart())
        assertEquals("state", refusal { controller.open(selected).close() })
        assertManualUntouched()
    }

    @Test
    fun expiryAndClockFailureInvalidateCapturedRunAuthority() = runBlocking {
        val selected = install()
        val run = open(selected)
        assertTrue(run.permit.valid())
        clock.trusted = false
        assertFalse(run.permit.valid())
        clock.trusted = true
        clock.advance(601)
        assertFalse(run.permit.valid())
        assertEquals("revoked", refusal { controller.open(selected).close() })
        assertManualUntouched()
    }

    @Test
    fun closingTheControllerInvalidatesPermitsAndClosingSessionsErasesTheirDirectories() = runBlocking {
        val run = open(install())
        val directory = run.session.directory
        assertTrue(directory.isDirectory)
        assertNotNull(File(directory, "synthetic.txt").takeIf { it.isFile })
        controller.close()
        assertFalse(run.permit.valid())
        run.close()
        assertFalse(directory.exists())
        assertManualUntouched()
    }

    @Test
    fun absentRegistrationRefusesBrowserAuthorizationBeforeClientCreation() = runBlocking {
        assertEquals("unsupported", refusal { controller.authorize(ALPHA) })
        assertEquals(0, clientsCreated.get())
        assertNull(controller.state.value.authorizing)
        assertEquals("unsupported", controller.state.value.error)
        assertManualUntouched()
    }

    @Test
    fun aRefusedBrowserLaunchDoesNotRetainControllerOwnership() = runBlocking {
        controller.close()
        reopen(ProviderRegistration(context.packageName, "synthetic-controller-client", "https://client.example/oauth/callback"))
        catalog()
        repeat(2) {
            assertEquals("binding", refusal { controller.authorize(ALPHA) })
            assertNull(controller.state.value.authorizing)
            assertEquals("binding", controller.state.value.error)
        }
        assertManualUntouched()
    }

    private companion object {
        const val ALPHA = "https://alpha.example"
        const val BETA = "https://beta.example"
        const val SELECTION = "service-selection"
        const val PASSWORD = "synthetic-controller-test-password"
        const val CODE = "7777777777777777777777777"
        val SEALED = ByteArray(1068) { 7 }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
