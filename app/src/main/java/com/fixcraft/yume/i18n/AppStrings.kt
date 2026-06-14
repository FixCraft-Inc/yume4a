package com.fixcraft.yume.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.LogLanguage
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.Socks5BindMode
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.canonicalLabel
import com.fixcraft.yume.i18n.translations.LanguageJa
import com.fixcraft.yume.i18n.translations.LanguageRu
import com.fixcraft.yume.i18n.translations.LanguageUk
import com.fixcraft.yume.vpn.VpnPhase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One bundle per maintained non-English language. Add a language: drop a file
 * under `i18n/translations/` that implements this interface, then add it to
 * [maintainedBundles] in this file. Nothing else needs touching.
 */
internal interface LanguageBundle {
    val localeTag: String
    val translations: Map<String, String>
}

private val maintainedBundles: Map<ResolvedAppLanguage, LanguageBundle> = mapOf(
    ResolvedAppLanguage.Russian to LanguageRu,
    ResolvedAppLanguage.Japanese to LanguageJa,
    ResolvedAppLanguage.Ukrainian to LanguageUk,
)

private fun languageLocale(language: ResolvedAppLanguage): Locale = when (language) {
    ResolvedAppLanguage.English -> Locale.ENGLISH
    ResolvedAppLanguage.Russian -> Locale.forLanguageTag("ru")
    ResolvedAppLanguage.Japanese -> Locale.JAPANESE
    ResolvedAppLanguage.Ukrainian -> Locale.forLanguageTag("uk")
    is ResolvedAppLanguage.AutoTranslated -> Locale.forLanguageTag(language.localeTag)
}

