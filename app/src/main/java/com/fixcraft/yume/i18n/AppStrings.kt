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
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.data.KitRefusal
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.i18n.translations.LanguageJa
import com.fixcraft.yume.i18n.translations.LanguageRu
import com.fixcraft.yume.i18n.translations.LanguageUk
import com.fixcraft.yume.vpn.UpstreamKind
import com.fixcraft.yume.vpn.VpnFailure
import com.fixcraft.yume.vpn.VpnPhase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One generated bundle per maintained non-English language. Add or edit messages
 * in YUME's canonical `translations/catalog.json`, then regenerate this checkout;
 * do not hand-edit the Kotlin maps.
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
        val id = TranslationCatalog.sourceToId[english]
        return (id?.let { translationMap?.get(it) })
            ?: autoTranslate?.invoke(english)
            ?: english
    }

    val back = t("Back")
    val close = t("Close")
    val cancel = t("Cancel")
    val save = t("Save")
    val delete = t("Delete")

    // Navigation.
    val overviewTab = t("Overview")
    val connectTab = t("Connect")
    val logsTab = t("Logs")
    val postureTab = t("Posture")
    val settingsTab = t("Settings")

    // Languages.
    val languageGroupTitle = t("Language")
    val maintainedLanguagesTitle = t("Maintained")
    val autoTranslatedLanguagesTitle = t("Auto-translated")
    val appLanguageLabel = t("App language")
    val originalLabel = t("Original")
    val followsSystemLabel = t("Follows your phone")
    val onDeviceTranslationLabel = t("On-device translation")
    val autoShortLabel = t("Auto")
    val nativeLabel = t("Native")
    val downloadedLabel = t("Downloaded")
    val removeDownloadedLanguageLabel = t("Remove download")
    val preparingLanguageTitle = t("Preparing language")
    val downloadingLanguageTitle = t("Downloading language data")
    val translatingLanguageTitle = t("Translating the app")
    val systemDefaultLanguage = t("System default")
    val preparingLanguageTemplate = t("Getting %s ready.")
    val downloadingLanguageTemplate = t("Downloading %s for offline use.")
    val translatingLanguageTemplate = t("Translating %s: %d of %d")
    val languagePreparationFailedTemplate = t("Couldn't finish %s yet. Try again with a stable connection.")
    val deletedLanguageDownloadTemplate = t("Removed %s download.")
    val failedToDeleteLanguageDownloadTemplate = t("Couldn't remove %s download.")

    // Overview.
    val connect = t("Connect")
    val disconnect = t("Disconnect")
    val noKitTitle = t("No server yet")
    val noKitDetail = t("Add the kit your server's owner gave you, then connect.")
    val addKit = t("Add a kit")
    val phaseIdle = t("Ready")
    val phasePermission = t("Permission needed")
    val phaseConnecting = t("Connecting")
    val phaseConnected = t("Protected")
    val phaseReconnecting = t("Reconnecting")
    val phaseDisconnecting = t("Disconnecting")
    val phaseError = t("Not connected")
    val idleDetail = t("Tap Connect.")
    val permissionDetail = t("Android needs your permission to start the VPN.")
    val connectingDetail = t("Opening the first session with the server.")
    val waitingForNetworkTitle = t("Waiting for network")
    val waitingForNetworkDetail = t("Wi-Fi or mobile data is unavailable. Yume connects when one returns.")
    val connectedDetail = t("Traffic from this device goes through the server.")
    val reconnectingDetail = t("The session was lost. Traffic waits until it returns.")
    val nextAttemptTemplate = t("Next attempt in %d s.")
    val disconnectingDetail = t("Closing the session.")
    val failureVpnPermissionTitle = t("VPN not allowed")
    val failureVpnPermissionDetail = t("Android did not let Yume create the VPN. Another VPN may be set as always-on.")
    val failureKitTitle = t("Kit unavailable")
    val failureKitDetail = t("The kit is no longer stored, or the app's data is locked. Open Yume and unlock it.")
    val failureResolveTitle = t("Server name not found")
    val failureResolveDetail = t("The server's name did not resolve on this network.")
    val failureUnreachableTitle = t("Server unreachable")
    val failureUnreachableDetail = t("The server did not answer. Check the network, or ask the server's owner.")
    val failureRefusedTitle = t("Server refused this kit")
    val failureRefusedDetail = t("The server did not accept this kit's keys. Ask its owner for a new kit.")
    val failureConfigurationTitle = t("Kit not supported")
    val failureConfigurationDetail = t("This kit's configuration is not one this version of Yume runs.")
    val failureDeviceTitle = t("VPN interface failed")
    val failureDeviceDetail = t("Android closed the VPN interface.")
    val connectedForLabel = t("Connected for")
    val networkLabel = t("Network")
    val serverAddressLabel = t("Server address")
    val openConnectionsLabel = t("Open connections")
    val wifi = "Wi-Fi"
    val cellular = t("Mobile data")
    val ethernet = t("Ethernet")
    val otherNetwork = t("Other")
    val trafficTitle = t("Traffic")
    val trafficHistoryTitle = t("Traffic history")
    val trafficRatesDetail = t("Smoothed payload rates · totals for this run")
    val trafficUpload = t("Upload")
    val trafficDownload = t("Download")
    val trafficRateUnavailable = t("Rate unavailable")
    val trafficLatestSample = t("Latest sample")
    private val trafficSecondsTemplate = t("%d s")
    private val trafficMinutesTemplate = t("%d min")
    private val trafficWindowTemplate = t("Last %s")
    private val trafficTotalTemplate = t("Total %s")
    fun trafficWindow(seconds: Int): String = if (seconds < 60) fill(trafficSecondsTemplate, seconds)
        else fill(trafficMinutesTemplate, seconds / 60)
    fun trafficWindowDescription(label: String): String = fill(trafficWindowTemplate, label)
    fun trafficTotal(bytes: String): String = fill(trafficTotalTemplate, bytes)
    val trafficCollectingSamples = t("Collecting samples…")
    val trafficConnectToStart = t("Connect to start measuring traffic")

    // Connect: kits.
    val kitsTitle = t("Kits")
    val kitsSubtitle = t("A kit holds one server's address and your keys for it. The server's owner seals it into a file and tells you its code.")
    val noKitsYet = t("No kits yet.")
    val selected = t("Selected")
    val live = t("Live")
    val renameKit = t("Rename")
    val renameKitTitle = t("Rename kit")
    val kitNameLabel = t("Name")
    val kitNameHelper = t("Shown in this app only.")
    val deleteKitTitle = t("Delete this kit?")
    val deleteKitDetail = t("Its keys are removed from this device. You need the file and its code to add it again.")
    val addKitTitle = t("Add a kit")
    val kitFileLabel = t("Kit file")
    val chooseFile = t("Choose file")
    val noFileChosen = t("No file chosen")
    val kitCodeLabel = t("Code")
    val kitCodeHelper = t("25 letters and digits, shown when the kit was sealed.")
    val importKit = t("Add")
    val openingKit = t("Opening the kit")
    val openingKitDetail = t("This takes a few seconds.")
    val kitAddedTemplate = t("Kit added: %s")
    val kitFileUnreadable = t("Couldn't read the selected file.")
    val kitRefusedWrongCode = t("This code does not open this file. Check the code, and that the file arrived unchanged.")
    val kitRefusedBadCode = t("The code is 25 letters and digits.")
    val kitRefusedTooLarge = t("This file is too large to be a kit.")
    val kitRefusedNotAKit = t("This file does not hold a kit this version of Yume can use.")
    val kitRefusedCircuits = t("This kit routes through several servers, which this app does not support yet.")
    val kitRefusedStorage = t("The kit could not be stored. Unlock the app's data and try again.")

    // Logs.
    val sessionLogTitle = t("Session log")
    val sessionLogSubtitle = t("What the connection did, newest last. Lines from the YUME library are in English.")
    val noSessionEventsYet = t("No session events yet.")
    val copyReport = t("Copy report")
    val shareReport = t("Share report")
    val clearLog = t("Clear")
    val reportCopied = t("Report copied.")
    val shareReportSubject = t("Yume Android report")

    // Posture.
    val postureConnectionTitle = t("The connection")
    val postureConnectionDetail = t("Both sides prove their identity with Ed25519 and ML-DSA-87 keys and agree on keys with X25519 and ML-KEM-1024, inside TLS 1.3. None of this can be switched off or chosen.")
    val protocolLabel = t("Protocol")
    val suiteLabel = t("Suite")
    val libraryLabel = t("YUME library")
    val cryptoBackendLabel = t("Cryptography")
    val serverIdentityLabel = t("Server identity (SHA-256)")
    val keyEpochLabel = t("New keys every")
    val notConnectedValue = t("Not connected")
    val postureServerTitle = t("What the server sees")
    val postureServerDetail = t("The server decrypts your traffic to pass it on. Its owner can see which addresses you reach and anything that is not encrypted by itself, such as plain HTTP. Nothing proves that a server keeps no logs, so use one you trust.")
    val postureDeviceTitle = t("On this device")
    val routesLabel = t("Through the tunnel")
    val routesAllIpv4 = t("All IPv4 traffic")
    val routesAllTraffic = t("All IPv4 and IPv6 traffic")
    val ipv6Label = "IPv6"
    val ipv6Blocked = t("Blocked while connected")
    val ipv6Carried = t("Through the tunnel")
    val nameLookupsLabel = t("Name lookups")
    val nameLookupsTemplate = t("Through the tunnel to %s")
    val sessionLossLabel = t("If the session drops")
    val sessionLossValue = t("Traffic waits and Yume reconnects")
    val appDataLabel = t("Kits on this device")
    val appDataKeystore = t("Encrypted, key in Android Keystore")
    val appDataPassword = t("Encrypted, Keystore and your password")
    val backupsLabel = t("Backups")
    val backupsValue = t("Kits are never backed up")
    val postureStopTitle = t("If the VPN stops")
    val postureStopDetail = t("When Yume is disconnected or stopped, Android sends traffic directly. To prevent that, turn on Always-on VPN and Block connections without VPN for Yume in Android's settings.")
    val openVpnSettings = t("Android VPN settings")
    val postureLimitsTitle = t("Limits")
    val postureLimitsDetail = t("YUME makes its connection look like a browser's. No tool can promise that it is never recognized or blocked.")

    // Settings.
    val networkGroupTitle = t("Network")
    val dnsServersLabel = t("DNS servers")
    val dnsServersHelper = t("Up to four IP addresses, separated by commas. Lookups go through the tunnel.")
    val dnsServerFormatError = t("Enter one to four IP addresses.")
    val carryIpv6Title = t("IPv6 through the tunnel")
    val carryIpv6Description = t("Off, IPv6 is blocked while connected. Turn on only if the server reaches IPv6.")
    val appliesOnNextConnect = t("Applies the next time you connect.")
    val themeGroupTitle = t("Theme")
    val advancedGroupTitle = t("Advanced")
    val appDataGroupTitle = t("App data")
    val advancedModeTitle = t("Advanced mode")
    val advancedModeDescription = t("Show network settings.")
    val tuningGroupTitle = t("Connection tuning")
    val tuningSubtitle = t("Choose the shared YUME posture for the selected kit. Changes apply the next time you connect.")
    val tuningNoKit = t("Select a kit to tune its connection.")
    val tuningStealth = t("Stealth")
    val tuningSpeed = t("Speed")
    val tuningSecurity = t("Security")
    val tuningStealthNote = t("Changes windows, credit returns, and idle rotation; it does not promise classifier results.")
    val tuningSpeedNote = t("Measured positions under the stated host and network conditions; fast and max need higher host TCP ceilings.")
    val tuningSecurityNote = t("Data per key epoch; algorithms and mandatory rotation stay the same.")
    val tuningLevelTemplate = t("Level %1\$d of 5")
    val tuningApply = t("Apply tuning")
    val tuningSaved = t("Tuning saved. It will be used on the next connection.")
    val tuningSaveFailed = t("Yume refused these limits. Nothing was changed.")
    val tuningCustom = t("Custom limits")
    val tuningExpert = t("Expert editing")
    val tuningExpertWarning = t("Expert values change the connection's resource limits. Invalid or unsafe values are refused by YUME. Edit one field at a time only if you understand the protocol.")
    val tuningExpertConfirm = t("I know what I'm doing")
    val tuningExpertUnlock = t("Unlock expert editing")
    val tuningExpertLocked = t("Expert editing is locked until you acknowledge the warning.")
    val tuningExpertOneAtTime = t("Apply one field at a time. The native core checks each value before saving.")
    val tuningFieldEpoch = t("Data per key epoch")
    val tuningFieldFlow = t("Flow budget")
    val tuningFieldCredits = t("Credit returns per window")
    val tuningFieldIdle = t("Rotate keys while idle")
    val tuningOn = t("On")
    val tuningOff = t("Off")
    val tuningMeasuredTitle = t("Measured speed conditions")
    val tuningEpochConditions = t("One host, one session, one download, loss-free emulated RTTs of 40, 100 and 200 ms, 6 MiB host TCP ceilings. Six 12-second samples per condition, tail-rate medians. Both peers use the selected limits.")
    val tuningWindowConditions = t("One host, one session, one download, loss-free emulated RTTs of 40, 100 and 200 ms, host TCP ceilings raised to 64 MiB. Both peers use the selected limits.")
    val tuningMeasurementLimit = t("Emulated paths only. Not a real-path speed or stealth claim.")
    val tuningWindowLimit = t("A separate run from epoch-comparison. Host ceilings and the software emulator limit results. Not a real-path speed or stealth claim.")
    val amoledTitle = t("AMOLED black")
    val amoledDescription = t("Use true black in dark mode.")
    val themeSystem = t("System theme")
    val themeYumeDescription = t("Muted built-in colors.")
    val themeSystemDescription = t("Material You on Android 12+.")
    val appDataSubtitle = t("Use Android protection by default, or add a password before the app opens.")
    val appDataProtectionLabel = t("Protection")
    val androidProtected = t("Android secure")
    val passwordProtected = t("Password required")
    val setPasswordButton = t("Set password")
    val changePasswordButton = t("Change password")
    val removePasswordButton = t("Remove password")
    val unlockAppDataTitle = t("Unlock app data")
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
    val deviceGroupTitle = t("Device")
    val aboutGroupTitle = t("About")
    val systemGroupTitle = t("System")
    val creditsTitle = t("Credits")
    val authorLabel = t("Author")
    val companyLabel = t("Company")
    val appLabel = t("App")
    val yumeLabel = "Yume"
    val basefwxLabel = "BaseFWX"
    val batterySettingsButton = t("Battery settings")
    val allowBackground = t("Allow background")
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

    // Notification.
    val notificationConnected = t("Protected")
    val notificationConnecting = t("Connecting")
    val notificationReconnecting = t("Reconnecting")
    val notificationWaitingForNetwork = t("Waiting for network")
    val notificationDisconnecting = t("Disconnecting")

    // Credits and components.
    val developerQuoteLabel = t("Recognition")
    val founderNoteLabel = t("From F1xGOD")
    val founderCeoRole = t("Founder & CEO, FixCraft, Inc.")
    val leadDeveloperDesignerRole = t("Author. Lead developer and designer of Yume and BaseFWX.")
    val claudeRoleLabel = t("Supporting AI contributor")
    val claudeCreditRole = t("Selected code reviews, refactors, and bug-fix contributions.")
    val codexTitleLabel = t("Primary AI engineering partner")
    val codexCreditRole = t("Architecture, implementation, security hardening, testing, and documentation across Yume, BaseFWX, desktop, and Android.")
    val openSourceComponentsTitle = t("Open-source components")
    val openSourceComponentsLink = t("View open-source components")
    val openSourceComponentsSubtitle = t("Third-party code Yume builds on, with licences.")
    val componentGroupLibrary = t("In the YUME library")
    val componentGroupLibraryBlurb = t("Compiled into libyume, which carries the connection on every platform.")
    val componentGroupAndroidOnly = t("Used only in the Android implementation of Yume")
    val componentGroupAndroidOnlyBlurb = t("Compiled into this app. The desktop client uses different libraries for these jobs.")
    val componentOpensslUsage = t("TLS 1.3 and every cryptographic primitive of the connection, with YUME's patch for the browser's handshake shape.")
    val componentBoostUsage = t("Asynchronous networking and timers.")
    val componentNghttp2Usage = t("HTTP/2 framing of the carrier.")
    val componentCompressionUsage = t("Certificate compression in the TLS handshake.")
    val componentJsonUsage = t("Reading the configuration.")
    val componentBasefwxUsage = t("Wraps this app's stored data under the optional app-data password.")
    val componentBouncyCastleUsage = t("Primitives BaseFWX's Java runtime uses.")
    val componentComposeUsage = t("Declarative UI toolkit that renders every screen in this app.")
    val componentCoroutinesUsage = t("Structured concurrency in the VPN service.")
    val componentMlKitUsage = t("On-device translation for languages without a maintained translation.")

    val developerCreditQuotes = listOf(
        "\"Credit is only fair when everyone has their place, and AI deserves to be recognized too.\"",
        "\"Judge the code, not the coder.\"",
    )

    val founderQuote = t("\"Building on open source is one of the most empowering experiences in software development. You do not have to wait for someone else to fix an issue or add a feature — you can build the solution yourself.\"")

    private fun fill(template: String, vararg args: Any): String = try {
        String.format(languageLocale(language), template, *args)
    } catch (_: java.util.IllegalFormatException) {
        // A translation that mangled a format specifier shows as it is.
        template
    }

    fun themeColorModeName(mode: ThemeColorMode): String = when (mode) {
        ThemeColorMode.Yume -> "Yume"
        ThemeColorMode.System -> themeSystem
    }

    fun themeColorModeDescription(mode: ThemeColorMode): String = when (mode) {
        ThemeColorMode.Yume -> themeYumeDescription
        ThemeColorMode.System -> themeSystemDescription
    }

    fun languageName(appLanguage: AppLanguage): String = when (appLanguage) {
        AppLanguage.SystemDefault -> systemDefaultLanguage
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

    fun preparingLanguage(languageName: String): String = fill(preparingLanguageTemplate, languageName)

    fun downloadingLanguage(languageName: String): String = fill(downloadingLanguageTemplate, languageName)

    fun translatingLanguage(languageName: String, done: Int, total: Int): String =
        fill(translatingLanguageTemplate, languageName, done, total)

    fun languagePreparationFailed(languageName: String): String =
        fill(languagePreparationFailedTemplate, languageName)

    fun deletedLanguageDownload(languageName: String): String = fill(deletedLanguageDownloadTemplate, languageName)

    fun failedToDeleteLanguageDownload(languageName: String): String =
        fill(failedToDeleteLanguageDownloadTemplate, languageName)

    fun nextAttempt(seconds: Long): String = fill(nextAttemptTemplate, seconds)

    fun kitAdded(name: String): String = fill(kitAddedTemplate, name)

    fun nameLookups(servers: String): String = fill(nameLookupsTemplate, servers)

    fun phaseName(phase: VpnPhase): String = when (phase) {
        VpnPhase.Idle -> phaseIdle
        VpnPhase.PermissionRequired -> phasePermission
        VpnPhase.Connecting -> phaseConnecting
        VpnPhase.Connected -> phaseConnected
        VpnPhase.Reconnecting -> phaseReconnecting
        VpnPhase.Disconnecting -> phaseDisconnecting
        VpnPhase.Error -> phaseError
    }

    fun failureTitle(failure: VpnFailure): String = when (failure) {
        VpnFailure.None -> phaseError
        VpnFailure.VpnPermission -> failureVpnPermissionTitle
        VpnFailure.KitUnavailable -> failureKitTitle
        VpnFailure.Resolve -> failureResolveTitle
        VpnFailure.Unreachable -> failureUnreachableTitle
        VpnFailure.Refused -> failureRefusedTitle
        VpnFailure.Configuration -> failureConfigurationTitle
        VpnFailure.Device -> failureDeviceTitle
    }

    fun failureDetail(failure: VpnFailure): String = when (failure) {
        VpnFailure.None -> ""
        VpnFailure.VpnPermission -> failureVpnPermissionDetail
        VpnFailure.KitUnavailable -> failureKitDetail
        VpnFailure.Resolve -> failureResolveDetail
        VpnFailure.Unreachable -> failureUnreachableDetail
        VpnFailure.Refused -> failureRefusedDetail
        VpnFailure.Configuration -> failureConfigurationDetail
        VpnFailure.Device -> failureDeviceDetail
    }

    fun upstreamName(kind: UpstreamKind): String = when (kind) {
        UpstreamKind.Wifi -> wifi
        UpstreamKind.Cellular -> cellular
        UpstreamKind.Ethernet -> ethernet
        UpstreamKind.Other -> otherNetwork
    }

    fun kitRefusal(reason: KitRefusal): String = when (reason) {
        KitRefusal.WrongCode -> kitRefusedWrongCode
        KitRefusal.BadCode -> kitRefusedBadCode
        KitRefusal.TooLarge -> kitRefusedTooLarge
        KitRefusal.NotAKit -> kitRefusedNotAKit
        KitRefusal.Circuits -> kitRefusedCircuits
        KitRefusal.Storage -> kitRefusedStorage
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
