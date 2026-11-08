package com.fixcraft.yume.ui

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fixcraft.yume.BusyUiState
import com.fixcraft.yume.MainUiState
import com.fixcraft.yume.TuningUiState
import com.fixcraft.yume.consumer.CodePrompt
import com.fixcraft.yume.consumer.ServiceEntry
import com.fixcraft.yume.consumer.ServiceSelection
import com.fixcraft.yume.consumer.ServiceUiState
import com.fixcraft.yume.core.YumeStatus
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.AppSettings
import com.fixcraft.yume.data.KitSummary
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.TuningLimits
import com.fixcraft.yume.data.TuningPreset
import com.fixcraft.yume.i18n.AppStrings
import com.fixcraft.yume.i18n.MessageId
import com.fixcraft.yume.i18n.appStrings
import com.fixcraft.yume.provider.ProviderCodec
import com.fixcraft.yume.vpn.UpstreamKind
import com.fixcraft.yume.vpn.VpnConnectionState
import com.fixcraft.yume.vpn.VpnFailure
import com.fixcraft.yume.vpn.VpnLogEntry
import com.fixcraft.yume.vpn.VpnLogLevel
import com.fixcraft.yume.vpn.VpnPhase
import com.fixcraft.yume.vpn.YumeVpnRuntime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A rendering gallery for visual review, not a behavior test. Every frame is the
 * production [YumeApp] drawn from a synthetic [MainUiState], with public example
 * names and callbacks that do nothing, so no provider, vault, native connection or
 * VPN runs and a frame shows presentation only. The host sets display size, font
 * scale and night mode between runs and passes `gallery.profile`, which names the
 * output folder under the app's private `gallery` directory, and optionally
 * `gallery.language`.
 */
@RunWith(AndroidJUnit4::class)
class ScreenGalleryTest {
    @get:Rule val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val profile = arguments.getString("gallery.profile") ?: "default"
    private val language: AppLanguage = when (arguments.getString("gallery.language")) {
        "ru" -> AppLanguage.Maintained.Russian
        "ja" -> AppLanguage.Maintained.Japanese
        "uk" -> AppLanguage.Maintained.Ukrainian
        "zh-Hans" -> AppLanguage.Maintained.ChineseSimplified
        else -> AppLanguage.Maintained.English
    }
    private val out = File(context.filesDir, "gallery/$profile").apply { mkdirs() }
    private val strings: AppStrings = appStrings(context, language)
    private var state by mutableStateOf(MainUiState())
    private var preparing by mutableStateOf<LanguagePreparationUiState?>(null)

    private fun show(initial: MainUiState) {
        state = initial.withLanguage()
        compose.setContent { FixtureApp(state, preparing) }
    }

    private fun MainUiState.withLanguage() = copy(appSettings = appSettings.copy(language = language))

    private fun open(destination: String) {
        compose.onNode(hasContentDescription(destination) and hasClickAction(), useUnmergedTree = false).performClick()
        compose.waitForIdle()
    }

    private fun tap(label: String) {
        compose.onAllNodes(hasText(label) and hasClickAction()).onLast().performClick()
        compose.waitForIdle()
    }

