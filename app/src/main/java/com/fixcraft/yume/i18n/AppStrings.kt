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
import com.fixcraft.yume.i18n.translations.LanguageZhHans
import com.fixcraft.yume.vpn.UpstreamKind
import com.fixcraft.yume.vpn.VpnFailure
import com.fixcraft.yume.vpn.VpnPhase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * One generated bundle per maintained or community non-English language. Add or edit messages
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
    ResolvedAppLanguage.ChineseSimplified to LanguageZhHans,
)

private fun languageLocale(language: ResolvedAppLanguage): Locale = when (language) {
    ResolvedAppLanguage.English -> Locale.ENGLISH
    ResolvedAppLanguage.Russian -> Locale.forLanguageTag("ru")
    ResolvedAppLanguage.Japanese -> Locale.JAPANESE
    ResolvedAppLanguage.Ukrainian -> Locale.forLanguageTag("uk")
    ResolvedAppLanguage.ChineseSimplified -> Locale.forLanguageTag("zh-Hans")
    is ResolvedAppLanguage.AutoTranslated -> Locale.forLanguageTag(language.localeTag)
    is ResolvedAppLanguage.Community -> Locale.forLanguageTag(language.localeTag)
}

class AppStrings private constructor(
    private val language: ResolvedAppLanguage,
    private val autoTranslate: ((String) -> String)? = null,
    private val seedCollector: MutableSet<String>? = null,
    communityBundles: Map<String, LanguageBundle> = CommunityLanguages.bundles,
) {
    // A community bundle holds only what it translates. The rest stays
    // English rather than going to on-device translation.
    private val translationMap: Map<String, String>? = maintainedBundles[language]?.translations
        ?: (language as? ResolvedAppLanguage.Community)?.let { communityBundles[it.localeTag]?.translations }

    fun message(message: MessageId): String = t(message)

    private fun t(message: MessageId): String {
        val english = message.source
        seedCollector?.add(english)
        if (language == ResolvedAppLanguage.English) return english
        return translationMap?.get(message.id) ?: autoTranslate?.invoke(english) ?: english
    }

    val back = t(MessageId.NAV_BACK)
    val close = t(MessageId.CLOSE)
    val cancel = t(MessageId.CANCEL)
    val save = t(MessageId.SAVE)
    val delete = t(MessageId.DELETE)

    // Navigation.
    val overviewTab = t(MessageId.OVERVIEW)
    val connectTab = t(MessageId.CONNECT)
    val logsTab = t(MessageId.LOGS)
    val postureTab = t(MessageId.NAV_POSTURE)
    val settingsTab = t(MessageId.SETTINGS)

    // Languages.
    val languageGroupTitle = t(MessageId.LANGUAGE)
    val maintainedLanguagesTitle = t(MessageId.MAINTAINED)
    val autoTranslatedLanguagesTitle = t(MessageId.AUTO_TRANSLATED)
    val communityLanguagesTitle = t(MessageId.COMMUNITY_TRANSLATIONS)
    val partlyEnglishLabel = t(MessageId.SOME_TEXT_APPEARS_IN_ENGLISH)
    val appLanguageLabel = t(MessageId.APP_LANGUAGE)
    val originalLabel = t(MessageId.ORIGINAL)
    val followsSystemLabel = t(MessageId.FOLLOWS_YOUR_PHONE)
    val onDeviceTranslationLabel = t(MessageId.ON_DEVICE_TRANSLATION)
    val autoShortLabel = t(MessageId.AUTO)
    val downloadedLabel = t(MessageId.DOWNLOADED)
    val removeDownloadedLanguageLabel = t(MessageId.REMOVE_DOWNLOAD)
    val preparingLanguageTitle = t(MessageId.PREPARING_LANGUAGE)
    val downloadingLanguageTitle = t(MessageId.DOWNLOADING_LANGUAGE_DATA)
    val translatingLanguageTitle = t(MessageId.TRANSLATING_THE_APP)
    val systemDefaultLanguage = t(MessageId.SYSTEM_DEFAULT)
    val preparingLanguageTemplate = t(MessageId.GETTING_READY)
    val downloadingLanguageTemplate = t(MessageId.DOWNLOADING_FOR_OFFLINE_USE)
    val translatingLanguageTemplate = t(MessageId.TRANSLATING_OF)
    val languagePreparationFailedTemplate = t(MessageId.COULDN_T_FINISH_YET_TRY_AGAIN_WITH_A_STABLE_CONNECTION)
    val deletedLanguageDownloadTemplate = t(MessageId.REMOVED_DOWNLOAD)
    val failedToDeleteLanguageDownloadTemplate = t(MessageId.COULDN_T_REMOVE_DOWNLOAD)

    // Overview.
    val connect = t(MessageId.CONNECT)
    val disconnect = t(MessageId.DISCONNECT)
    val noKitTitle = t(MessageId.NO_SERVER_YET)
    val noKitDetail = t(MessageId.ADD_THE_KIT_YOUR_SERVER_S_OWNER_GAVE_YOU_THEN_CONNECT)
    val addKit = t(MessageId.ADD_A_KIT)
    val phaseIdle = t(MessageId.READY)
    val phasePermission = t(MessageId.PERMISSION_NEEDED)
    val phaseConnecting = t(MessageId.CONNECTING)
    val phaseConnected = t(MessageId.PROTECTED)
    val phaseReconnecting = t(MessageId.RECONNECTING)
    val phaseDisconnecting = t(MessageId.DISCONNECTING)
    val phaseError = t(MessageId.NOT_CONNECTED)
    val idleDetail = t(MessageId.TAP_CONNECT)
    val permissionDetail = t(MessageId.ANDROID_NEEDS_YOUR_PERMISSION_TO_START_THE_VPN)
    val connectingDetail = t(MessageId.OPENING_THE_FIRST_SESSION_WITH_THE_SERVER)
    val waitingForNetworkTitle = t(MessageId.WAITING_FOR_NETWORK)
    val waitingForNetworkDetail = t(MessageId.WI_FI_OR_MOBILE_DATA_IS_UNAVAILABLE_YUME_CONNECTS_WHEN)
    val connectedDetail = t(MessageId.TRAFFIC_FROM_THIS_DEVICE_GOES_THROUGH_THE_SERVER)
    val reconnectingDetail = t(MessageId.THE_SESSION_WAS_LOST_TRAFFIC_WAITS_UNTIL_IT_RETURNS)
    val nextAttemptTemplate = t(MessageId.NEXT_ATTEMPT_IN_S)
    val disconnectingDetail = t(MessageId.CLOSING_THE_SESSION)
    val failureVpnPermissionTitle = t(MessageId.VPN_NOT_ALLOWED)
    val failureVpnPermissionDetail = t(MessageId.ANDROID_DID_NOT_LET_YUME_CREATE_THE_VPN_ANOTHER_VPN_MAY)
    val failureKitTitle = t(MessageId.KIT_UNAVAILABLE)
    val failureKitDetail = t(MessageId.THE_KIT_IS_NO_LONGER_STORED_OR_THE_APP_S_DATA_IS_LOCKED)
    val failureResolveTitle = t(MessageId.SERVER_NAME_NOT_FOUND)
    val failureResolveDetail = t(MessageId.THE_SERVER_S_NAME_DID_NOT_RESOLVE_ON_THIS_NETWORK)
    val failureUnreachableTitle = t(MessageId.SERVER_UNREACHABLE)
    val failureUnreachableDetail = t(MessageId.THE_SERVER_DID_NOT_ANSWER_CHECK_THE_NETWORK_OR_ASK_THE)
    val failureRefusedTitle = t(MessageId.SERVER_REFUSED_THIS_KIT)
    val failureRefusedDetail = t(MessageId.THE_SERVER_DID_NOT_ACCEPT_THIS_KIT_S_KEYS_ASK_ITS_OWNER)
    val failureConfigurationTitle = t(MessageId.KIT_NOT_SUPPORTED)
    val failureConfigurationDetail = t(MessageId.THIS_KIT_S_CONFIGURATION_IS_NOT_ONE_THIS_VERSION_OF_YUM)
    val failureDeviceTitle = t(MessageId.VPN_INTERFACE_FAILED)
    val failureDeviceDetail = t(MessageId.ANDROID_CLOSED_THE_VPN_INTERFACE)
    val connectedForLabel = t(MessageId.CONNECTED_FOR)
    val networkLabel = t(MessageId.NETWORK)
    val serverAddressLabel = t(MessageId.SERVER_ADDRESS)
    val openConnectionsLabel = t(MessageId.OPEN_CONNECTIONS)
    val wifi = "Wi-Fi"
    val cellular = t(MessageId.MOBILE_DATA)
    val ethernet = t(MessageId.ETHERNET)
    val otherNetwork = t(MessageId.OTHER)
    val trafficTitle = t(MessageId.TRAFFIC)
    val trafficHistoryTitle = t(MessageId.TRAFFIC_HISTORY)
    val trafficRatesDetail = t(MessageId.SMOOTHED_PAYLOAD_RATES_TOTALS_FOR_THIS_RUN)
    val trafficUpload = t(MessageId.UPLOAD)
    val trafficDownload = t(MessageId.DOWNLOAD)
    val trafficRateUnavailable = t(MessageId.RATE_UNAVAILABLE)
    val trafficLatestSample = t(MessageId.LATEST_SAMPLE)
    private val trafficSecondsTemplate = t(MessageId.TRAFFIC_SECONDS)
    private val trafficMinutesTemplate = t(MessageId.MIN)
    private val trafficWindowTemplate = t(MessageId.LAST)
    private val trafficTotalTemplate = t(MessageId.TOTAL)
    fun trafficWindow(seconds: Int): String = if (seconds < 60) fill(trafficSecondsTemplate, seconds)
        else fill(trafficMinutesTemplate, seconds / 60)
    fun trafficWindowDescription(label: String): String = fill(trafficWindowTemplate, label)
    fun trafficTotal(bytes: String): String = fill(trafficTotalTemplate, bytes)
    val trafficCollectingSamples = t(MessageId.COLLECTING_SAMPLES)
    val trafficConnectToStart = t(MessageId.CONNECT_TO_START_MEASURING_TRAFFIC)

    // Connect: kits.
    val kitsTitle = t(MessageId.KITS)
    val kitsSubtitle = t(MessageId.A_KIT_HOLDS_ONE_SERVER_S_ADDRESS_AND_YOUR_KEYS_FOR_IT_T)
    val noKitsYet = t(MessageId.NO_KITS_YET)
    val selected = t(MessageId.SELECTED)
    val live = t(MessageId.LIVE)
    val renameKit = t(MessageId.RENAME)
    val renameKitTitle = t(MessageId.RENAME_KIT)
    val kitNameLabel = t(MessageId.NAME)
    val kitNameHelper = t(MessageId.SHOWN_IN_THIS_APP_ONLY)
    val deleteKitTitle = t(MessageId.DELETE_THIS_KIT)
    val deleteKitDetail = t(MessageId.ITS_KEYS_ARE_REMOVED_FROM_THIS_DEVICE_YOU_NEED_THE_FILE)
    val addKitTitle = t(MessageId.ADD_A_KIT)
    val kitFileLabel = t(MessageId.KIT_FILE)
    val chooseFile = t(MessageId.CHOOSE_FILE)
    val noFileChosen = t(MessageId.NO_FILE_CHOSEN)
    val kitCodeLabel = t(MessageId.CODE)
    val kitCodeHelper = t(MessageId.MESSAGE_25_LETTERS_AND_DIGITS_SHOWN_WHEN_THE_KIT_WAS_SEALED)
    val importKit = t(MessageId.ADD)
    val openingKit = t(MessageId.OPENING_THE_KIT)
    val openingKitDetail = t(MessageId.THIS_TAKES_A_FEW_SECONDS)
    val kitAddedTemplate = t(MessageId.KIT_ADDED)
    val kitFileUnreadable = t(MessageId.COULDN_T_READ_THE_SELECTED_FILE)
    val kitRefusedWrongCode = t(MessageId.THIS_CODE_DOES_NOT_OPEN_THIS_FILE_CHECK_THE_CODE_AND_TH)
    val kitRefusedBadCode = t(MessageId.THE_CODE_IS_25_LETTERS_AND_DIGITS)
    val kitRefusedTooLarge = t(MessageId.THIS_FILE_IS_TOO_LARGE_TO_BE_A_KIT)
    val kitRefusedNotAKit = t(MessageId.THIS_FILE_DOES_NOT_HOLD_A_KIT_THIS_VERSION_OF_YUME_CAN)
    val kitRefusedCircuits = t(MessageId.THIS_KIT_ROUTES_THROUGH_SEVERAL_SERVERS_WHICH_THIS_APP)
    val kitRefusedStorage = t(MessageId.THE_KIT_COULD_NOT_BE_STORED_UNLOCK_THE_APP_S_DATA_AND_T)

    val kitIndexCorrupt = t(MessageId.KIT_INDEX_CORRUPT)
    val recoverKitIndex = t(MessageId.KIT_INDEX_RECOVER)
    val recoverKitIndexDetail = t(MessageId.KIT_INDEX_RECOVER_DETAIL)

    // Logs.
    val sessionLogTitle = t(MessageId.SESSION_LOG)
    val sessionLogSubtitle = t(MessageId.WHAT_THE_CONNECTION_DID_NEWEST_LAST_LINES_FROM_THE_YUME)
    val noSessionEventsYet = t(MessageId.NO_SESSION_EVENTS_YET)
    val copyReport = t(MessageId.COPY_REPORT)
    val shareReport = t(MessageId.SHARE_REPORT)
    val clearLog = t(MessageId.CLEAR)
    val reportCopied = t(MessageId.REPORT_COPIED)
    val shareReportSubject = t(MessageId.YUME_ANDROID_REPORT)

    // Posture.
    val postureConnectionTitle = t(MessageId.THE_CONNECTION)
    val postureConnectionDetail = t(MessageId.BOTH_SIDES_PROVE_THEIR_IDENTITY_WITH_ED25519_AND_ML_DSA)
    val protocolLabel = t(MessageId.PROTOCOL)
    val suiteLabel = t(MessageId.SUITE)
    val libraryLabel = t(MessageId.YUME_LIBRARY)
    val cryptoBackendLabel = t(MessageId.CRYPTOGRAPHY)
    val serverIdentityLabel = t(MessageId.SERVER_IDENTITY_SHA_256)
    val keyEpochLabel = t(MessageId.NEW_KEYS_EVERY)
    val notConnectedValue = t(MessageId.NOT_CONNECTED)
    val postureServerTitle = t(MessageId.WHAT_THE_SERVER_SEES)
    val postureServerDetail = t(MessageId.POSTURE_SERVER_DETAIL)
    val postureConnectionSummary = t(MessageId.POSTURE_CONNECTION_SUMMARY)
    val postureServerSummary = t(MessageId.POSTURE_SERVER_SUMMARY)
    val postureStopSummary = t(MessageId.POSTURE_STOP_SUMMARY)
    private val moreAboutTemplate = t(MessageId.INFO_MORE_ABOUT)
    val postureDeviceTitle = t(MessageId.ON_THIS_DEVICE)
    val routesLabel = t(MessageId.THROUGH_THE_TUNNEL)
    val routesAllIpv4 = t(MessageId.ALL_IPV4_TRAFFIC)
    val routesAllTraffic = t(MessageId.ALL_IPV4_AND_IPV6_TRAFFIC)
    val ipv6Label = "IPv6"
    val ipv6Blocked = t(MessageId.BLOCKED_WHILE_CONNECTED)
    val ipv6Carried = t(MessageId.THROUGH_THE_TUNNEL)
    val nameLookupsLabel = t(MessageId.NAME_LOOKUPS)
    val nameLookupsTemplate = t(MessageId.THROUGH_THE_TUNNEL_TO)
    val sessionLossLabel = t(MessageId.IF_THE_SESSION_DROPS)
    val sessionLossValue = t(MessageId.TRAFFIC_WAITS_AND_YUME_RECONNECTS)
    val appDataLabel = t(MessageId.KITS_ON_THIS_DEVICE)
    val appDataKeystore = t(MessageId.ENCRYPTED_KEY_IN_ANDROID_KEYSTORE)
    val appDataPassword = t(MessageId.ENCRYPTED_KEYSTORE_AND_YOUR_PASSWORD)
    val backupsLabel = t(MessageId.BACKUPS)
    val backupsValue = t(MessageId.KITS_ARE_NEVER_BACKED_UP)
    val postureStopTitle = t(MessageId.IF_THE_VPN_STOPS)
    val postureStopDetail = t(MessageId.WHEN_YUME_IS_DISCONNECTED_OR_STOPPED_ANDROID_SENDS_TRAF)
    val openVpnSettings = t(MessageId.ANDROID_VPN_SETTINGS)
    val postureLimitsTitle = t(MessageId.LIMITS)
    val postureLimitsDetail = t(MessageId.YUME_MAKES_ITS_CONNECTION_LOOK_LIKE_A_BROWSER_S_NO_TOOL)

    // Settings.
    val networkGroupTitle = t(MessageId.NETWORK)
    val dnsServersLabel = t(MessageId.DNS_SERVERS)
    val dnsServersHelper = t(MessageId.UP_TO_FOUR_IP_ADDRESSES_SEPARATED_BY_COMMAS_LOOKUPS_GO)
    val dnsServerFormatError = t(MessageId.ENTER_ONE_TO_FOUR_IP_ADDRESSES)
    val carryIpv6Title = t(MessageId.IPV6_THROUGH_THE_TUNNEL)
    val carryIpv6Description = t(MessageId.OFF_IPV6_IS_BLOCKED_WHILE_CONNECTED_TURN_ON_ONLY_IF_THE)
    val appliesOnNextConnect = t(MessageId.APPLIES_THE_NEXT_TIME_YOU_CONNECT)
    val themeGroupTitle = t(MessageId.THEME)
    val advancedGroupTitle = t(MessageId.ADVANCED)
    val appDataGroupTitle = t(MessageId.APP_DATA)
    val advancedModeTitle = t(MessageId.ADVANCED_MODE)
    val advancedModeDescription = t(MessageId.SHOW_NETWORK_SETTINGS)
    val tuningGroupTitle = t(MessageId.CONNECTION_TUNING)
    val tuningSubtitle = t(MessageId.CHOOSE_THE_SHARED_YUME_POSTURE_FOR_THE_SELECTED_KIT_CHA)
    val tuningNoKit = t(MessageId.SELECT_A_KIT_TO_TUNE_ITS_CONNECTION)
    val tuningStealth = t(MessageId.STEALTH)
    val tuningSpeed = t(MessageId.SPEED)
    val tuningSecurity = t(MessageId.SECURITY)
    val tuningStealthNote = t(MessageId.CHANGES_WINDOWS_CREDIT_RETURNS_AND_IDLE_ROTATION_IT_DOE)
    val tuningSpeedNote = t(MessageId.MEASURED_POSITIONS_UNDER_THE_STATED_HOST_AND_NETWORK_CO)
    val tuningSecurityNote = t(MessageId.DATA_PER_KEY_EPOCH_ALGORITHMS_AND_MANDATORY_ROTATION_ST)
    val tuningLevelTemplate = t(MessageId.LEVEL_D_OF_D)
    val tuningApply = t(MessageId.APPLY_TUNING)
    val tuningSaved = t(MessageId.TUNING_SAVED_IT_WILL_BE_USED_ON_THE_NEXT_CONNECTION)
    val tuningSaveFailed = t(MessageId.YUME_REFUSED_THESE_LIMITS_NOTHING_WAS_CHANGED)
    val tuningCustom = t(MessageId.CUSTOM_LIMITS)
    val tuningExpert = t(MessageId.EXPERT_EDITING)
    val tuningExpertWarning = t(MessageId.EXPERT_VALUES_CHANGE_THE_CONNECTION_S_RESOURCE_LIMITS_I)
    val tuningExpertConfirm = t(MessageId.I_KNOW_WHAT_I_M_DOING)
    val tuningExpertUnlock = t(MessageId.UNLOCK_EXPERT_EDITING)
    val tuningExpertLocked = t(MessageId.EXPERT_EDITING_IS_LOCKED_UNTIL_YOU_ACKNOWLEDGE_THE_WARN)
    val tuningExpertOneAtTime = t(MessageId.APPLY_ONE_FIELD_AT_A_TIME_THE_NATIVE_CORE_CHECKS_EACH_V)
    val tuningFieldEpoch = t(MessageId.DATA_PER_KEY_EPOCH)
    val tuningFieldFlow = t(MessageId.FLOW_BUDGET)
    val tuningFieldCredits = t(MessageId.CREDIT_RETURNS_PER_WINDOW)
    val tuningFieldIdle = t(MessageId.ROTATE_KEYS_WHILE_IDLE)
    val tuningOn = t(MessageId.ON)
    val tuningOff = t(MessageId.OFF)
    val tuningMeasuredTitle = t(MessageId.MEASURED_SPEED_CONDITIONS)
    val tuningEpochConditions = t(MessageId.ONE_HOST_ONE_SESSION_ONE_DOWNLOAD_LOSS_FREE_EMULATED_RT)
    val tuningWindowConditions = t(MessageId.ONE_HOST_ONE_SESSION_ONE_DOWNLOAD_LOSS_FREE_EMULATED_RT_2)
    val tuningMeasurementLimit = t(MessageId.EMULATED_PATHS_ONLY_NOT_A_REAL_PATH_SPEED_OR_STEALTH_CL)
    val tuningWindowLimit = t(MessageId.A_SEPARATE_RUN_FROM_EPOCH_COMPARISON_HOST_CEILINGS_AND)
    val amoledTitle = t(MessageId.AMOLED_BLACK)
    val amoledDescription = t(MessageId.USE_TRUE_BLACK_IN_DARK_MODE)
    val themeSystem = t(MessageId.SYSTEM_THEME)
    val themeYumeDescription = t(MessageId.THEME_YUME_PLUM_COLORS)
    val themeSystemDescription = t(MessageId.MATERIAL_YOU_ON_ANDROID_12)
    val appDataSubtitle = t(MessageId.USE_ANDROID_PROTECTION_BY_DEFAULT_OR_ADD_A_PASSWORD_BEF)
    val appDataProtectionLabel = t(MessageId.PROTECTION)
    val androidProtected = t(MessageId.ANDROID_SECURE)
    val passwordProtected = t(MessageId.PASSWORD_REQUIRED)
    val setPasswordButton = t(MessageId.SET_PASSWORD)
    val changePasswordButton = t(MessageId.CHANGE_PASSWORD)
    val removePasswordButton = t(MessageId.REMOVE_PASSWORD)
    val unlockAppDataTitle = t(MessageId.UNLOCK_APP_DATA)
    val unlockAppDataDetail = t(MessageId.ENTER_THE_PASSWORD_TO_OPEN_YUME_AND_LOAD_ENCRYPTED_DATA)
    val passwordLabel = t(MessageId.PASSWORD)
    val currentPasswordLabel = t(MessageId.CURRENT_PASSWORD)
    val newPasswordLabel = t(MessageId.NEW_PASSWORD)
    val confirmPasswordLabel = t(MessageId.CONFIRM_PASSWORD)
    val unlockButton = t(MessageId.UNLOCK)
    val enableAppDataPasswordDetail = t(MessageId.YUME_WILL_ASK_FOR_THIS_PASSWORD_BEFORE_THE_APP_OPENS_ST)
    val changeAppDataPasswordDetail = t(MessageId.ENTER_THE_CURRENT_PASSWORD_THEN_SET_A_NEW_ONE_FOR_ENCRY)
    val disableAppDataPasswordDetail = t(MessageId.ENTER_THE_CURRENT_PASSWORD_TO_RETURN_APP_DATA_PROTECTIO)
    val passwordRequired = t(MessageId.ENTER_THE_PASSWORD_TO_CONTINUE)
    val currentPasswordRequired = t(MessageId.CURRENT_PASSWORD_REQUIRED)
    val newPasswordRequired = t(MessageId.NEW_PASSWORD_REQUIRED)
    val passwordTooShort = t(MessageId.PASSWORD_MINIMUM_UTF8_BYTES)
    val languageDownloadsUnavailable = t(MessageId.LANGUAGE_DOWNLOADS_UNAVAILABLE)
    val failedToUnlockAppData = t(MessageId.APP_DATA_UNLOCK_FAILED)
    val tuningResetPreset = t(MessageId.TUNING_RESET_PRESET)
    val passwordsDoNotMatch = t(MessageId.PASSWORDS_DO_NOT_MATCH)
    val appDataPasswordEnabled = t(MessageId.APP_DATA_PASSWORD_ENABLED)
    val appDataPasswordChanged = t(MessageId.APP_DATA_PASSWORD_CHANGED)
    val appDataPasswordDisabled = t(MessageId.APP_DATA_PASSWORD_REMOVED)
    val failedToEnableAppDataPassword = t(MessageId.COULDN_T_ENABLE_THE_APP_DATA_PASSWORD)
    val failedToChangeAppDataPassword = t(MessageId.COULDN_T_CHANGE_THE_APP_DATA_PASSWORD)
    val failedToDisableAppDataPassword = t(MessageId.COULDN_T_REMOVE_THE_APP_DATA_PASSWORD)
    val deviceGroupTitle = t(MessageId.DEVICE)
    val aboutGroupTitle = t(MessageId.ABOUT)
    val systemGroupTitle = t(MessageId.SYSTEM)
    val creditsTitle = t(MessageId.CREDITS)
    val authorLabel = t(MessageId.AUTHOR)
    val companyLabel = t(MessageId.COMPANY)
    val appLabel = t(MessageId.APP)
    val yumeLabel = "Yume"
    val basefwxLabel = "BaseFWX"
    val batterySettingsButton = t(MessageId.BATTERY_SETTINGS)
    val allowBackground = t(MessageId.ALLOW_BACKGROUND)
    val notificationSettingsButton = t(MessageId.NOTIFICATION_SETTINGS)
    val notificationsLabel = t(MessageId.NOTIFICATIONS)
    val batteryOptimizationLabel = t(MessageId.BATTERY_OPTIMIZATION)
    val dynamicColorsLabel = t(MessageId.DYNAMIC_COLORS)
    val allowed = t(MessageId.ALLOWED)
    val permissionRequired = t(MessageId.PERMISSION_REQUIRED)
    val optimized = t(MessageId.OPTIMIZED)
    val unrestricted = t(MessageId.UNRESTRICTED)
    val available = t(MessageId.AVAILABLE)
    val unavailableOnAndroidVersion = t(MessageId.UNAVAILABLE_ON_THIS_ANDROID_VERSION)

    // Notification.
    val notificationConnected = t(MessageId.PROTECTED)
    val notificationConnecting = t(MessageId.CONNECTING)
    val notificationReconnecting = t(MessageId.RECONNECTING)
    val notificationWaitingForNetwork = t(MessageId.WAITING_FOR_NETWORK)
    val notificationDisconnecting = t(MessageId.DISCONNECTING)

    // Credits and components.
    val developerQuoteLabel = t(MessageId.RECOGNITION)
    val aiHelperRole = t(MessageId.AI_HELPERS)
    val founderNoteLabel = t(MessageId.FROM_F1XGOD)
    val founderCeoRole = t(MessageId.FOUNDER_CEO_FIXCRAFT_INC)
    val leadDeveloperDesignerRole = t(MessageId.AUTHOR_LEAD_DEVELOPER_AND_DESIGNER_OF_YUME_AND_BASEFWX)
    val openSourceComponentsTitle = t(MessageId.OPEN_SOURCE_COMPONENTS)
    val openSourceComponentsLink = t(MessageId.VIEW_OPEN_SOURCE_COMPONENTS)
    val openSourceComponentsSubtitle = t(MessageId.THIRD_PARTY_CODE_YUME_BUILDS_ON_WITH_LICENCES)
    val componentGroupLibrary = t(MessageId.IN_THE_YUME_LIBRARY)
    val componentGroupLibraryBlurb = t(MessageId.COMPILED_INTO_LIBYUME_WHICH_CARRIES_THE_CONNECTION_ON_E)
    val componentGroupAndroidOnly = t(MessageId.USED_ONLY_IN_THE_ANDROID_IMPLEMENTATION_OF_YUME)
    val componentGroupAndroidOnlyBlurb = t(MessageId.COMPILED_INTO_THIS_APP_THE_DESKTOP_CLIENT_USES_DIFFEREN)
    val componentOpensslUsage = t(MessageId.TLS_1_3_AND_EVERY_CRYPTOGRAPHIC_PRIMITIVE_OF_THE_CONNEC)
    val componentBoostUsage = t(MessageId.ASYNCHRONOUS_NETWORKING_AND_TIMERS)
    val componentNghttp2Usage = t(MessageId.HTTP_2_FRAMING_OF_THE_CARRIER)
    val componentCompressionUsage = t(MessageId.CERTIFICATE_COMPRESSION_IN_THE_TLS_HANDSHAKE)
    val componentJsonUsage = t(MessageId.READING_THE_CONFIGURATION)
    val componentBasefwxUsage = t(MessageId.WRAPS_THIS_APP_S_STORED_DATA_UNDER_THE_OPTIONAL_APP_DAT)
    val componentBouncyCastleUsage = t(MessageId.PRIMITIVES_BASEFWX_S_JAVA_RUNTIME_USES)
    val componentComposeUsage = t(MessageId.DECLARATIVE_UI_TOOLKIT_THAT_RENDERS_EVERY_SCREEN_IN_THI)
    val componentCoroutinesUsage = t(MessageId.STRUCTURED_CONCURRENCY_IN_THE_VPN_SERVICE)
    val componentMlKitUsage = t(MessageId.ON_DEVICE_TRANSLATION_FOR_LANGUAGES_WITHOUT_A_MAINTAINE)

    val developerCreditQuotes = listOf(
        "\"Credit is only fair when everyone has their place, and AI deserves to be recognized too.\"",
        "\"Judge the code, not the coder.\"",
    )

    val founderQuote = t(MessageId.BUILDING_ON_OPEN_SOURCE_IS_ONE_OF_THE_MOST_EMPOWERING_E)

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
        AppLanguage.Maintained.ChineseSimplified -> "简体中文"
        is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(appLanguage)
        is AppLanguage.Community -> AppLanguageCatalog.displayName(appLanguage)
    }

    /** Who translated a language, shown as their names under its group heading. */
    fun creditNames(credits: List<TranslationCredit>): String = credits.joinToString(", ") { it.name }

    private fun maintainedCredit(language: AppLanguage.Maintained): String? =
        MaintainedLanguageCredits.credits[language.localeTag]?.let(::creditNames)

    fun languageDescription(appLanguage: AppLanguage): String? = when (appLanguage) {
        AppLanguage.SystemDefault -> followsSystemLabel
        AppLanguage.Maintained.English -> originalLabel
        AppLanguage.Maintained.Russian -> maintainedCredit(AppLanguage.Maintained.Russian)
        AppLanguage.Maintained.Japanese -> maintainedCredit(AppLanguage.Maintained.Japanese)
        AppLanguage.Maintained.Ukrainian -> maintainedCredit(AppLanguage.Maintained.Ukrainian)
        AppLanguage.Maintained.ChineseSimplified -> maintainedCredit(AppLanguage.Maintained.ChineseSimplified)
        is AppLanguage.AutoTranslated -> onDeviceTranslationLabel
        is AppLanguage.Community -> communityLanguage(appLanguage.localeTag)?.let { community ->
            val credit = creditNames(community.credits)
            if (community.complete) credit else "$credit\n$partlyEnglishLabel"
        }
    }

    fun languageSummary(appLanguage: AppLanguage): String {
        // The credit lines belong in the picker; the summary stays short.
        val description = when (appLanguage) {
            is AppLanguage.AutoTranslated -> autoShortLabel
            is AppLanguage.Community -> null
            AppLanguage.Maintained.English -> originalLabel
            is AppLanguage.Maintained -> null
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
    fun moreAbout(title: String): String = fill(moreAboutTemplate, title)

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

        /** For tests: strings drawn from the given community bundles. */
        internal fun forCommunity(
            language: ResolvedAppLanguage.Community,
            communityBundles: Map<String, LanguageBundle>,
        ): AppStrings = AppStrings(language, communityBundles = communityBundles)

        fun translationSeeds(): List<String> = translationSeeds
    }
}

fun appStrings(
    language: AppLanguage,
    systemLocale: Locale = Locale.getDefault(),
    autoTranslate: ((String) -> String)? = null,
): AppStrings = AppStrings.forLanguage(language.resolve(systemLocale, communityLanguageTags), autoTranslate)

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