class AppStrings private constructor(
    private val language: ResolvedAppLanguage,
    private val autoTranslate: ((String) -> String)? = null,
    private val seedCollector: MutableSet<String>? = null,
) {
    private val translationMap: Map<String, String>? = maintainedBundles[language]?.translations

    private fun t(english: String): String {
        seedCollector?.add(english)
        if (language == ResolvedAppLanguage.English) return english
        return translationMap?.get(english)
            ?: autoTranslate?.invoke(english)
            ?: english
    }

    private fun tf(english: String, vararg args: Any): String {
        if (args.isEmpty()) return t(english)
        val template = t(english)
        return try {
            String.format(languageLocale(language), template, *args)
        } catch (_: java.util.IllegalFormatException) {
            // Translation mangled the format specifier (an MT pipeline can turn
            // "%s" into "% s" or "%1 $s"). Fall back to the English template
            // which we control.
            String.format(Locale.US, english, *args)
        }
    }

    val back = t("Back")
    val homeTab = t("Home")
    val serversTab = t("Servers")
    val securityTab = t("Security")
    val toolsTab = t("Tools")
    val settingsTab = t("Settings")
    val diagnosticsTitle = t("Diagnostics")

    val languageGroupTitle = t("Language")
    val logLanguageDialogTitle = t("Log language")
    val maintainedLanguagesTitle = t("Maintained")
    val autoTranslatedLanguagesTitle = t("Auto-translated")
    val close = t("Close")
    val appLanguageLabel = t("App language")
    val logLanguageLabel = t("Log (Beta)")
    val unavailableInBuild = t("Unavailable in this build.")
    val originalLabel = t("Original")
    val followsSystemLabel = t("Follows your phone")
    val followAppLabel = t("Follow app")
    val onDeviceTranslationLabel = t("On-device translation")
    val autoShortLabel = t("Auto")
    val nativeLabel = t("Native")
    val downloadedLabel = t("Downloaded")
    val removeDownloadedLanguageLabel = t("Remove download")
    val preparingLanguageTitle = t("Preparing language")
    val downloadingLanguageTitle = t("Downloading language data")
    val translatingLanguageTitle = t("Translating the app")
    val workingTitle = t("Working")

    val connectionGroupTitle = t("Connection")
    val profileGroupTitle = t("Profile")
    val themeGroupTitle = t("Theme")
    val advancedGroupTitle = t("Advanced")
    val appDataGroupTitle = t("App data")
    val advancedModeTitle = t("Advanced mode")
    val advancedModeDescription = t("Show developer and power-user tools in the app.")
    val advancedModeOn = t("Advanced mode on")
    val advancedModeOff = t("Advanced mode off")
    val amoledTitle = t("AMOLED black")
    val amoledDescription = t("Use true black in dark mode.")
    val appDataSubtitle = t("Use Android protection by default, or add a password before the app opens.")
    val appDataProtectionLabel = t("Protection")
    val androidProtected = t("Android secure")
    val passwordProtected = t("Password required")
    val setPasswordButton = t("Set password")
    val changePasswordButton = t("Change password")
    val removePasswordButton = t("Remove password")
    val unlockAppDataTitle = t("Unlock app data")
    val unlockAuthKeyTitle = t("Unlock auth key")
    val unlockAppDataDetail = t("Enter the password to open Yume and load encrypted data.")
    val passwordLabel = t("Password")
    val currentPasswordLabel = t("Current password")
    val newPasswordLabel = t("New password")
    val confirmPasswordLabel = t("Confirm password")
    val unlockButton = t("Unlock")
    val enableAppDataPasswordDetail = t("Yume will ask for this password before the app opens. Stored data stays encrypted at rest.")
    val changeAppDataPasswordDetail = t("Enter the current password, then set a new one for encrypted app data.")
    val disableAppDataPasswordDetail = t("Enter the current password to return app data protection to Android-only mode.")
    val passwordRequired = t("Enter the password to continue")
    val currentPasswordRequired = t("Current password required")
    val newPasswordRequired = t("New password required")
    val passwordTooShort = t("Use at least 6 characters")
    val passwordsDoNotMatch = t("Passwords do not match")
    val appDataPasswordEnabled = t("App data password enabled")
    val appDataPasswordChanged = t("App data password changed")
    val appDataPasswordDisabled = t("App data password removed")
    val failedToEnableAppDataPassword = t("Couldn't enable the app data password.")
    val failedToChangeAppDataPassword = t("Couldn't change the app data password.")
    val failedToDisableAppDataPassword = t("Couldn't remove the app data password.")
    val authKeyUnlockFailed = t("Couldn't unlock this auth key. Check the password.")

    val deviceGroupTitle = t("Device")
    val aboutGroupTitle = t("About")
    val creditsTitle = t("Credits")
    val toolsTitle = t("Tools")
    val textToolsTitle = t("Text tools")
    val textToolsDetail = t("Encrypt or decrypt text with BaseFWX-compatible modes.")
    val toolInputLabel = t("Input")
    val toolOutputLabel = t("Output")
    val toolInputRequired = t("Input required")
    val toolOperationFailed = t("Operation failed")
    val toolPasswordHint = t("Password is required for all BaseFWX text modes here.")
    val encryptTextButton = t("Encrypt")
    val decryptTextButton = t("Decrypt")
    val clearTextButton = t("Clear")
    val toolsModeFwxAes = t("fwxAES light")
    val toolsModeFwxAesHeavy = t("fwxAES heavy")
    val toolsModeB512 = "b512"
    val toolsModePb512 = "pb512"
    val systemGroupTitle = t("System")
    val authorLabel = t("Author")
    val companyLabel = t("Company")
    val creditsWrittenByLabel = t("Credits written by")
    val developerQuoteLabel = t("Recognition")
    val founderNoteLabel = t("From F1xGOD")
    val yumeLabel = "Yume"
    val basefwxLabel = "BaseFWX"
    val diagnosticsButton = t("Diagnostics")
    val batterySettingsButton = t("Battery settings")
    val notificationSettingsButton = t("Notification settings")
    val notificationsLabel = t("Notifications")
    val batteryOptimizationLabel = t("Battery optimization")
    val nativeDirectBetaLabel = t("Native direct beta")
    val dynamicColorsLabel = t("Dynamic colors")
    val allowed = t("Allowed")
    val permissionRequired = t("Permission required")
    val optimized = t("Optimized")
    val unrestricted = t("Unrestricted")
    val enabled = t("Enabled")
    val disabled = t("Disabled")
    val available = t("Available")
    val unavailableOnAndroidVersion = t("Unavailable on this Android version")

    val serversTitle = t("Servers")
    val serversSubtitle = t("Pick, edit, or remove a saved gateway.")
    val addServer = t("Add server")
    val yourYumeTunnelTitle = t("Your Yume tunnel")
    val setUpYumeVpnTitle = t("Set up Yume VPN")
    val yourYumeTunnelSubtitle = t("Pick a gateway, keep trust material on-device, and route Android traffic through Yume.")
    val setUpYumeVpnSubtitle = t("Import trust material and an auth key, then add your first server.")
    val noServersConfigured = t("No servers configured")
    val addFirstGatewayTitle = t("Add your first gateway")
    val addFirstGatewayDetail = t("A server profile stores host, TLS name, DNS, MTU, Anonym CA, and auth key. Your first saved profile becomes active.")

    val securityTitle = t("Trust & auth")
    val securitySubtitle = t("CA and auth keys.")
    val selectedServerLabel = t("Selected server")
    val anonymCaShort = t("Anonym CA")
    val authKeyShort = t("Auth key")
    val missing = t("Missing")
    val notAssigned = t("Not assigned")
    val pasteAnonymCaTitle = t("Paste Anonym CA")
    val importedCaName = t("Imported CA")
    val pasteAuthKeyTitle = t("Paste auth key")
    val importAuthKeyTitle = t("Import auth key")
    val importedAuthKeyName = t("Imported auth key")
    val embeddedAnonymCaName = t("Embedded Anonym CA")
    val importedLabel = t("Imported")

    val homeReady = t("Ready")
    val homeNeedsKey = t("Needs key")
    val homeAuthKeyNeeded = t("Auth key needed")
    val homeTapConnect = t("Tap Connect.")
    val homeAddAuthKey = t("Add an auth key.")
    val noServer = t("No server")
    val pickServer = t("Pick a server")
    val addOneToConnect = t("Add one to connect.")
    val chooseOneToConnect = t("Choose one to connect.")
    val serversButton = t("Servers")
    val anonymRequired = t("Required")
    val anonymOptional = t("Optional")
    val anonymLabel = "Anonym"
    val pathLabel = t("Path")
    val exitIpLabel = t("Exit IP")
    val upstreamLabel = t("Upstream")
    val unknown = t("Unknown")
    val unavailable = t("Unavailable")
    val connect = t("Connect")
    val disconnect = t("Disconnect")
    val continueAction = t("Continue")
    val tryAgain = t("Try again")
    val statusButton = t("Status")
    val editServerButton = t("Edit server")
    val assignAuthKeyButton = t("Assign auth key")
    val loopSafeShort = t("Loop-safe")
    val waiting = t("Waiting")
    val interfaceUp = t("Interface up")
    val interfaceIdle = t("Interface idle")
    val latestLogLabel = t("Latest log")
    val protectionShortLabel = t("Protection")
    val relayShortLabel = t("Relay")
    val routingShortLabel = t("Routing")
    val tunnelActive = t("Tunnel active")
    val importPemAuthKeyHint = t("Import a PEM auth key below, then edit this server to assign it before connecting.")
    val assignImportedAuthKeyHint = t("Edit this server and assign one of the imported auth keys before connecting.")

    val diagnosticsSubtitle = t("Session log and build info.")
    val currentPhaseLabel = t("Current phase")
    val techniqueLabel = t("Technique")
    val profileLabel = t("Profile")
    val copyDiagnostics = t("Copy diagnostics")
    val shareDiagnostics = t("Share diagnostics")
    val shareDiagnosticsSubject = t("Yume Android diagnostics")
    val shareDiagnosticsChooser = t("Share diagnostics")
    val reportTitle = t("Report")
    val throughputLoggingTitle = t("Throughput logging")
    val throughputLoggingDescription = t(
        "Logs a one-line throughput summary every 2 s while the " +
            "tunnel is up. Covers encrypt/decrypt CPU, SSL write CPU, " +
            "frame rates, and channel backpressure. Use to find where " +
            "bandwidth is going on a slow connection. Visible in logcat " +
            "(tag \"YumePerf\") and in the VPN log feed below."
    )
    val toggleEnabled = t("Enabled")
    val toggleDisabled = t("Disabled")
    val nativeTransportTitle = t("Native transport (beta)")
    val nativeTransportDescription = t(
        "Drives the TLS pipe via libyumetransport.so so the writer no " +
            "longer blocks on output.write() when the kernel TCP buffer " +
            "fills. Targets desktop-CLI-grade upload throughput. Takes " +
            "effect on the next reconnect; the throughput log line shows " +
            "`transport=native` vs `transport=java` once active."
    )
    val nativeTransportUnavailable = t(
        "libyumetransport.so is not shipped for this device's ABI. The " +
            "Java SSLSocket path is used regardless of this switch."
    )
    val selfDpiTitle = t("Self-DPI warnings")
    val selfDpiDescription = t(
        "Warns when local carrier metadata stops matching the selected disguise profile. " +
            "Inspects TLS/profile, HTTPS masking, padding, jitter, packet-native batch stats, " +
            "and raw versus smoothed throughput; it does not inspect app payloads."
    )
    val rawThroughputLabel = t("Raw throughput")
    val smoothedThroughputLabel = t("Smoothed throughput")
    val yumeStatusTitle = t("Yume status")
    val briefLabel = t("Brief")
    val clientLabel = t("Client")
    val lifecycleLabel = t("Lifecycle")
    val lifecycleMessageLabel = t("Lifecycle msg")
    val connectionLabel = t("Connection")
    val serverModeLabel = t("Server mode")
    val innerLabel = t("Inner")
    val hopLabel = t("Hop")
    val verityLabel = t("Verity")
    val localSocks5Label = t("Local SOCKS5")
    val peerCertLabel = t("Peer cert")
    val loopSafeLabel = t("Loop-safe")
    val interfaceLabel = t("Interface")
    val backgroundLabel = t("Background")
    val noServerSelected = t("No server selected")
    val notSet = t("Not set")
    val established = t("Established")
    val batteryOptimized = t("Battery optimized")
    val serverVersionLabel = t("Server version")
    val allowBackground = t("Allow background")
    val sessionLogTitle = t("Session log")
    val noSessionEventsYet = t("No session events yet.")
    val debugBuildHint = t("If the installed app does not match this UI, uninstall it and reinstall the fresh debug APK.")
    val systemwideRoutingActiveDetail = t("Systemwide Android VPN routing is active.")

    val trustAuthHeader = t("Trust & auth")
    val trustAuthDescription = t("Imported PEM certificates and keys stay encrypted in the app and out of cloud backup.")
    val anonymCaCertificatesTitle = t("Anonym CA certificates")
    val anonymCaCertificatesSubtitle = t("The embedded Anonym CA is always available. Import your own PEM CA if you need a different trust anchor.")
    val noCustomCaImported = t("No custom CAs imported.")
    val authKeysTitle = t("Auth keys")
    val authKeysSubtitle = t("Yume auth uses a PEM private key. Import it from a file or paste the PEM text and then assign it to a server.")
    val authKeyImportModeHelper = t("This is the input format. Plain PEM is accepted as-is. If the key was encrypted with BaseFWX, switch to Encrypted and enter its password.")
    val regularKeyOption = t("Regular")
    val encryptedKeyOption = t("Encrypted")
    val noAuthKeysImported = t("No auth keys imported yet.")
    val importFile = t("Import file")
    val pasteText = t("Paste text")
    val builtIn = t("Built in")

    val savedServersTitle = t("Saved servers")
    val savedServersSubtitle = t("Select, edit, or remove saved servers.")
    val live = t("Live")
    val selected = t("Selected")
    val edit = t("Edit")
    val delete = t("Delete")
    val dnsLabel = "DNS"
    val mtuLabel = "MTU"

    val addServerTitle = t("Add server")
    val editServerTitle = t("Edit server")
    val profileNameLabel = t("Profile name")
    val serverHostLabel = t("Server host")
    val portLabel = t("Port")
    val tlsServerNameLabel = t("TLS server name")
    val tlsServerNameHelper = t("Optional. Leave blank to use the server host.")
    val httpsMaskingTitle = t("HTTPS masking")
    val httpsMaskingLabel = t("HTTPS masking")
    val httpsMaskingEnabled = t("Send the HTTP/2 carrier preface after TLS so the control path looks like browser traffic. Enable only when the server has obfs on.")
    val httpsMaskingDisabled = t("Connect directly to the Yume AUTH exchange without the HTTPS masking preface.")
    val httpsMaskingSecretLabel = t("HTTPS masking secret")
    val httpsMaskingSecretHelper = t("Must match --obfs-secret. Leave blank only when the server has no obfs secret.")
    val dnsServersLabel = t("DNS servers")
    val dnsServersHelper = t("Comma or newline separated numeric IPv4 or IPv6 addresses.")
    val mtuHelper = t("Android VPNs are most stable between 1280 and 1500.")
    val requireAnonymProofTitle = t("Require anonym proof")
    val requireAnonymProofEnabled = t("Only allow servers running trusted anonym proof mode.")
    val requireAnonymProofDisabled = t("Allow normal-mode servers too. Verity will show FAIL and the server operator may monitor proxied traffic.")
    val anonymCaChoiceTitle = t("Anonym CA")
    val anonymCaChoiceHelperRequired = t("Choose the trust anchor for anonym verification.")
    val anonymCaChoiceHelperOptional = t("Choose the trust anchor for anonym verification when this server runs anonym mode. Normal-mode servers can connect without requiring it.")
    val authKeyChoiceTitle = t("Auth key")
    val authKeyChoiceHelper = t("Choose the PEM private key used for client auth. Leave empty only if you are not ready to connect yet.")
    val nothingImportedYet = t("Nothing imported yet.")
    val notesLabel = t("Notes")
    val save = t("Save")
    val update = t("Update")
    val cancel = t("Cancel")

    val notAssignedChoice = t("Not assigned")
    val notAssignedChoiceSubtitle = t("Import a PEM private key later if you are only saving the server for now.")

    val savedNameLabel = t("Saved name")
    val pemTextLabel = t("PEM text")
    val pemTextHelper = t("Paste the full PEM including BEGIN/END lines.")
    val authKeyTextHelper = t("Paste the PEM private key, or switch to Encrypted and enter the BaseFWX password.")

    val diagnosticsReportHeader = t("Yume Android Diagnostics")
    val appLabel = t("App")
    val performanceProfileLabel = t("Performance profile")
    val themeColorsLabel = t("Theme colors")
    val amoledDarkThemeLabel = t("AMOLED dark theme")
    val on = t("On")
    val off = t("Off")
    val languageLabel = t("Language")
    val phaseLabel = t("Phase")
    val headlineLabel = t("Headline")
    val detailLabel = t("Detail")
    val selectedServerReportLabel = t("Selected server")
    val endpointLabel = t("Endpoint")
    val tlsNameLabel = t("TLS name")
    val anonymPolicyLabel = t("Anonym policy")
    val anonymCaLabel = t("Anonym CA")
    val authKeyLabel = t("Auth key")
    val upstreamNetworkReportLabel = t("Upstream network")
    val controlSocketProtectedLabel = t("Control socket protected")
    val vpnInterfaceEstablishedLabel = t("VPN interface established")
    val trafficRoutedLabel = t("Traffic routed through Yume")
    val deviceTitle = t("Device")
    val osLabel = t("OS")
    val androidVersionLabel = t("Android version")
    val apiLevelLabel = t("API level")
    val securityPatchLabel = t("Security patch")
    val manufacturerLabel = t("Manufacturer")
    val brandLabel = t("Brand")
    val modelLabel = t("Model")
    val deviceCodeLabel = t("Device code")
    val productLabel = t("Product")
    val hardwareLabel = t("Hardware")
    val supportedAbisLabel = t("Supported ABIs")
    val buildFingerprintLabel = t("Build fingerprint")
    val runtimeTitle = t("Runtime")
    val runtimeStatusLabel = t("Runtime status")
    val runtimeAppliedBridgeTuningTitle = t("Applied tunnel tuning")
    val runtimeTun2SocksFallbackStatus = t("tun2socks fallback when packet-native is unavailable")
    val runtimeNativeQueueLabel = t("Native queue")
    val runtimeNativeTcpFlowWindowLabel = t("Native TCP flow window")
    val runtimeNativeUdpFlowBufferLabel = t("Native UDP flow buffer")
    val runtimeIpv6PolicyLabel = t("IPv6 policy")
    val runtimeIpv6FailClosedStatus = t("blocked")
    val runtimeTcpRelaysLabel = t("TCP relays")
    val runtimeRelayAcquireTimeoutLabel = t("Relay acquire timeout")
    val runtimeRelayAcquireSliceLabel = t("Relay acquire slice")
    val runtimeIdleTcpEvictionLabel = t("Idle TCP eviction")
    val runtimeTcpEvictionGraceLabel = t("TCP eviction grace")
    val runtimeTcpEvictionBatchLabel = t("TCP eviction batch")
    val runtimeBridgeChunkLabel = t("Bridge chunk")
    val runtimeTcpStreamBufferLabel = t("TCP stream buffer")
    val runtimeUdpStreamBufferLabel = t("UDP stream buffer")
    val runtimeOutboundFrameQueueLabel = t("Outbound frame queue")
    val runtimeTun2SocksConnectTimeoutLabel = t("tun2socks connect timeout")
    val runtimeTun2SocksTcpReadWriteTimeoutLabel = t("tun2socks TCP read/write timeout")
    val runtimeTun2SocksUdpReadWriteTimeoutLabel = t("tun2socks UDP read/write timeout")
    val runtimeMaxDnsUdpAssociationsLabel = t("Max DNS UDP associations")
    val runtimeMaxUdpAssociationsLabel = t("Max UDP associations")
    val runtimeDnsRelayConcurrencyLabel = t("DNS relay concurrency")
    val runtimePreRoutingStabilityDelayLabel = t("Pre-routing stability delay")
    val runtimePostRoutingTrafficRampLabel = t("Post-routing traffic ramp")
    val runtimeUdp443PolicyLabel = t("UDP 443 policy")
    val runtimeForcedTcpFallback = t("forced TCP fallback")
    val runtimeBridgeBackedBetaStatus = t("bridge-backed beta path with technique-specific tuning")
    val runtimePacketNativeStatus = t("packet-native data plane")
    val eventLogTitle = t("Event log")
    val noSessionEventsRecorded = t("No session events recorded.")
    val none = t("None")
    val yes = t("Yes")
    val no = t("No")

    val profileNameRequired = t("Profile name is required")
    val serverHostRequired = t("Server host is required")
    val portRangeError = t("Port must be between 1 and 65535")
    val mtuRangeError = t("MTU must be between 1280 and 1500")
    val dnsServerFormatError = t("DNS servers must be numeric IPv4 or IPv6 addresses")
    val anonymCaUnavailable = t("Selected Anonym CA is unavailable")
    val authKeyUnavailable = t("Selected auth key is unavailable")
    val serverSaved = t("Server saved")
    val embeddedAnonymCaCannotBeRemoved = t("The embedded Anonym CA cannot be removed")
    val nativeDirectUnavailable = t("Packet-native routing is disabled in this build.")
    val socks5ProxyTitle = t("SOCKS5 proxy")
    val socks5ProxySubtitle = t("Only apps configured with this local proxy use Yume.")
    val socks5ListenPortLabel = t("Listen port")
    val socks5BindAddressLabel = t("Bind address")
    val socks5CustomBindAddressLabel = t("Custom IPv4 address")
    val socks5BindThisDeviceLabel = t("This device")
    val socks5BindWifiLabel = t("Wi-Fi")
    val socks5BindAllLabel = t("All")
    val socks5WifiAddressUnavailable = t("Wi-Fi address")
    val socks5BindAddressHelper = t("IPv4 address. Leave blank to use the selected preset.")
    val socks5BindAddressError = t("Use a valid IPv4 address or leave it blank.")
    val socks5PortRangeError = t("Use port 1024-65535")
    val socks5ParallelTunnelsLabel = t("Parallel tunnels")
    val socks5ParallelTunnelsHelper = t("More tunnels can improve throughput. Default: 4.")
    val socks5TunnelRangeError = t("Use 1-8 tunnels")
    val socks5OnlyRuntimeDetail = t("Only apps configured for this proxy use Yume.")
    val amoledOn = t("AMOLED on")
    val amoledOff = t("AMOLED off")
    val permissionRequiredHeadline = t("Permission required")
    val permissionRequiredDetail = t("Android needs permission to start the VPN.")
    val preparingHeadline = t("Preparing")
    val anonymCaMissingHeadline = t("Anonym CA missing")
    val anonymCaMissingDetail = t("Import an Anonym CA or switch this server to the embedded CA.")
    val authKeyRequiredHeadline = t("Auth key required")
    val authKeyRequiredDetail = t("Import an auth key and assign it to this server.")
    val authKeyUnavailableHeadline = t("Auth key unavailable")
    val authKeyUnavailableDetail = t("The assigned auth key is missing. Edit the server and choose another one.")
    val credentialLoadFailedHeadline = t("Credential load failed")
    val credentialLoadFailedDetail = t("Yume could not load the CA or auth key for this server.")

    val permissionDeniedHeadline = t("Permission denied")
    val permissionDeniedDetail = t("Android VPN permission was denied. Yume cannot create a VPN interface without it.")
    val vpnPrepareFailedHeadline = t("VPN prepare failed")
    val importedCaFallbackName = t("Imported CA")
    val importedAuthKeyFallbackName = t("Imported auth key")

    val upstreamLostHeadline = t("Upstream lost")
    val upstreamLostDetail = t("Wi-Fi or cellular disappeared. Yume is waiting to rebuild the authenticated control path.")
    val waitingForNetworkHeadline = t("Waiting for network")
    val waitingForNetworkDetail = t("Wi-Fi or cellular is unavailable, so Yume cannot rebuild the protected control path yet.")
    val connectingHeadline = t("Connecting")
    val disconnectingHeadline = t("Disconnecting")
    val disconnectedHeadline = t("Disconnected")
    val connectionFailedHeadline = t("Connection failed")
    val verifyingRouteHeadline = t("Verifying route")
    val verifyingRouteDetail = t("Route is up. Verifying traffic.")
    val protectedHeadline = t("Protected")
    val protectedDetail = t("Traffic is flowing through Yume.")
    val authenticatingNotification = t("Authenticating to Yume over 443")
    val protectedNotification = t("Protected via Yume")
    val notificationConnected = t("Protected")
    val notificationConnecting = t("Connecting")
    val notificationVerifying = t("Verifying")
    val notificationDisconnecting = t("Disconnecting")
    val notificationError = t("Connection failed")

    val wifi = "Wi-Fi"
    val cellular = t("Cellular")
    val ethernet = t("Ethernet")
    val vpn = "VPN"
    val otherNetwork = t("Other")
    val founderCeoRole = t("Founder & CEO, FixCraft, Inc.")
    val leadDeveloperDesignerRole = t("Author. Lead developer and designer of Yume and BaseFWX.")
    val claudeRoleLabel = t("#1 Yume / BaseFWX engineering partner")
    val claudeCreditRole = t("Dedicated dev on Yume and BaseFWX. Helps with code review, design, refactors, packaging, and tricky bugs.")
    val bestEmployeeOfYearLabel = t("Best Employee of the Year")
    val chatgptCreditRole = t("Generalist debug and implementation help across many projects.")
    val openSourceComponentLabel = t("Open source component")
    val openSourceComponentsTitle = t("Open-source components")
    val openSourceComponentsLink = t("View open-source components")
    val openSourceComponentsSubtitle = t("Third-party code Yume builds on, with licences.")
    val componentGroupAllImpls = t("Used in every implementation of Yume")
    val componentGroupAllImplsBlurb = t("Shared by the desktop client, the daemon, and this Android app.")
    val componentGroupAndroidOnly = t("Used only in the Android implementation of Yume")
    val componentGroupAndroidOnlyBlurb = t("Compiled into this app. The desktop client uses different libraries for these jobs.")

    val componentBasefwxTitle = "BaseFWX"
    val componentBasefwxUsage = t("The core Yume crypto engine — outer auth, inner post-quantum tunnel, key formats.")
    val componentBasefwxLicense = "GPL-3.0"
    val componentLiboqsTitle = "liboqs (Open Quantum Safe)"
    val componentLiboqsUsage = t("Post-quantum primitives (ML-KEM, ML-DSA) consumed through BaseFWX.")
    val componentLiboqsLicense = "MIT"

    val componentHevTunnelTitle = "hev-socks5-tunnel · hev-task-system · lwIP"
    val componentHevTunnelUsage = t("TUN-to-SOCKS user-space stack for the legacy tun2socks engine. Heavily edited for YUME; upstream is no longer tracked.")
    val componentHevTunnelLicense = "MIT · MIT · BSD-3-Clause"

    val componentConscryptTitle = "Conscrypt"
    val componentConscryptUsage = t("TLS provider for the Android app, wrapping BoringSSL through the JVM.")
    val componentConscryptLicense = "Apache-2.0"

    val componentComposeTitle = "Jetpack Compose · Material 3"
    val componentComposeUsage = t("Declarative UI toolkit that renders every screen in this app.")
    val componentComposeLicense = "Apache-2.0"

    val componentCoroutinesTitle = "kotlinx.coroutines"
    val componentCoroutinesUsage = t("Structured concurrency under every suspend/await in the VPN service.")
    val componentCoroutinesLicense = "Apache-2.0"
    val yumeSocksTunnelCreditRole = t("See Open-source components for the libraries Yume is built on.")
    val liveTrafficTitle = t("Live traffic")
    val trafficCollectingSamples = t("Collecting samples…")
    val trafficConnectToStart = t("Connect to start measuring traffic")

    val developerCreditQuotes = listOf(
        "\"Credit is only fair when everyone has their place, and AI deserves to be recognized too.\"",
        "\"Judge the code, not the coder.\"",
    )

    val founderQuote = t("\"Building on open source is one of the most empowering experiences in software development. You do not have to wait for someone else to fix an issue or add a feature — you can build the solution yourself.\"")

    fun connectionTechniqueName(technique: ConnectionTechnique): String = when (technique) {
        ConnectionTechnique.Tun2SocksStable -> technique.canonicalLabel()
        ConnectionTechnique.NativeDirectBeta -> technique.canonicalLabel()
        ConnectionTechnique.Socks5ProxyOnly -> technique.canonicalLabel()
    }

    fun connectionTechniqueDescription(technique: ConnectionTechnique): String = when (technique) {
        ConnectionTechnique.Tun2SocksStable -> t("Stable VPN routing through tun2socks.")
        ConnectionTechnique.NativeDirectBeta -> t("Packet-native IPv4 over one Yume TLS carrier when the server supports it.")
        ConnectionTechnique.Socks5ProxyOnly -> t("Local proxy for apps you configure manually.")
    }

    val nativeDirectFixedTuningName = t("Fixed native tuning")

    val nativeDirectFixedTuningDescription = t("Native direct uses fixed tuning.")

    fun socks5BindModeName(mode: Socks5BindMode): String = when (mode) {
        Socks5BindMode.Loopback -> socks5BindThisDeviceLabel
        Socks5BindMode.Wifi -> socks5BindWifiLabel
        Socks5BindMode.Wildcard -> socks5BindAllLabel
    }

    fun performanceProfileName(profile: PerformanceProfile): String = when (profile) {
        PerformanceProfile.Latency -> t(profile.canonicalLabel())
        PerformanceProfile.Balanced -> t(profile.canonicalLabel())
        PerformanceProfile.Throughput -> t(profile.canonicalLabel())
    }

    fun performanceProfileDescription(profile: PerformanceProfile): String = when (profile) {
        PerformanceProfile.Latency -> t("Lower queueing for interactive traffic.")
        PerformanceProfile.Balanced -> t("Default mixed-use profile.")
        PerformanceProfile.Throughput -> t("Larger buffers for sustained transfers.")
    }

    fun themeColorModeName(mode: ThemeColorMode): String = when (mode) {
        ThemeColorMode.Yume -> "Yume"
        ThemeColorMode.System -> t("System theme")
    }

    fun themeColorModeDescription(mode: ThemeColorMode): String = when (mode) {
        ThemeColorMode.Yume -> t("Muted built-in colors.")
        ThemeColorMode.System -> t("Material You on Android 12+.")
    }

    fun languageName(appLanguage: AppLanguage): String = when (appLanguage) {
        AppLanguage.SystemDefault -> t("System default")
        AppLanguage.Maintained.English -> "English"
        AppLanguage.Maintained.Russian -> "Русский"
        AppLanguage.Maintained.Japanese -> "日本語"
        AppLanguage.Maintained.Ukrainian -> "Українська"
        is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(appLanguage)
    }

    fun languageDescription(appLanguage: AppLanguage): String? = when (appLanguage) {
        AppLanguage.SystemDefault -> followsSystemLabel
        AppLanguage.Maintained.English -> originalLabel
        AppLanguage.Maintained.Russian -> nativeLabel
        AppLanguage.Maintained.Japanese -> nativeLabel
        AppLanguage.Maintained.Ukrainian -> nativeLabel
        is AppLanguage.AutoTranslated -> onDeviceTranslationLabel
    }

    fun languageSummary(appLanguage: AppLanguage): String {
        val description = when (appLanguage) {
            is AppLanguage.AutoTranslated -> autoShortLabel
            else -> languageDescription(appLanguage)
        }
        return if (description.isNullOrBlank()) {
            languageName(appLanguage)
        } else {
            "${languageName(appLanguage)} ($description)"
        }
    }

    fun logLanguageName(logLanguage: LogLanguage): String = when (logLanguage) {
        LogLanguage.FollowApp -> followAppLabel
        LogLanguage.English -> "English"
        LogLanguage.Russian -> "Русский"
        LogLanguage.Japanese -> "日本語"
        LogLanguage.Ukrainian -> "Українська"
    }

    fun logLanguageDescription(logLanguage: LogLanguage): String? = when (logLanguage) {
        LogLanguage.FollowApp -> null
        LogLanguage.English -> originalLabel
        LogLanguage.Russian -> nativeLabel
        LogLanguage.Japanese -> nativeLabel
        LogLanguage.Ukrainian -> nativeLabel
    }

    fun logLanguageSummary(logLanguage: LogLanguage): String {
        val description = logLanguageDescription(logLanguage)
        return if (description.isNullOrBlank()) {
            logLanguageName(logLanguage)
        } else {
            "${logLanguageName(logLanguage)} ($description)"
        }
    }

    fun preparingLanguage(languageName: String): String {
        return tf("Getting %s ready.", languageName)
    }

    fun downloadingLanguage(languageName: String): String {
        return tf("Downloading %s for offline use.", languageName)
    }

    fun translatingLanguage(languageName: String, done: Int, total: Int): String {
        return tf("Translating %s: %d of %d", languageName, done, total)
    }

    fun languagePreparationFailed(languageName: String): String {
        return tf("Couldn't finish %s yet. Try again with a stable connection.", languageName)
    }

    fun deletedLanguageDownload(languageName: String): String {
        return tf("Removed %s download.", languageName)
    }

    fun failedToDeleteLanguageDownload(languageName: String): String {
        return tf("Couldn't remove %s download.", languageName)
    }

    fun materialSaved(typeLabel: String, displayName: String): String {
        return tf("%s saved: %s", typeLabel, displayName)
    }

    fun materialImported(typeLabel: String, displayName: String): String {
        return tf("%s imported: %s", typeLabel, displayName)
    }

    fun failedToReadSelected(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return tf("Failed to read the selected %s", normalizedType)
    }

    fun materialTextEmpty(typeLabel: String): String {
        return tf("No text provided for %s", typeLabel)
    }

    fun failedToSave(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return tf("Failed to save %s", normalizedType)
    }

    fun failedToImport(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return tf("Failed to import %s", normalizedType)
    }

    fun saving(typeLabel: String): String {
        return tf("Saving %s.", typeLabel)
    }

    fun importing(typeLabel: String): String {
        return tf("Importing %s.", typeLabel)
    }

    fun readingSelectedFile(typeLabel: String): String {
        return tf("Reading selected %s file.", typeLabel)
    }

    fun unlockAuthKeyDetail(authKeyName: String): String {
        return tf("Please unlock the key %s.", authKeyName)
    }

    fun unlockingAuthKey(authKeyName: String): String {
        return tf("Unlocking %s.", authKeyName)
    }

    fun savingServer(profileName: String): String {
        return tf("Saving %s.", profileName)
    }

    val updatingAppDataProtection = t("Updating app data protection.")

    fun processingText(actionLabel: String, modeLabel: String): String {
        return tf("%s with %s.", actionLabel, modeLabel)
    }

    fun logAndroidNeedsVpnPermission(): String = t("Android needs VPN permission before Yume can create the interface.")

    fun logAndroidVpnPermissionGranted(): String = t("Android VPN permission granted.")

    fun logAndroidVpnPermissionDenied(): String = t("Android VPN permission was denied.")

    fun logRequestingNotificationPermission(): String = t("Requesting Android notification permission for VPN status updates.")

    fun logOpeningVpnPermissionDialog(): String = t("Opening the Android VPN permission dialog.")

    fun logAndroidVpnPermissionAlreadyGranted(): String = t("Android VPN permission is already granted.")

    fun logSelectedServer(name: String, endpointLabel: String): String =
        tf("Selected server: %s (%s).", name, endpointLabel)

    fun logSelectedAnonymCaUnavailable(): String = t("The selected anonym CA is unavailable.")

    fun logSelectedAuthKeyRequired(): String = t("This server does not have an auth key assigned.")

    fun logSelectedAuthKeyUnavailable(): String = t("The selected auth key is unavailable.")

    fun logCredentialsReady(): String = t("Local CA and auth key are ready. Starting the Android VPN service.")

    fun logTechnique(summary: String): String =
        tf("Technique: %s.", summary)

    fun localizedHeadline(headline: String): String = when (headline) {
        "Disconnected" -> disconnectedHeadline
        "Connection failed" -> connectionFailedHeadline
        "Verifying route" -> verifyingRouteHeadline
        "Protected" -> protectedHeadline
        "Connecting" -> connectingHeadline
        "Disconnecting" -> disconnectingHeadline
        "Waiting for network" -> waitingForNetworkHeadline
        "Upstream lost" -> upstreamLostHeadline
        "Permission required" -> permissionRequiredHeadline
        else -> headline
    }

    fun localizedDetail(detail: String): String = when (detail) {
        "" -> ""
        "Route is up. Verifying traffic." -> verifyingRouteDetail
        "Traffic is flowing through Yume." -> protectedDetail
        "Wi-Fi or cellular is unavailable, so Yume cannot rebuild the protected control path yet." -> waitingForNetworkDetail
        "Wi-Fi or cellular disappeared. Yume is waiting to rebuild the authenticated control path." -> upstreamLostDetail
        "Android needs permission to start the VPN." -> permissionRequiredDetail
        else -> detail
    }

    fun logStartingAndroidSession(name: String, endpointLabel: String): String =
        tf("Starting Yume Android session for %s (%s).", name, endpointLabel)

    fun logRequestedProtectionPolicy(value: String): String =
        tf("Requested protection policy: %s.", value)

    fun logLoadedAnonymCa(displayName: String): String =
        tf("Loaded anonym CA: %s.", displayName)

    fun logLoadedAuthKey(displayName: String): String =
        tf("Loaded auth key: %s.", displayName)

    fun logOpeningAuthenticatedControlPath(networkLabel: String): String =
        tf("Opening authenticated Yume control path on %s.", networkLabel)

    fun logProtectedControlSocket(): String = t("Protected the control socket from the VPN path.")

    fun logSelectedIpv4ControlEndpoint(endpoint: String, host: String): String =
        tf("Selected IPv4 control endpoint %s for %s.", endpoint, host)

    fun logTlsHandshakeCompleted(detail: String): String =
        tf("TLS handshake completed: %s.", detail)

    fun logReceivedAuthChallenge(): String = t("Received Yume AUTH challenge.")

    fun logAuthenticatedToServer(): String = t("Authenticated to the Yume server.")

    fun logRunningRelayCanary(target: String): String =
        tf("Running a Yume relay canary via %s.", target)

    fun logVerifiedAppRelayTraffic(exitIp: String): String =
        tf("Verified app relay traffic via Yume. Exit IP: %s.", exitIp)

    fun logRunningRoutedProbe(): String = t("Running an in-app routed Android traffic probe through Yume.")

    fun logVerifiedSystemTraffic(exitIp: String): String =
        tf("Verified system traffic via Yume. Exit IP: %s.", exitIp)

    fun logTlsHandshakeFailed(detail: String): String =
        tf("TLS handshake failed: %s", detail)

    fun logTlsProviderHandshakeFailed(): String = t("TLS handshake failed on this Android TLS provider")

    fun logHandshakeFailed(): String = t("Handshake failed")

    fun logVpnPrepareFailed(detail: String): String =
        tf("VpnService.prepare() failed: %s", detail)

    fun logUnhandledVpnCoroutineFailure(detail: String): String =
        tf("Unhandled VPN coroutine failure: %s", detail)

    fun logNoUpstreamNetwork(): String = t("No upstream network is available. Waiting for Wi-Fi or cellular.")

    fun logUpstreamNetworkLost(): String = t("Upstream network lost. Waiting for Wi-Fi or cellular.")

    fun logFailedToVerifySystemRouting(detail: String): String =
        tf("Failed to verify systemwide Yume routing: %s", detail)

    fun removed(displayName: String): String = tf("%s removed", displayName)

    fun reconnectFor(label: String): String {
        return tf("Reconnect for %s.", label)
    }

    fun checkingKeysFor(endpointLabel: String): String {
        return tf("Checking keys and VPN permission for %s.", endpointLabel)
    }

    fun loadingKeysFor(endpointLabel: String): String {
        return tf("Loading keys and opening a control path to %s.", endpointLabel)
    }

    fun usingAnonymCa(displayName: String): String {
        return tf("Using %s", displayName)
    }

    fun selectedServerSummary(
        profileName: String,
        selectedCaName: String,
        selectedAuthKeyName: String,
    ): String {
        return tf("Selected server: %s, %s: %s, %s: %s", profileName, anonymCaLabel, selectedCaName, authKeyLabel, selectedAuthKeyName)
    }

    fun currentPhaseSummary(phase: String, technique: String, profile: String): String {
        return tf("Current phase: %s • Technique: %s • Profile: %s", phase, technique, profile)
    }

    fun appVersion(value: String): String = tf("App: %s", value)

    fun routePath(technique: String, profile: String): String {
        return tf("Path: %s • %s", technique, profile)
    }

    fun exitIpAndUpstream(exitIp: String, upstream: String): String {
        return tf("Exit IP: %s • Upstream: %s", exitIp, upstream)
    }

    fun anonymSummary(required: Boolean): String {
        val value = if (required) anonymRequired else anonymOptional
        return "$anonymLabel: $value"
    }

    fun authKeySummary(displayName: String): String {
        return tf("Auth key: %s", displayName)
    }

    fun materialTypeLabel(type: SecureMaterialType): String = when (type) {
        SecureMaterialType.AnonymCa -> anonymCaLabel
        SecureMaterialType.AuthKey -> authKeyLabel
    }

    fun anonymWithVerity(veritySummary: String): String = "$anonymLabel $veritySummary"

    fun boolValue(value: Boolean): String = if (value) yes else no

    fun notificationLocation(flagEmoji: String?, countryName: String?): String {
        val normalizedFlag = flagEmoji?.trim().orEmpty()
        val normalizedCountry = countryName?.trim().orEmpty()
        return when {
            normalizedFlag.isNotEmpty() && normalizedCountry.isNotEmpty() -> "$normalizedFlag $normalizedCountry"
            normalizedCountry.isNotEmpty() -> normalizedCountry
            normalizedFlag.isNotEmpty() -> normalizedFlag
            else -> t("Unknown location")
        }
    }

    fun notificationProtectedIn(location: String): String {
        return tf("Protected in %s", location)
    }

    fun notificationTrafficTotals(download: String, upload: String): String {
        return "↓ $download   ↑ $upload"
    }

    fun buildVersionLabel(versionName: String, versionCode: Int, debug: Boolean): String {
        return if (debug) {
            tf("DEBUG build v%s (%d)", versionName, versionCode)
        } else {
            "v$versionName"
        }
    }

    fun phaseStatus(phase: VpnPhase, protected: Boolean, proxyReady: Boolean): String {
        return when (phase) {
            VpnPhase.Idle -> t("Idle")
            VpnPhase.PermissionRequired -> t("Permission needed")
            VpnPhase.Connecting -> t("Connecting")
            VpnPhase.Verifying -> t("Verifying")
            VpnPhase.Connected -> when {
                protected -> t("Protected")
                proxyReady -> t("Proxy ready")
                else -> t("Connected")
            }
            VpnPhase.Disconnecting -> t("Disconnecting")
            VpnPhase.Error -> t("Error")
        }
    }

    companion object {
        private val translationSeeds: List<String> by lazy {
            val collector = linkedSetOf<String>()
            val seedStrings = AppStrings(
                language = ResolvedAppLanguage.English,
                seedCollector = collector,
            )
            seedStrings.javaClass.declaredMethods
                .filter { it.parameterCount == 0 && it.returnType == String::class.java && it.name.startsWith("get") }
                .sortedBy { it.name }
                .forEach { runCatching { it.invoke(seedStrings) } }
            collector += listOf(
                "Idle", "Permission needed", "Connecting", "Verifying",
                "Protected", "Proxy ready", "Connected", "Disconnecting", "Error",
                "Latency", "Balanced", "Throughput",
                "Stable VPN routing through tun2socks.",
                "Direct Yume TCP streams. UDP falls back to TCP; IPv6 is blocked.",
                "Local proxy for apps you configure manually.",
                "Lower queueing for interactive traffic.",
                "Default mixed-use profile.",
                "Larger buffers for sustained transfers.",
            )
            collector.toList()
        }

        fun forLanguage(
            language: ResolvedAppLanguage,
            autoTranslate: ((String) -> String)? = null,
        ): AppStrings = AppStrings(language, autoTranslate)

        fun translationSeeds(): List<String> = translationSeeds
    }
}

