package com.fixcraft.yume.consumer

import android.content.Intent
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.MainActivity
import com.fixcraft.yume.YumeApplication
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettingsStore
import com.fixcraft.yume.data.AppVaultManager
import com.fixcraft.yume.data.KitStore
import com.fixcraft.yume.i18n.MessageId
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.provider.AndroidProviderClock
import com.fixcraft.yume.provider.ProviderCallbacks
import com.fixcraft.yume.provider.RequiresNativeFixtures
import com.fixcraft.yume.vpn.VpnFailure
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import com.fixcraft.yume.vpn.YumeVpnService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/** Explicit host-owned HTTPS/browser/native/UI gate, excluded from ordinary instrumentation. */
@RequiresNativeFixtures
@RunWith(AndroidJUnit4::class)
class ServiceLiveIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val services get() = (context.applicationContext as YumeApplication).services
    private val settings get() = AppSettingsStore.get(context)
    private val handoff = File(context.noBackupFilesDir, "consumer-live-handoff")
    private val config by lazy { JSONObject(File(handoff, "config.json").readText()) }
    private val alpha get() = config.getString("alpha")
    private val beta get() = config.getString("beta")
    private val strings get() = appStrings(context, AppLanguage.Maintained.English)
    private val evidence = JSONObject()
    private val traffic get() = config.optJSONObject("traffic")
    private val trafficEvidence = JSONArray()
    private var stage = "entry"
    private fun mark(value: String) { stage = value; File(handoff, "stage.json").writeText(JSONObject().put("stage", stage).toString()) }

    private fun text(id: MessageId) = strings.message(id)
    private fun nav(value: String) = compose.onNode(hasText(value) and isSelectable()).performClick()
    private fun visible(matcher: SemanticsMatcher, index: Int = 0): SemanticsNodeInteraction {
        val node = compose.onAllNodes(matcher)[index]
        node.performScrollTo()
        val scroll = compose.onNode(hasScrollAction() and hasAnyDescendant(matcher))
        repeat(15) { step ->
            val viewport = scroll.fetchSemanticsNode().boundsInRoot
            val target = node.fetchSemanticsNode()
            val bounds = target.boundsInRoot
            // Clipped bounds can collapse outside the viewport. The layout's
            // position and size retain the direction needed to reveal it.
            val top = target.positionInRoot.y
            val bottom = top + target.size.height
            val lowerEdge = viewport.bottom - if (viewport.height > 1000f) 180f else 0f
            File(handoff, "visibility.jsonl").appendText(JSONObject().put("stage", stage).put("step", step)
                .put("bounds", bounds.toString()).put("layout_top", top).put("layout_bottom", bottom)
                .put("viewport", viewport.toString()).toString() + "\n")
            if (node.isDisplayed() && bounds.height > 1f && top >= viewport.top && bottom <= lowerEdge) return node
            val distance = when {
                top < viewport.top -> top - viewport.top
                bottom > lowerEdge -> bottom - lowerEdge
                else -> throw AssertionError("Target has visible layout bounds but is not displayed")
            }.coerceIn(-viewport.height * 0.75f, viewport.height * 0.75f)
            scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, distance) }
        }
        throw AssertionError("Target did not become fully visible in its scroll viewport")
    }
    private fun click(value: String) {
        val matcher = hasText(value) and hasClickAction() and !isSelectable()
        if (compose.onAllNodes(matcher and hasAnyAncestor(hasScrollAction())).fetchSemanticsNodes().isNotEmpty())
            visible(matcher)
        compose.onNode(matcher).assertIsDisplayed().performClick()
    }
    private fun input(label: String, value: String) = compose.onNode((hasText(label) or hasContentDescription(label)) and hasSetTextAction())
        .performTextInput(value)
    private suspend fun await(check: () -> Boolean) {
        try { withTimeout(35_000) { while (!check()) delay(100) } }
        catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            val state = services.state.value
            val diagnostic = JSONObject().put("stage", stage).put("error", state.error)
                .put("busy", state.busy.toString()).put("selected", state.selection?.provider)
                .put("entries", state.entries.map { it.provider + ":" + it.suspended + ":" + (it.selection != null) }.toString())
                .put("vpn_phase", YumeVpnRuntime.state.value.phase.name)
                .put("vpn_failure", YumeVpnRuntime.state.value.failure.name)
                .put("vpn_detail", YumeVpnRuntime.state.value.failureDetail)
            File(handoff, "failure.json").writeText(diagnostic.toString(2))
            throw AssertionError(diagnostic.toString(), error)
        }
    }
    private suspend fun idle() = await { services.state.value.busy.isEmpty() }
    private fun entry(provider: String) = services.state.value.entries.single { it.provider == provider }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private suspend fun host(action: String, prompt: CodePrompt? = null, selected: ServiceSelection? = null) {
        val id = prompt?.grant ?: action
        File(handoff, "$id.request").writeText(JSONObject().put("action", action)
            .put("provider", prompt?.provider ?: selected?.provider)
            .put("grant_id", prompt?.grant ?: selected?.grant).toString())
        val reply = File(handoff, "$id.ready")
        withTimeout(if (action == "account-expired") 90_000 else 35_000) { while (!reply.isFile) delay(100) }
        assertEquals("ready", reply.readText()); assertTrue(reply.delete())
    }

    private fun vpnNetwork(): Network {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = requireNotNull(manager.activeNetwork)
        assertTrue("The active network must be the VPN", manager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
        return network
    }

    private fun trafficAddress(): InetAddress {
        val host = requireNotNull(traffic).getString("host")
        require(host.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        require(host.split('.').all { it.toInt() in 0..255 })
        return InetAddress.getByName(host).also { address ->
            require(!address.isLoopbackAddress && !address.isAnyLocalAddress && !address.isMulticastAddress)
        }
    }

    private fun tcpSocket(network: Network): Socket = network.socketFactory.createSocket().also { socket ->
        try {
            socket.soTimeout = 5000
            socket.connect(InetSocketAddress(trafficAddress(), requireNotNull(traffic).getInt("tcp_port")), 5000)
        } catch (error: Throwable) { socket.close(); throw error }
    }

    private fun echo(socket: Socket, payload: ByteArray): ByteArray {
        socket.getOutputStream().write(payload)
        val received = ByteArray(payload.size)
        var size = 0
        while (size < received.size) {
            val count = socket.getInputStream().read(received, size, received.size - size)
            if (count < 0) throw IOException("Echo stream ended")
            size += count
        }
        return received
    }

    /** Both protocols are bound to the captured VPN, with no default-route retry. */
    private suspend fun packets(id: String, label: String) {
        if (traffic == null) return
        mark("packets:$label")
        val selected = services.state.value.selection
        assertEquals(id, if (services.state.value.manual) KitStore.get(context).selectedId.value else selected?.id)
        val before = YumeVpnRuntime.state.value
        assertEquals(VpnPhase.Connected, before.phase)
        assertEquals(id, before.kitId)
        val status = requireNotNull(before.status)
        assertEquals(requireNotNull(traffic).getJSONObject("peers")
            .getString(if (services.state.value.manual) "manual" else requireNotNull(selected).provider), status.peerFingerprint)
        val network = vpnNetwork()
        val tcp = ByteArray(65536).also(SecureRandom()::nextBytes)
        val udp = ByteArray(1024).also(SecureRandom()::nextBytes)
        withContext(Dispatchers.IO) {
            tcpSocket(network).use { assertArrayEquals(tcp, echo(it, tcp)) }
            DatagramSocket(null).use { socket ->
                network.bindSocket(socket)
                socket.bind(InetSocketAddress(0))
                socket.soTimeout = 5000
                val address = trafficAddress()
                val port = requireNotNull(traffic).getInt("udp_port")
                socket.send(DatagramPacket(udp, udp.size, address, port))
                val reply = DatagramPacket(ByteArray(udp.size + 1), udp.size + 1)
                socket.receive(reply)
                assertEquals(address, reply.address); assertEquals(port, reply.port)
                assertArrayEquals(udp, reply.data.copyOf(reply.length))
            }
        }
        await {
            val current = YumeVpnRuntime.state.value.status
            current != null && current.payloadBytesReceived >= status.payloadBytesReceived + tcp.size + udp.size &&
                current.payloadBytesSent >= status.payloadBytesSent + tcp.size + udp.size
        }
        val after = YumeVpnRuntime.state.value
        assertEquals(VpnPhase.Connected, after.phase)
        assertEquals(id, after.kitId)
        assertEquals(before.runId, after.runId)
        assertEquals(before.endpointId, after.endpointId)
        assertEquals(before.underlyingNetworkHandle, after.underlyingNetworkHandle)
        assertEquals(status.sessions, requireNotNull(after.status).sessions)
        assertEquals(status.peerFingerprint, after.status?.peerFingerprint)
        assertEquals(network, vpnNetwork())
        assertEquals(selected, services.state.value.selection)
        assertEquals(listOf("9.9.9.9"), after.dnsServers)
        assertFalse(after.carriesIpv6)
        trafficEvidence.put(JSONObject().put("label", label).put("kit_id", id).put("run_id", after.runId)
            .put("endpoint_id", after.endpointId).put("vpn_network", network.networkHandle)
            .put("peer", status.peerFingerprint).put("tcp_bytes", tcp.size).put("tcp_sha256", hash(tcp))
            .put("udp_bytes", udp.size).put("udp_sha256", hash(udp))
            .put("payload_rx_delta", requireNotNull(after.status).payloadBytesReceived - status.payloadBytesReceived)
            .put("payload_tx_delta", requireNotNull(after.status).payloadBytesSent - status.payloadBytesSent))
        File(handoff, "traffic-evidence-${if (File(handoff, "restart.json").exists()) "restart" else "initial"}.json")
            .writeText(trafficEvidence.toString(2))
    }

    private fun screenshot(name: String) {
        val image = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(handoff, "$name.png").outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { image.recycle() }
    }

    private suspend fun add(provider: String, issuer: String? = null) {
        mark("add:$provider")
        click(text(MessageId.PROVIDER_ADD_SERVICE))
        input(text(MessageId.PROVIDER_ORIGIN), provider)
        if (issuer != null) input(text(MessageId.PROVIDER_ISSUER), issuer)
        click(text(MessageId.PROVIDER_CONSENT)); click(strings.message(MessageId.ADD))
        await { services.state.value.entries.any { it.provider == provider } }; idle()
        assertNull(services.state.value.error)
    }

    /** Steps appear only on the rows they apply to, and the menu owns refreshing and removal. */
    private fun within(provider: String, label: String) {
        val state = services.state.value
        if (label == text(MessageId.PROVIDER_REMOVE)) { menu(provider); click(label); return }
        if (label == text(MessageId.PROVIDER_SERVERS) && state.catalogs[provider] != null) {
            menu(provider); click(text(MessageId.PROVIDER_REFRESH_SERVERS)); return
        }
        val entries = state.entries.filter { row ->
            when (label) {
                text(MessageId.PROVIDER_SIGN_IN) -> row.issuer != null && !row.authorized
                text(MessageId.PROVIDER_SERVERS) -> state.catalogs[row.provider] == null && (row.issuer == null || row.authorized)
                text(MessageId.PROVIDER_SELECT) -> row.choice != null && (state.manual || state.selection?.provider != row.provider)
                else -> true
            }
        }
        val index = entries.indexOfFirst { it.provider == provider }
        visible(hasText(label) and hasClickAction(), index).performClick()
    }

    private fun menu(provider: String) {
        val entries = services.state.value.entries
        val label = entries.single { it.provider == provider }.label
        val index = entries.filter { it.label == label }.indexOfFirst { it.provider == provider }
        visible(hasContentDescription(strings.providerMore(label)) and hasClickAction(), index).performClick()
    }

    /**
     * Chooses the provider's one server, which is local, then connects: Connect
     * fetches the grant, kit and, for an anonymous provider, the typed opening
     * code. Returns the held grant's binding, or null after a wrong code.
     */
    private suspend fun provision(provider: String, wrong: Boolean = false): ServiceSelection? {
        mark("provision:$provider:wrong=$wrong")
        if (!services.state.value.catalogs.containsKey(provider)) {
            within(provider, text(MessageId.PROVIDER_SERVERS))
            await { services.state.value.catalogs.containsKey(provider) }; idle()
        }
        // Only this provider has a catalog on its first install.
        services.state.value.catalogs.values.forEach { catalog ->
            assertEquals(1, catalog.services.size)
            assertEquals(1, catalog.services.single().servers.size)
        }
        val server = services.state.value.catalogs.getValue(provider).services.single().servers.single()
        visible(hasText(server.label) and hasClickAction()).performClick()
        await { services.state.value.selection?.provider == provider && provider !in services.state.value.busy }; idle()
        nav(strings.overviewTab)
        click(strings.connect)
        val prompt = if (entry(provider).issuer != null) null else {
            await { services.state.value.codePrompt?.provider == provider }
            val prompt = services.state.value.codePrompt!!
            host("opening-code", prompt)
            val file = File(handoff, prompt.grant + ".code")
            val code = file.readText().trim().toCharArray(); assertTrue(file.delete())
            try {
                if (wrong) code[0] = if (code[0] == '0') '1' else '0'
                input(text(MessageId.CODE), String(code)); click(strings.save)
            } finally { code.fill('\u0000') }
            idle()
            prompt
        }
        if (wrong) {
            await { YumeVpnRuntime.state.value.phase == VpnPhase.Error }
            assertNotNull(services.state.value.error)
            assertNull(entry(provider).selection)
            return null
        }
        await { YumeVpnRuntime.state.value.phase == VpnPhase.Connected }
        click(strings.disconnect); await { YumeVpnRuntime.state.value.phase == VpnPhase.Idle }
        mark("selected:$provider")
        assertNull(services.state.value.error)
        val selection = requireNotNull(entry(provider).selection)
        // The grant outlives the disconnect, and the host describes the kit it issued.
        if (prompt != null) services.open(selection).use { opened ->
            val expected = JSONObject(File(handoff, prompt.grant + ".json").readText())
            assertEquals(expected.getString("client_identity"), selection.identity)
            assertEquals(expected.getString("server_key_sha256"), selection.serverKey)
            assertEquals(expected.getString("host"), opened.session.description.host)
            assertEquals(expected.getInt("port"), opened.session.description.port)
            val files = expected.getJSONObject("files")
            for (path in files.keys()) assertEquals(files.getString(path), hash(if (path == "yume.json") opened.session.config else File(opened.session.directory, path).readBytes()))
        }
        return selection
    }

    /** The selected route a held grant belongs to, which is what a run reports as its kit. */
    private fun routeId(selection: ServiceSelection) = "provider:${selection.epoch}"

    private suspend fun connectAndStop(id: String, automatic: Boolean = false) {
        mark("connect:$id:automatic=$automatic")
        nav(strings.overviewTab)
        if (automatic) {
            assertTrue(context.getSharedPreferences("yume_vpn_restart", 0).edit().clear().commit())
            context.startForegroundService(Intent(context, YumeVpnService::class.java))
        } else click(strings.connect)
        await { YumeVpnRuntime.state.value.phase == VpnPhase.Connected }
        assertEquals(id, YumeVpnRuntime.state.value.kitId)
        packets(id, if (automatic) "restart" else "explicit")
        screenshot("connected-${if (automatic) "always-on" else "explicit"}")
        click(strings.disconnect)
        await { YumeVpnRuntime.state.value.phase == VpnPhase.Idle }
    }

    private suspend fun startSelected(selection: ServiceSelection, label: String) {
        nav(strings.connectTab)
        if (services.state.value.selection?.provider != selection.provider) within(selection.provider, text(MessageId.PROVIDER_SELECT))
        await { services.state.value.selection?.provider == selection.provider && "selection" !in services.state.value.busy }; idle()
        nav(strings.overviewTab)
        click(strings.connect)
        await { YumeVpnRuntime.state.value.phase == VpnPhase.Connected }
        packets(routeId(selection), label)
    }

    private fun manualHash(id: String): String {
        val bytes = requireNotNull(AppVaultManager.readBlob(context, "kit-$id"))
        return try { hash(bytes) } finally { bytes.fill(0) }
    }

    /**
     * A connected run renews at half its lease: a fresh grant is prepared through
     * the tunnel, then the run moves to it while its interface stays in place.
     */
    private suspend fun renewLive(previous: ServiceSelection): ServiceSelection {
        assertEquals(routeId(previous), YumeVpnRuntime.state.value.kitId)
        assertEquals(VpnPhase.Connected, YumeVpnRuntime.state.value.phase)
        val run = YumeVpnRuntime.state.value.runId
        val renewAt = requireNotNull(services.renewAfter(previous))
        val wait = (renewAt - System.currentTimeMillis() / 1000 + 90).coerceAtLeast(30) * 1000
        withTimeout(wait) {
            while (entry(previous.provider).selection?.grant == previous.grant || YumeVpnRuntime.state.value.runId == run ||
                YumeVpnRuntime.state.value.phase != VpnPhase.Connected) delay(250)
        }
        val replacement = requireNotNull(entry(previous.provider).selection)
        assertNotEquals(previous.grant, replacement.grant)
        assertNotEquals(previous.identity, replacement.identity)
        assertEquals(previous.copy(grant = replacement.grant, identity = replacement.identity), replacement)
        assertEquals(routeId(previous), YumeVpnRuntime.state.value.kitId)
        evidence.put("live_renewal_in_tunnel:${previous.provider}", true)
        return replacement
    }

    private suspend fun y4Live(betaCurrent: ServiceSelection, manualId: String) {
        val preferences = settings.settings.value
        val manualBefore = manualHash(manualId)
        val alphaInitial = requireNotNull(entry(alpha).selection)
        // The anonymous provider's opening code is typed by hand, so its grant renews only at a person's Connect.
        startSelected(betaCurrent, "beta-initial")
        assertEquals(alphaInitial, entry(alpha).selection)
        assertEquals(manualBefore, manualHash(manualId))
        click(strings.disconnect); await { YumeVpnRuntime.state.value.phase == VpnPhase.Idle }

        startSelected(alphaInitial, "alpha-before-api-expiry")
        val run = YumeVpnRuntime.state.value.runId
        host("account-expired", selected = alphaInitial)
        packets(routeId(alphaInitial), "alpha-after-api-expiry")
        assertEquals(run, YumeVpnRuntime.state.value.runId)
        nav(strings.connectTab)
        val previousCatalog = services.state.value.catalogs[alpha]
        within(alpha, text(MessageId.PROVIDER_SERVERS))
        await { services.state.value.catalogs[alpha] != previousCatalog || services.state.value.error != null }; idle()
        assertNull(services.state.value.error)
        assertEquals(alpha, services.state.value.selection?.provider)
        assertEquals(run, YumeVpnRuntime.state.value.runId)
        packets(routeId(alphaInitial), "alpha-after-api-refresh")
        evidence.put("account_api_expiry_and_refresh_preserve_transport", true)
        val alphaCurrent = renewLive(alphaInitial)
        assertEquals(betaCurrent, entry(beta).selection)
        assertEquals(manualBefore, manualHash(manualId))
        packets(routeId(alphaCurrent), "alpha-renewed")

        mark("revoke-live-alpha")
        val network = vpnNetwork()
        val persistent = withContext(Dispatchers.IO) { tcpSocket(network) }
        try {
            val probe = ByteArray(32).also(SecureRandom()::nextBytes)
            withContext(Dispatchers.IO) { assertArrayEquals(probe, echo(persistent, probe)) }
            val sessions = requireNotNull(YumeVpnRuntime.state.value.status).sessions
            host("revoke-alpha", selected = alphaCurrent)
            withContext(Dispatchers.IO) {
                try { assertEquals("Revocation must close the established stream", -1, persistent.getInputStream().read()) }
                catch (timeout: SocketTimeoutException) { throw AssertionError("Revoked stream remained open", timeout) }
                catch (_: IOException) { /* A reset also closes the authenticated stream. */ }
            }
            await { YumeVpnRuntime.state.value.phase != VpnPhase.Connected }
            assertEquals(alpha, services.state.value.selection?.provider)
            assertEquals(routeId(alphaCurrent), YumeVpnRuntime.state.value.kitId)
            assertTrue(YumeVpnRuntime.state.value.status?.sessions?.let { it <= sessions } ?: true)
            assertNotEquals(VpnPhase.Connected, YumeVpnRuntime.state.value.phase)
            // Stop retries, then make a fresh explicit attempt with the same local
            // grant. Native admission must refuse that removed identity.
            YumeVpnService.startDisconnect(context)
            await { YumeVpnRuntime.state.value.phase == VpnPhase.Idle }
            click(strings.connect)
            await {
                val attempt = YumeVpnRuntime.state.value
                assertNotEquals(VpnPhase.Connected, attempt.phase)
                assertTrue(attempt.status?.sessions?.let { it == 0L } ?: true)
                assertTrue(attempt.status?.session != com.fixcraft.yume.core.YumeStatus.SESSION_ACTIVE)
                attempt.phase == VpnPhase.Error
            }
            val refused = YumeVpnRuntime.state.value
            assertEquals(routeId(alphaCurrent), refused.kitId)
            assertFalse(refused.isActive)
            assertNotEquals(VpnFailure.None, refused.failure)
            assertEquals(alphaCurrent, entry(alpha).selection)
            assertEquals(betaCurrent, entry(beta).selection)
            assertEquals(manualBefore, manualHash(manualId))
            evidence.put("revoked_admission_failure", refused.failure.name)
            evidence.put("server_revocation_closes_stream_and_refuses_new_admission_without_fallback", true)
        } finally { persistent.close() }

        nav(strings.connectTab)
        within(alpha, text(MessageId.PROVIDER_REMOVE))
        compose.onNode(hasText(text(MessageId.PROVIDER_REMOVE)) and hasClickAction() and
            hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick()
        await { services.state.value.entries.none { it.provider == alpha } }; idle()
        assertEquals(betaCurrent, entry(beta).selection)
        assertEquals(manualBefore, manualHash(manualId))
        assertEquals(preferences, settings.settings.value)
        startSelected(betaCurrent, "beta-after-alpha-revoke-and-removal")
        click(strings.disconnect); await { YumeVpnRuntime.state.value.phase == VpnPhase.Idle }
        evidence.put("two_provider_manual_and_preferences_isolation", true)
    }

    @Test fun presentationAndProvisioning() = runBlocking {
        assertNotNull(AndroidProviderClock.now())
        settings.updateLanguage(AppLanguage.Maintained.English)
        settings.updateDnsServers(listOf("9.9.9.9")); settings.updateCarryIpv6(false)
        settings.updateAdvancedMode(false)
        await { services.state.value.loaded }
        if (traffic != null) {
            assertTrue(services.state.value.entries.isEmpty())
            assertTrue(KitStore.get(context).kits.value.isEmpty())
        }
        nav(strings.connectTab)
        add(beta)
        assertNull(provision(beta, wrong = true))
        val betaSelection = requireNotNull(provision(beta))
        val betaRoute = requireNotNull(services.state.value.selection)
        evidence.put("simple_anonymous_wrong_code_exact_retry", true)

        mark("manual-picker")
        val kits = KitStore.get(context)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(strings.addKit))
        click(strings.addKit)
        click(strings.chooseFile)
        host("manual-picker")
        val codeFile = File(handoff, "manual.code")
        val code = codeFile.readText().trim().toCharArray()
        try { input(strings.kitCodeLabel, String(code)) } finally { code.fill('\u0000') }
        input(strings.kitNameLabel, "Y3 independent manual")
        click(strings.importKit)
        await { kits.kits.value.size == 1 && services.state.value.manual }
        val manualId = kits.kits.value.single().id
        evidence.put("real_manual_document_picker_and_import_dialog", true)
        assertTrue(services.state.value.manual)
        assertEquals(manualId, kits.selectedId.value)
        nav(strings.overviewTab)
        compose.onNodeWithText("Y3 independent manual").assertExists()
        services.useProvider(beta); await { services.state.value.selection == betaRoute }; idle()

        nav(strings.connectTab)
        add(alpha, config.getString("issuer"))
        mark("browser:$alpha")
        com.fixcraft.yume.provider.ProviderAppLinks.verify(context,
            com.fixcraft.yume.provider.ProviderRegistration(context.packageName, config.getString("client_id"), config.getString("callback")))
        mark("browser-platform-verified")
        visible(hasText(text(MessageId.PROVIDER_SIGN_IN)) and hasClickAction())
        host("browser-sign-in")
        await { services.state.value.authorizing == alpha || services.state.value.error != null }
        assertNull(services.state.value.error)
        try { services.authorize(beta); throw AssertionError("Concurrent browser attempt accepted") }
        catch (refused: com.fixcraft.yume.provider.ProviderCodec.Refusal) { assertEquals("state", refused.code) }
        assertEquals(alpha, services.state.value.authorizing)
        services.cancel(beta)
        assertEquals(alpha, services.state.value.authorizing)
        evidence.put("browser_attempt_ownership_isolated", true)
        host("browser-return")
        await { services.state.value.authorizing == null && "callback" !in services.state.value.busy }
        assertNull(services.state.value.error)
        settings.updateAdvancedMode(true)
        provision(alpha)
        evidence.put("external_browser_and_advanced_provider", true)
        assertEquals(listOf("9.9.9.9"), settings.settings.value.dnsServers)
        assertFalse(settings.settings.value.carryIpv6)
        assertEquals(listOf(manualId), kits.kits.value.map { it.id })
        if (traffic != null) y4Live(betaSelection, manualId)
        services.useProvider(beta); await { services.state.value.selection == betaRoute }; idle()
        connectAndStop(betaRoute.id)
        connectAndStop(betaRoute.id)
        evidence.put("provider_connect_stop_reconnect_stop", true)
        assertEquals(betaRoute, services.selectedForRestart())
        // The host now kills this process. No pending PKCE state is serialized.
        if (traffic == null) {
            services.authorize(alpha)
            assertEquals(alpha, services.state.value.authorizing)
        }
        File(handoff, "restart.json").writeText(JSONObject().put("selection", JSONObject(betaRoute.encode()))
            .put("manual", manualId).toString())
        File(handoff, "presentation-evidence.json").writeText(evidence.toString(2))
    }

    @Test fun afterProcessDeath() = runBlocking {
        val saved = JSONObject(File(handoff, "restart.json").readText())
        val selected = requireNotNull(ServiceRoute.decode(saved.getJSONObject("selection").toString()))
        val preferences = settings.settings.value
        val manualBefore = manualHash(saved.getString("manual"))
        await { services.state.value.loaded }
        assertEquals(selected, services.state.value.selection?.copy(choice = null))
        assertNull(services.state.value.authorizing)
        assertNull(services.state.value.codePrompt)
        assertTrue(services.state.value.catalogs.isEmpty())
        ProviderCallbacks.deliver(config.getString("callback") + "?state=lost&code=lost&iss=lost")
        await { services.state.value.error == "state" }
        evidence.put(if (traffic == null) "actual_process_death_selection_and_lost_callback"
            else "actual_process_death_selection_and_unsolicited_callback_refusal", true)
        connectAndStop(selected.id, automatic = true)
        // A real lease, with both clocks enforced by the VPN owner, expires live.
        click(strings.connect)
        await { YumeVpnRuntime.state.value.phase == VpnPhase.Connected }
        packets(selected.id, "before-transport-expiry")
        val expiryWait = traffic?.optLong("expiry_wait_ms", 360_000L) ?: 200_000L
        require(expiryWait in 1..400_000L)
        withTimeout(expiryWait) { while (YumeVpnRuntime.state.value.phase != VpnPhase.Error) delay(100) }
        assertEquals(selected.id, YumeVpnRuntime.state.value.kitId)
        assertEquals(VpnFailure.KitUnavailable, YumeVpnRuntime.state.value.failure)
        assertEquals(text(MessageId.PROVIDER_SUSPENDED), YumeVpnRuntime.state.value.failureDetail)
        assertFalse(YumeVpnRuntime.state.value.isActive)
        assertEquals(selected, services.state.value.selection?.copy(choice = null))
        assertFalse(services.state.value.manual)
        evidence.put("live_transport_expiry_stops_exact_run", true)
        if (traffic == null) {
            val alphaSelection = requireNotNull(entry(alpha).selection)
            services.useProvider(alpha); await { services.state.value.selection?.provider == alpha }; idle()
            click(strings.connect)
            await { YumeVpnRuntime.state.value.phase == VpnPhase.Connected }
            nav(strings.connectTab)
            within(alpha, text(MessageId.PROVIDER_REMOVE))
            // Confirmation is the only dialog with a remove action.
            compose.onNode(hasText(text(MessageId.PROVIDER_REMOVE)) and hasClickAction() and
                androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick()
            await { YumeVpnRuntime.state.value.phase == VpnPhase.Error }
            assertEquals(routeId(alphaSelection), YumeVpnRuntime.state.value.kitId)
            assertEquals(VpnFailure.KitUnavailable, YumeVpnRuntime.state.value.failure)
            assertEquals(text(MessageId.PROVIDER_SUSPENDED), YumeVpnRuntime.state.value.failureDetail)
            await { services.state.value.entries.none { it.provider == alpha } }
            evidence.put("provider_removal_stops_live_run_without_fallback", true)
        } else assertEquals(listOf(beta), services.state.value.entries.map { it.provider })
        host("offline-beta")
        if (traffic == null) services.remove(beta) else {
            nav(strings.connectTab)
            within(beta, text(MessageId.PROVIDER_REMOVE))
            compose.onNode(hasText(text(MessageId.PROVIDER_REMOVE)) and hasClickAction() and
                hasAnyAncestor(androidx.compose.ui.test.isDialog())).performClick()
        }
        await { services.state.value.entries.isEmpty() }; idle()
        assertTrue(services.state.value.entries.isEmpty())
        host("offline-all")
        val manualId = saved.getString("manual")
        if (traffic == null) {
            KitStore.get(context).select(manualId); services.selectManual()
        } else {
            visible(hasText("Y3 independent manual") and hasClickAction()).performClick()
            await { services.state.value.manual && KitStore.get(context).selectedId.value == manualId }
        }
        settings.updateAdvancedMode(true)
        connectAndStop(manualId)
        settings.updateAdvancedMode(false)
        connectAndStop(manualId)
        assertEquals(listOf("9.9.9.9"), settings.settings.value.dnsServers)
        assertFalse(settings.settings.value.carryIpv6)
        assertEquals(listOf(manualId), KitStore.get(context).kits.value.map { it.id })
        assertEquals(preferences.copy(advancedMode = false), settings.settings.value)
        assertEquals(manualBefore, manualHash(manualId))
        evidence.put("independent_manual_both_modes_with_providers_stopped", true)
        File(handoff, "restart-evidence.json").writeText(evidence.toString(2))
    }
}
