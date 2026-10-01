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
import com.fixcraft.yume.data.LogLanguage
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.ThemeColorMode
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
    val logLanguageLabel = t("Log language")
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

    val connectionGroupTitle = t("Transport")
    val transportFixedSummary =
        t("Android runs one transport. It is fixed by the protocol, not selectable.")
    val transportModeValue = "packet_bulk_v1 (IPv4)"
    val carrierLabel = t("Carrier")
    val carrierValue = "TLS 1.3 + HTTP/2 WebSocket"
    val authenticationLabel = t("Authentication")
    val authenticationValue = "Ed25519 + ML-DSA-87 (AUTH v2)"
    val innerValue = "ML-KEM-1024 + X25519 + PSK, AES-256-GCM"
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
    val localSelfTestTitle = t("Local self-test")
    val localSelfTestDetail = t("Measures this device's Yume hot paths without contacting a server.")
    val runQuickBenchmarkButton = t("Quick test")
    val runFullBenchmarkButton = t("Full benchmark")
    val copyLocalSelfTestReport = t("Copy local self-test report")
    val localSelfTestRunning = t("Running local self-test")
    val localBenchmarkRunning = t("Running full benchmark")
    val localSelfTestCopied = t("Local self-test report copied.")
    val localSelfTestNoResults = t("No local self-test results yet.")
    val showBenchmarkDetails = t("Show detailed stats")
    val quickBenchmarkNoScore = t("Full mode computes a GLOBAL score.")
    fun benchmarkScoreValue(label: String, score: Long, grade: String) =
        t("{label} {score} · {grade}")
            .replace("{label}", label)
            .replace("{score}", "%,d".format(java.util.Locale.US, score))
            .replace("{grade}", grade)
    fun benchmarkProgressValue(label: String, percent: Int) =
        t("{label}: {percent}%")
            .replace("{label}", label)
            .replace("{percent}", percent.coerceIn(0, 100).toString())
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
    val developerQuoteLabel = t("Recognition")
    val founderNoteLabel = t("From F1xGOD")
    val yumeLabel = "Yume"
    val basefwxLabel = "BaseFWX"
    val diagnosticsButton = t("Diagnostics")
    val batterySettingsButton = t("Battery settings")
    val notificationSettingsButton = t("Notification settings")
    val notificationsLabel = t("Notifications")
    val batteryOptimizationLabel = t("Battery optimization")
    val dynamicColorsLabel = t("Dynamic colors")
    val allowed = t("Allowed")
    val permissionRequired = t("Permission required")
    val optimized = t("Optimized")
    val unrestricted = t("Unrestricted")
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
    val addFirstGatewayDetail = t("A server profile stores host, TLS name, DNS, MTU, operator CA, and auth key. Your first saved profile becomes active.")

    val securitySubtitle = t("CA and auth keys.")
    val selectedServerLabel = t("Selected server")
    val missing = t("Missing")
    val notAssigned = t("Not assigned")
    val pasteAnonymCaTitle = t("Paste operator CA")
    val importedCaName = t("Imported CA")
    val pasteAuthKeyTitle = t("Paste auth key")
    val importAuthKeyTitle = t("Import auth key")
    val importedAuthKeyName = t("Imported auth key")
    val embeddedAnonymCaName = t("Embedded FixCraft operator CA")
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
    val anonymLabel = t("Operator identity")
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
    val routingShortLabel = t("Routing")
    val tunnelActive = t("Tunnel active")
    val importPemAuthKeyHint = t("Import a PEM auth key below, then edit this server to assign it before connecting.")
    val assignImportedAuthKeyHint = t("Edit this server and assign one of the imported auth keys before connecting.")

    val diagnosticsSubtitle = t("Session log and build info.")
    val copyDiagnostics = t("Copy diagnostics")
    val shareDiagnostics = t("Share diagnostics")
    val shareDiagnosticsSubject = t("Yume Android diagnostics")
    val shareDiagnosticsChooser = t("Share diagnostics")
    val reportTitle = t("Report")
    val rawThroughputLabel = t("Raw throughput")
    val smoothedThroughputLabel = t("Smoothed throughput")
    val yumeStatusTitle = t("Yume status")
    val briefLabel = t("Brief")
    val clientLabel = t("Client")
    val connectionLabel = t("Connection")
    val serverModeLabel = t("Server mode")
    val innerLabel = t("Inner tunnel")
    val ratchetLabel = t("Ratchet")
    val verityLabel = t("Operator verification")
    val routeLabel = t("Route")
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

    val trustAuthHeader = t("Trust & auth")
    val trustAuthDescription = t("Imported PEM certificates and keys stay encrypted in the app and out of cloud backup.")
    val anonymCaCertificatesTitle = t("Operator CA certificates")
    val anonymCaCertificatesSubtitle = t("The built-in FixCraft operator CA starts available but can be removed. Import the hoster's PEM CA, or leave operator authorization unassigned when the profile accepts monitoring.")
    val noCustomCaImported = t("No custom CAs imported.")
    val authKeysTitle = t("Auth keys")
    val authKeysSubtitle = t("A Yume auth key is one composite identity file: an Ed25519 PEM private-key block followed by an ML-DSA-87 block. Generate it with `yume --keys-gen` on the desktop, then assign it to a server.")
    val authKeyImportModeHelper = t("This is the input format. A plain composite PEM is accepted as-is. If the key was encrypted with BaseFWX, switch to Encrypted and enter its password.")
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
    val transportSecurityTitle = t("Transport security")
    val transportSecurityFixedDescription = t("Fixed by the protocol, not configurable. Every session uses composite Ed25519 + ML-DSA-87 authentication and an ML-KEM-1024 / X25519 / PSK directional ratchet inside the HTTP/2 carrier.")
    val admissionSecretLabel = t("Admission secret")
    val admissionSecretHelper = t("Required. Must match the server's --obfs-secret.")
    val innerPskLabel = t("Inner PSK")
    val innerPskHelper = t("Required. Must match the server's inner PSK file.")
    val hex64Requirement = t("Exactly 64 lowercase hex characters")
    val tlsPinLabel = t("TLS leaf SHA-256 pin")
    val tlsPinHelper = t("Optional. 64 lowercase hex characters when set.")
    val tlsProfileLabel = t("TLS profile")
    val tlsProfileHelper = t("Applied by the shared native YUME transport.")
    val dnsServersLabel = t("DNS servers")
    val dnsServersHelper = t("Comma or newline separated numeric IPv4 or IPv6 addresses.")
    val mtuHelper = t("Android VPNs are most stable between 1280 and 1500.")
    val requireAnonymProofTitle = t("Require operator identity proof")
    val requireAnonymProofEnabled = t("Only allow a server authorized by the selected operator CA. This does not prove the operator cannot inspect or log traffic.")
    val requireAnonymProofDisabled = t("Allow servers without operator identity proof. The server operator may monitor proxied traffic.")
    val anonymCaChoiceTitle = t("Operator CA")
    val anonymCaChoiceHelperRequired = t("Choose the CA that is expected to authorize this server operator.")
    val anonymCaChoiceHelperOptional = t("Choose the CA used to verify operator authorization when the server provides a proof.")
    val authKeyChoiceTitle = t("Auth key")
    val authKeyChoiceHelper = t("Choose the composite identity used for client auth. Leave empty only if you are not ready to connect yet.")
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
    val authKeyTextHelper = t("Paste both PEM private-key blocks, Ed25519 first, or switch to Encrypted and enter the BaseFWX password.")

    val diagnosticsReportHeader = t("Yume Android Diagnostics")
    val appLabel = t("App")
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
    val anonymPolicyLabel = t("Operator identity policy")
    val anonymCaLabel = t("Operator CA")
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
    val runtimeIpv6PolicyLabel = t("IPv6 policy")
    val runtimeIpv6FailClosedStatus = t("blocked")
    val runtimeDnsPolicyLabel = t("DNS policy")
    val runtimeIpv4OnlyDnsPolicy = t("IPv4-only; the server assigns the resolvers")
    val runtimePacketNativeStatus = t("shared native packet data plane")
    val runtimeIdleStatus = t("not connected")
    val runtimeBatchLimitLabel = t("Packet batch limit")
    val assignedAddressLabel = t("Assigned IPv4")
    val qualificationLabel = t("Qualification")
    val androidUnqualifiedStatus =
        t("Android device traffic, lifecycle, and throughput gates are still open.")
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
    val admissionSecretFormatError = t("Admission secret must be exactly 64 lowercase hex characters")
    val innerPskFormatError = t("Inner PSK must be exactly 64 lowercase hex characters")
    val tlsPinFormatError = t("TLS pin must be exactly 64 lowercase hex characters")
    val admissionSecretRequiredHeadline = t("Admission secret required")
    val admissionSecretRequiredDetail = t("Enter the server's 64-character lowercase hex admission secret.")
    val innerPskRequiredHeadline = t("Inner PSK required")
    val innerPskRequiredDetail = t("Enter the server's 64-character lowercase hex inner PSK.")
    val invalidTlsPinHeadline = t("Invalid TLS pin")
    val invalidTlsPinDetail = t("The TLS pin must be 64 lowercase hex characters.")
    val anonymCaUnavailable = t("Selected operator CA is unavailable")
    val authKeyUnavailable = t("Selected auth key is unavailable")
    val serverSaved = t("Server saved")
    val amoledOn = t("AMOLED on")
    val amoledOff = t("AMOLED off")
    val permissionRequiredHeadline = t("Permission required")
    val permissionRequiredDetail = t("Android needs permission to start the VPN.")
    val preparingHeadline = t("Preparing")
    val anonymCaMissingHeadline = t("Operator CA missing")
    val anonymCaMissingDetail = t("Select an available operator CA, or disable required operator proof if you accept monitoring by this server.")
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
    val startingSharedClientDetail = t("Starting the shared YUME client.")
    val packetTransportConnectedDetail = t("Shared native packet transport is connected. Android device traffic, lifecycle, and throughput gates are still open.")
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
    val claudeRoleLabel = t("Supporting AI contributor")
    val claudeCreditRole = t("Selected code reviews, refactors, and bug-fix contributions.")
    val codexTitleLabel = t("Primary AI engineering partner")
    val codexCreditRole = t("Architecture, implementation, security hardening, testing, and documentation across Yume, BaseFWX, desktop, and Android.")
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

    val componentConscryptTitle = "Conscrypt"
    val componentConscryptUsage = t("JVM-side TLS provider wrapping BoringSSL. The YUME carrier's TLS lives in the shared native client, not here.")
    val componentConscryptLicense = "Apache-2.0"

    val componentComposeTitle = "Jetpack Compose · Material 3"
    val componentComposeUsage = t("Declarative UI toolkit that renders every screen in this app.")
    val componentComposeLicense = "Apache-2.0"

    val componentCoroutinesTitle = "kotlinx.coroutines"
    val componentCoroutinesUsage = t("Structured concurrency under every suspend/await in the VPN service.")
    val componentCoroutinesLicense = "Apache-2.0"
    val liveTrafficTitle = t("Live traffic")
    val trafficCollectingSamples = t("Collecting samples…")
    val trafficConnectToStart = t("Connect to start measuring traffic")

    val developerCreditQuotes = listOf(
        "\"Credit is only fair when everyone has their place, and AI deserves to be recognized too.\"",
        "\"Judge the code, not the coder.\"",
    )

    val founderQuote = t("\"Building on open source is one of the most empowering experiences in software development. You do not have to wait for someone else to fix an issue or add a feature — you can build the solution yourself.\"")

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

    fun localSelfTestCompleted(elapsed: String): String {
        return tf("Completed in %s", elapsed)
    }

    fun logAndroidNeedsVpnPermission(): String = t("Android needs VPN permission before Yume can create the interface.")

    fun logAndroidVpnPermissionGranted(): String = t("Android VPN permission granted.")

    fun logAndroidVpnPermissionDenied(): String = t("Android VPN permission was denied.")

    fun logRequestingNotificationPermission(): String = t("Requesting Android notification permission for VPN status updates.")

    fun logOpeningVpnPermissionDialog(): String = t("Opening the Android VPN permission dialog.")

    fun logAndroidVpnPermissionAlreadyGranted(): String = t("Android VPN permission is already granted.")

    fun logSelectedServer(name: String, endpointLabel: String): String =
        tf("Selected server: %s (%s).", name, endpointLabel)

    fun logSelectedAnonymCaUnavailable(): String = t("The selected operator CA is unavailable.")

    fun logSelectedAuthKeyRequired(): String = t("This server does not have an auth key assigned.")

    fun logSelectedAuthKeyUnavailable(): String = t("The selected auth key is unavailable.")

    fun logCredentialsReady(): String = t("Local CA and auth key are ready. Starting the Android VPN service.")

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
        "Starting the shared YUME client." -> startingSharedClientDetail
        "Shared native packet transport is connected. Android device traffic, lifecycle, and throughput gates are still open." ->
            packetTransportConnectedDetail
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
        tf("Loaded operator CA: %s.", displayName)

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

    fun checkingKeysFor(endpointLabel: String): String {
        return tf("Checking keys and VPN permission for %s.", endpointLabel)
    }

    fun loadingKeysFor(endpointLabel: String): String {
        return tf("Loading keys and opening a control path to %s.", endpointLabel)
    }

    fun selectedServerSummary(
        profileName: String,
        selectedCaName: String,
        selectedAuthKeyName: String,
    ): String {
        return tf("Selected server: %s, %s: %s, %s: %s", profileName, anonymCaLabel, selectedCaName, authKeyLabel, selectedAuthKeyName)
    }

    fun currentPhaseSummary(phase: String, route: String): String {
        return tf("Current phase: %s • Route: %s", phase, route)
    }

    fun appVersion(value: String): String = tf("App: %s", value)

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

    fun phaseStatus(phase: VpnPhase, protected: Boolean, routeReady: Boolean): String {
        return when (phase) {
            VpnPhase.Idle -> t("Idle")
            VpnPhase.PermissionRequired -> t("Permission needed")
            VpnPhase.Connecting -> t("Connecting")
            VpnPhase.Verifying -> t("Verifying")
            VpnPhase.Connected -> when {
                protected -> t("Protected")
                routeReady -> t("Route ready")
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
                "Protected", "Route ready", "Connected", "Disconnecting", "Error",
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