fun appStrings(
    language: AppLanguage,
    systemLocale: Locale = Locale.getDefault(),
    autoTranslate: ((String) -> String)? = null,
): AppStrings = AppStrings.forLanguage(language.resolve(systemLocale), autoTranslate)

fun appStrings(
    context: Context,
    language: AppLanguage,
): AppStrings {
    val resolved = AppLocaleManager.resolveLanguage(context, language)
    val autoTranslate = (resolved as? ResolvedAppLanguage.AutoTranslated)?.let { auto ->
        { english: String ->
            AutoTranslationRuntime.getOrQueue(
                context = context,
                targetLocaleTag = auto.localeTag,
                english = english,
            )
        }
    }
    return AppStrings.forLanguage(resolved, autoTranslate)
}

private val LocalAppStrings = staticCompositionLocalOf { appStrings(AppLanguage.SystemDefault) }

@Composable
fun ProvideAppStrings(
    context: Context,
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val resolved = remember(context, language) { AppLocaleManager.resolveLanguage(context, language) }
    val autoLocaleTag = (resolved as? ResolvedAppLanguage.AutoTranslated)?.localeTag
    val revision by remember(autoLocaleTag) {
        autoLocaleTag?.let(AutoTranslationRuntime::revision)
            ?: MutableStateFlow(0)
    }.collectAsState()
    LaunchedEffect(autoLocaleTag) {
        autoLocaleTag?.let { localeTag ->
            AutoTranslationRuntime.prewarm(
                context = context,
                targetLocaleTag = localeTag,
                seeds = AppStrings.translationSeeds(),
            )
        }
    }
    val strings = remember(language, resolved, revision) {
        when (resolved) {
            is ResolvedAppLanguage.AutoTranslated -> AppStrings.forLanguage(resolved) { english ->
                AutoTranslationRuntime.getOrQueue(
                    context = context,
                    targetLocaleTag = resolved.localeTag,
                    english = english,
                )
            }
            else -> AppStrings.forLanguage(resolved)
        }
    }
    CompositionLocalProvider(LocalAppStrings provides strings, content = content)
}

@Composable
fun appStrings(): AppStrings = LocalAppStrings.current