    private fun scrollTo(label: String) {
        compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText(label))
        compose.waitForIdle()
    }

    /** The whole display, including system bars and dialog windows. */
    private fun screenshot(): Bitmap {
        compose.waitForIdle()
        Thread.sleep(250)
        return instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
    }

    private fun save(name: String, image: Bitmap) {
        File(out, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** One frame per screenful of [name], so nothing below the fold goes unseen. */
    private fun frame(name: String, scroll: Boolean = true) {
        var previous = screenshot()
        save(name, previous)
        if (!scroll) return
        for (index in 1..MAX_SCROLL_FRAMES) {
            val scrollable = compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty()
            if (!scrollable) return
            compose.onAllNodes(hasScrollAction()).onLast().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = top + bottom * 0.2f, durationMillis = 400) }
            val next = screenshot()
            if (next.sameAs(previous)) break
            save("$name-$index", next)
            previous = next
        }
        // The next frame starts from the top of its page.
        repeat(MAX_SCROLL_FRAMES + 1) {
            compose.onAllNodes(hasScrollAction()).onLast().performTouchInput { swipeDown(startY = top + bottom * 0.2f, endY = bottom * 0.9f, durationMillis = 120) }
        }
        compose.waitForIdle()
    }

    /** The first Back can close only the keyboard, so this repeats until the dialog is gone. */
    private fun dismissDialog() {
        repeat(3) {
            if (compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()) return
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitForIdle()
            Thread.sleep(300)
        }
    }

    @Test fun overview() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        frame("overview-no-route")
        state = routeState(selectedRoute()).withLanguage()
        frame("overview-service-idle")
        pushTraffic()
        for ((name, vpn) in phases()) {
            state = routeState(selectedRoute()).copy(vpnState = vpn).withLanguage()
            frame("overview-$name")
        }
        state = manualState().copy(vpnState = phases().toMap().getValue("connected").copy(kitId = KIT.id, kitName = KIT.name, endpointLabel = KIT.endpointLabel)).withLanguage()
        frame("overview-manual-connected")
        state = manualState().withLanguage()
        frame("overview-manual-idle")
        state = state.copy(busyState = BusyUiState(strings.message(MessageId.PROVIDER_BUSY), EXAMPLE_LABEL))
        frame("overview-busy-dialog", scroll = false)
        YumeVpnRuntime.resetTrafficHistory()
    }

    @Test fun connect() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        open(strings.connectTab)
        for ((name, service) in serviceStates()) {
            state = MainUiState(services = service).withLanguage()
            frame("connect-$name")
        }
        state = manualState().withLanguage()
        frame("connect-manual-selected")
        state = state.copy(vpnState = VpnConnectionState(phase = VpnPhase.Connected, kitId = KIT.id, kitName = KIT.name)).withLanguage()
        frame("connect-manual-live")
        state = manualState().copy(kitIndexCorrupt = true).withLanguage()
        frame("connect-kit-index-corrupt")
        val chosen = selection(JAPAN)
        val selected = MainUiState(services = serviceStates().toMap().getValue("server-selected"))
        state = selected.copy(appSettings = selected.appSettings.copy(themeColorMode = ThemeColorMode.System)).withLanguage()
        frame("connect-system-theme", scroll = false)
        state = selected.copy(appSettings = selected.appSettings.copy(amoledDarkTheme = true)).withLanguage()
        frame("connect-amoled", scroll = false)
        state = selected.copy(appSettings = selected.appSettings.copy(advancedMode = true), vpnState = VpnConnectionState(phase = VpnPhase.Connected, kitId = chosen.id)).withLanguage()
        frame("connect-advanced-live")
    }

    @Test fun serviceMenu() {
        show(MainUiState(services = serviceStates().toMap().getValue("server-selected")))
        open(strings.connectTab)
        val menu = hasContentDescription(strings.providerMore(EXAMPLE_LABEL)) and hasClickAction()
        compose.onNode(menu).performScrollTo().performClick()
        frame("dialog-service-menu", scroll = false)
        tap(strings.message(MessageId.PROVIDER_DETAILS))
        frame("dialog-service-details", scroll = false)
        dismissDialog()
        state = state.copy(appSettings = state.appSettings.copy(advancedMode = true))
        compose.onNode(menu).performScrollTo().performClick()
        tap(strings.message(MessageId.PROVIDER_DETAILS))
        frame("dialog-service-details-advanced", scroll = false)
        dismissDialog()
        compose.onNode(menu).performScrollTo().performClick()
        tap(strings.message(MessageId.PROVIDER_REMOVE))
        frame("dialog-remove-service", scroll = false)
        dismissDialog()
        scrollTo(strings.message(MessageId.PROVIDER_MANUAL))
        compose.onNode(hasContentDescription(strings.moreAbout(strings.message(MessageId.PROVIDER_MANUAL)))).performClick()
        frame("dialog-kits-info", scroll = false)
    }

    @Test fun addingFromASuggestion() {
        show(MainUiState(services = ServiceUiState(loaded = true, browserRegistered = true, manual = true)))
        open(strings.connectTab)
        tap(strings.message(MessageId.PROVIDER_OTHER))
        frame("dialog-add-service-suggested", scroll = false)
    }

    @Test fun connectDialogs() {
        show(manualState())
        open(strings.connectTab)
        tap(strings.message(MessageId.PROVIDER_ADD_SERVICE))
        frame("dialog-add-service", scroll = false)
        dismissDialog()
        tap(strings.addKit)
        frame("dialog-add-kit", scroll = false)
        dismissDialog()
        compose.onAllNodes(hasContentDescription(strings.renameKit)).onLast().performClick()
        frame("dialog-rename-kit", scroll = false)
        dismissDialog()
        compose.onAllNodes(hasContentDescription(strings.delete)).onLast().performClick()
        frame("dialog-delete-kit", scroll = false)
        dismissDialog()
        state = MainUiState(services = serviceStates().toMap().getValue("code-prompt")).withLanguage()
        frame("dialog-opening-code", scroll = false)
    }

    @Test fun logs() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        open(strings.logsTab)
        frame("logs-empty")
        state = state.copy(vpnState = VpnConnectionState(eventLog = LOG))
        frame("logs-populated")
    }

    @Test fun posture() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        open(strings.postureTab)
        frame("posture-idle")
        state = routeState(selectedRoute()).copy(vpnState = phases().toMap().getValue("connected")).withLanguage()
        frame("posture-connected")
    }

    @Test fun settings() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        open(strings.settingsTab)
        frame("settings-simple")
        state = state.copy(appSettings = state.appSettings.copy(advancedMode = true))
        frame("settings-advanced")
    }

    @Test fun settingsWithTuning() {
        show(manualState().copy(tuning = TUNING, appDataPasswordEnabled = true))
        open(strings.settingsTab)
        frame("settings-tuning")
    }

    @Test fun settingsDialogs() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        open(strings.settingsTab)
        scrollTo(strings.setPasswordButton)
        tap(strings.setPasswordButton)
        frame("dialog-password-set", scroll = false)
        dismissDialog()
        scrollTo(strings.appLanguageLabel)
        tap(strings.appLanguageLabel)
        frame("dialog-language", scroll = false)
        tap(strings.close)
    }

    @Test fun credits() {
        show(MainUiState(services = ServiceUiState(loaded = true, manual = true)))
        open(strings.settingsTab)
        scrollTo(strings.creditsTitle)
        tap(strings.creditsTitle)
        frame("credits")
        scrollTo(strings.openSourceComponentsLink)
        tap(strings.openSourceComponentsLink)
        frame("credits-components")
    }

    private fun manualState() = MainUiState(kits = listOf(KIT, KIT_LONG), selectedKitId = KIT.id,
        services = ServiceUiState(loaded = true, manual = true))

    private fun routeState(selection: ServiceSelection) = MainUiState(services = ServiceUiState(
        entries = listOf(signedIn(selection)), catalogs = mapOf(PROVIDER to CATALOG), selection = selection, loaded = true, browserRegistered = true))

    private fun selectedRoute() = selection(JAPAN)

    private fun selection(server: ProviderCodec.Server) = ServiceSelection(PROVIDER, EPOCH, "example-device", "vpn", server.id,
        "example-grant", "a".repeat(64), server.serverKeySha256, server.host, server.port, server.label, "vpn")

    private fun signedIn(selection: ServiceSelection?, server: ProviderCodec.Server? = JAPAN, renewAfter: Long? = FUTURE,
                         expiresAt: Long? = FUTURE + 1800, suspended: Boolean = false) =
        ServiceEntry(PROVIDER, EXAMPLE_LABEL, ISSUER, selection, expiresAt.takeIf { selection != null }, suspended, authorized = true,
            server = server.takeIf { selection != null }, renewAfter = renewAfter.takeIf { selection != null })

    private fun signedOut(issuer: String? = ISSUER, label: String = EXAMPLE_LABEL) =
        ServiceEntry(PROVIDER, label, issuer, null, null, false)

    private fun serviceStates(): List<Pair<String, ServiceUiState>> {
        val base = ServiceUiState(loaded = true, browserRegistered = true)
        val chosen = selection(JAPAN)
        val now = System.currentTimeMillis() / 1000
        return listOf(
            "empty" to base.copy(manual = true),
            "signed-out" to base.copy(entries = listOf(signedOut())),
            "signed-out-same-origins" to base.copy(entries = listOf(signedOut(issuer = PROVIDER))),
            "signed-out-long-names" to base.copy(entries = listOf(signedOut(label = LONG_LABEL))),
            "signed-out-unregistered" to base.copy(entries = listOf(signedOut()), browserRegistered = false),
            "anonymous" to base.copy(entries = listOf(signedOut(issuer = null))),
            "browser-pending" to base.copy(entries = listOf(signedOut()), authorizing = PROVIDER),
            "sign-in-cancelled" to base.copy(entries = listOf(signedOut()), error = "cancelled"),
            "signed-in-loading" to base.copy(entries = listOf(signedIn(null)), busy = setOf(PROVIDER)),
            "signed-in-no-list" to base.copy(entries = listOf(signedIn(null))),
            "signed-in-no-servers" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG.copy(services = emptyList()))),
            "servers-unselected" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG)),
            "servers-unknown-country" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG_UNKNOWN)),
            "servers-long-names" to base.copy(entries = listOf(signedIn(null, server = null).copy(label = LONG_LABEL)), catalogs = mapOf(PROVIDER to CATALOG_LONG)),
            "server-provisioning" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG), busy = setOf(PROVIDER)),
            "server-selected" to base.copy(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = chosen),
            "server-selected-no-list" to base.copy(entries = listOf(signedIn(chosen)), selection = chosen),
            "server-installed-not-in-use" to base.copy(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), manual = true),
            "lease-renewal-due" to base.copy(entries = listOf(signedIn(chosen, renewAfter = now - 60, expiresAt = now + 600)), catalogs = mapOf(PROVIDER to CATALOG), selection = chosen),
            "lease-renewing" to base.copy(entries = listOf(signedIn(chosen, renewAfter = now - 60, expiresAt = now + 600)), catalogs = mapOf(PROVIDER to CATALOG), selection = chosen, busy = setOf(PROVIDER)),
            "lease-expired" to base.copy(entries = listOf(signedIn(chosen, renewAfter = now - 3600, expiresAt = now - 60)), catalogs = mapOf(PROVIDER to CATALOG), selection = chosen),
            "suspended" to base.copy(entries = listOf(signedIn(chosen, suspended = true)), selection = chosen),
            "error-unauthorized" to base.copy(entries = listOf(signedOut()), error = "unauthorized"),
            "error-not-entitled" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG), error = "not_entitled"),
            "error-unavailable" to base.copy(entries = listOf(signedIn(chosen)), catalogs = mapOf(PROVIDER to CATALOG), selection = chosen, error = "unavailable"),
            "error-rate-limited" to base.copy(entries = listOf(signedIn(null)), error = "rate_limited"),
            "error-stale" to base.copy(entries = listOf(signedIn(null)), catalogs = mapOf(PROVIDER to CATALOG), error = "stale"),
            "error-binding" to base.copy(entries = listOf(signedIn(chosen)), selection = chosen, error = "binding"),
            "error-storage" to base.copy(entries = listOf(signedIn(null)), error = "storage"),
            "removed" to base.copy(manual = false, removed = true),
            "two-providers" to base.copy(entries = listOf(signedIn(chosen), signedOut(label = "Second operator").copy(provider = "https://second.example.net", issuer = "https://second.example.net")),
                catalogs = mapOf(PROVIDER to CATALOG), selection = chosen),
            "code-prompt" to base.copy(entries = listOf(signedOut(issuer = null)), codePrompt = CodePrompt("example", PROVIDER, "example-grant")),
        )
    }

    private fun phases(): List<Pair<String, VpnConnectionState>> {
        val connected = YumeStatus(YumeStatus.STATE_RUNNING, YumeStatus.SESSION_ACTIVE, 0, 12_884_901_888L, 1_425_000, 0, 1, 0, false,
            5_242_880, 48_234_496, 5_500_000, 49_000_000, 16_777_216, 17_179_869_184L, 8, 14, 3, "ab".repeat(32), "")
        val live = VpnConnectionState(kitId = selectedRoute().id, kitName = JAPAN.label, endpointLabel = "${JAPAN.host}:${JAPAN.port}",
            dnsServers = AppSettings.DEFAULT_DNS_SERVERS, mtu = 1400, upstream = UpstreamKind.Wifi, serverAddress = "203.0.113.24:443")
        return listOf(
            "permission" to live.copy(phase = VpnPhase.PermissionRequired, serverAddress = null),
            "connecting" to live.copy(phase = VpnPhase.Connecting, serverAddress = null),
            "connected" to live.copy(phase = VpnPhase.Connected, status = connected),
            "reconnecting" to live.copy(phase = VpnPhase.Reconnecting, status = connected.copy(session = YumeStatus.SESSION_WAITING, retryMs = 4_000)),
            "waiting-network" to live.copy(phase = VpnPhase.Reconnecting, waitingForNetwork = true, status = connected.copy(session = YumeStatus.SESSION_WAITING)),
            "disconnecting" to live.copy(phase = VpnPhase.Disconnecting),
            "failed-unreachable" to live.copy(phase = VpnPhase.Error, failure = VpnFailure.Unreachable, failureDetail = "connect: timed out"),
            "failed-refused" to live.copy(phase = VpnPhase.Error, failure = VpnFailure.Refused),
        )
    }

    private fun pushTraffic() {
        YumeVpnRuntime.resetTrafficHistory()
        var rx = 0L
        var tx = 0L
        for (index in 0 until 24) {
            rx += 200_000L + index * 90_000L
            tx += 40_000L + index * 12_000L
            YumeVpnRuntime.pushTrafficSample(rx, tx)
            Thread.sleep(40)
        }
    }

    private companion object {
        const val MAX_SCROLL_FRAMES = 5
        const val PROVIDER = "https://api.example.org"
        const val ISSUER = "https://account.example.org"
        const val EPOCH = "00000000-0000-4000-8000-000000000001"
        const val EXAMPLE_LABEL = "Example Network"
        const val LONG_LABEL = "Example Community Network of the Northern Prefectures, Volunteer Operators' Association"
        const val FUTURE = 4_102_444_800L
        val JAPAN = ProviderCodec.Server("jp-vpn", "JP1 VPN", "jp1.example.org", 443, "b".repeat(64), "JP")
        val STATES = ProviderCodec.Server("us2-vpn", "US2 VPN", "us2.example.org", 443, "c".repeat(64), "US")
        val UNKNOWN = ProviderCodec.Server("lab-vpn", "Lab VPN", "lab.example.org", 8443, "d".repeat(64), null)
        val LONG_SERVER = ProviderCodec.Server("long-vpn", "Very long server name for the north-east regional backbone", "a-long-host-name.north-east.example.org", 44301, "e".repeat(64), "GB")
        private fun catalog(vararg servers: ProviderCodec.Server) = ProviderCodec.Catalog(PROVIDER, "example-device", "$PROVIDER/provider/v1",
            1_800_000_000, 1_800_000_300, listOf(ProviderCodec.Service("vpn", "VPN access", "vpn", emptyList(), servers.toList())))
        val CATALOG = catalog(JAPAN, STATES)
        val CATALOG_UNKNOWN = catalog(JAPAN, UNKNOWN)
        val CATALOG_LONG = catalog(LONG_SERVER, STATES, UNKNOWN)
        val KIT = KitSummary("kit-1", "Home server", "home.example.net", 8443, 0)
        val KIT_LONG = KitSummary("kit-2", "A long kit name for the family server", "a-very-long-host-name-for-an-operator.example.net", 44301, 0)
        val LOG = listOf(
            VpnLogEntry(1_800_000_000_000, VpnLogLevel.Info, "Starting the VPN"),
            VpnLogEntry(1_800_000_001_000, VpnLogLevel.Info, "Connected to jp1.example.org:443"),
            VpnLogEntry(1_800_000_950_000, VpnLogLevel.Warn, "The session ended. Reconnecting."),
            VpnLogEntry(1_800_000_960_000, VpnLogLevel.Error, "connect: timed out after 10 s while waiting for the first response"),
        )
        val TUNING = TuningUiState(kitId = "kit-1", limits = TuningLimits(16_777_216, 17_179_869_184L, 8, false),
            preset = TuningPreset("balanced", TuningLimits(16_777_216, 17_179_869_184L, 8, false), mapOf("stealth" to 2, "speed" to 2, "security" to 2),
                "window-comparison", listOf(320.0, 110.0), listOf(40, 210)),
            levelMaximum = mapOf("stealth" to 3, "speed" to 3, "security" to 3))
    }
}
