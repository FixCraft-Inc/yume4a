package com.fixcraft.yume.data

import android.content.Context
import androidx.core.content.edit
import com.fixcraft.yume.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ConnectionTechnique(
    val wireValue: String,
) {
    Tun2SocksStable(
        wireValue = "tun2socks_stable",
    ),
    NativeDirectBeta(
        wireValue = "native_direct_beta",
    ),
    Socks5ProxyOnly(
        wireValue = "socks5_proxy_only",
    );

    companion object {
        fun fromWireValue(value: String): ConnectionTechnique? {
            return when (value) {
                "native_direct_alpha" -> NativeDirectBeta
                else -> entries.firstOrNull { it.wireValue == value }
            }
        }
    }
}

fun ConnectionTechnique.canonicalLabel(): String = when (this) {
    ConnectionTechnique.Tun2SocksStable -> "Tun2Socks stable"
    ConnectionTechnique.NativeDirectBeta -> "Native direct beta"
    ConnectionTechnique.Socks5ProxyOnly -> "SOCKS5 proxy only"
}

fun ConnectionTechnique.supportsUserSelectablePerformanceProfiles(): Boolean {
    return this == ConnectionTechnique.Tun2SocksStable
}

fun ConnectionTechnique.isVpnService(): Boolean = when (this) {
    ConnectionTechnique.Tun2SocksStable,
    ConnectionTechnique.NativeDirectBeta -> true
    ConnectionTechnique.Socks5ProxyOnly -> false
}

enum class Socks5BindMode(
    val wireValue: String,
) {
    Loopback(wireValue = "loopback"),
    Wifi(wireValue = "wifi"),
    Wildcard(wireValue = "wildcard");

    companion object {
        fun fromWireValue(value: String?): Socks5BindMode? {
            if (value == null) return null
            return entries.firstOrNull { it.wireValue == value }
        }
    }
}

fun Socks5BindMode.canonicalLabel(): String = when (this) {
    Socks5BindMode.Loopback -> "Loopback (127.0.0.1)"
    Socks5BindMode.Wifi -> "Wi-Fi interface"
    Socks5BindMode.Wildcard -> "All interfaces (0.0.0.0)"
}

data class Socks5ProxySettings(
    val port: Int = DEFAULT_SOCKS5_PORT,
    val bindMode: Socks5BindMode = Socks5BindMode.Loopback,
    val tunnelCount: Int = DEFAULT_TUNNEL_COUNT,
) {
    val normalizedPort: Int
        get() = port.coerceIn(MIN_SOCKS5_PORT, MAX_SOCKS5_PORT)

    val normalizedTunnelCount: Int
        get() = tunnelCount.coerceIn(MIN_TUNNEL_COUNT, MAX_TUNNEL_COUNT)

    companion object {
        const val DEFAULT_SOCKS5_PORT = 1088
        const val MIN_SOCKS5_PORT = 1024
        const val MAX_SOCKS5_PORT = 65535
        const val DEFAULT_TUNNEL_COUNT = 4
        const val MIN_TUNNEL_COUNT = 1
        // Per-session memory/CPU isn't free — every additional tunnel
        // means another TLS state, another auth handshake, another
        // keepalive ticker, and another hop-key timer. 8 is the
        // pragmatic ceiling for a mobile device; beyond that we hit
        // the server's accept rate limit and the marginal gain on the
        // client falls off as the TLS-encrypt path saturates the CPU.
        const val MAX_TUNNEL_COUNT = 8
    }
}

enum class PerformanceProfile(
    val wireValue: String,
) {
    Latency(
        wireValue = "latency",
    ),
    Balanced(
        wireValue = "balanced",
    ),
    Throughput(
        wireValue = "throughput",
    );

    companion object {
        fun fromWireValue(value: String): PerformanceProfile? = entries.firstOrNull { it.wireValue == value }
    }
}

fun PerformanceProfile.canonicalLabel(): String = when (this) {
    PerformanceProfile.Latency -> "Latency"
    PerformanceProfile.Balanced -> "Balanced"
    PerformanceProfile.Throughput -> "Throughput"
}

enum class ThemeColorMode(
    val wireValue: String,
) {
    Yume(
        wireValue = "yume",
    ),
    System(
        wireValue = "system",
    );

    companion object {
        fun fromWireValue(value: String): ThemeColorMode? = entries.firstOrNull { it.wireValue == value }
    }
}

sealed class AppLanguage(
    val wireValue: String,
) {
    open val localeTag: String? = null

    data object SystemDefault : AppLanguage("system")

    sealed class Maintained(
        wireValue: String,
        final override val localeTag: String,
    ) : AppLanguage(wireValue) {
        data object English : Maintained("en", "en")

        data object Russian : Maintained("ru", "ru")

        data object Japanese : Maintained("ja", "ja")

        data object Ukrainian : Maintained("uk", "uk")
    }

    data class AutoTranslated(
        private val targetLocaleTag: String,
    ) : AppLanguage("auto:${normalizeLanguageTag(targetLocaleTag)}") {
        override val localeTag: String = normalizeLanguageTag(targetLocaleTag)
    }

    val isMaintained: Boolean
        get() = this is Maintained

    val isAutoTranslated: Boolean
        get() = this is AutoTranslated

    fun resolve(systemLocale: Locale = Locale.getDefault()): ResolvedAppLanguage {
        val requestedTag = when (this) {
            SystemDefault -> systemLocale.toLanguageTag()
            is Maintained -> localeTag
            is AutoTranslated -> localeTag
        }
        val normalizedTag = normalizeLanguageTag(requestedTag)
        return when (Locale.forLanguageTag(normalizedTag).language.lowercase(Locale.ROOT)) {
            Maintained.English.localeTag -> ResolvedAppLanguage.English
            Maintained.Russian.localeTag -> ResolvedAppLanguage.Russian
            Maintained.Japanese.localeTag -> ResolvedAppLanguage.Japanese
            Maintained.Ukrainian.localeTag -> ResolvedAppLanguage.Ukrainian
            else -> ResolvedAppLanguage.AutoTranslated(normalizedTag)
        }
    }

    companion object {
        val maintainedEntries: List<Maintained> = listOf(
            Maintained.English,
            Maintained.Russian,
            Maintained.Japanese,
            Maintained.Ukrainian,
        )

        fun fromWireValue(value: String?): AppLanguage {
            val normalizedValue = value.orEmpty().trim()
            if (normalizedValue.isEmpty() || normalizedValue == SystemDefault.wireValue) {
                return SystemDefault
            }
            maintainedEntries.firstOrNull { it.wireValue == normalizedValue }?.let { return it }
            if (normalizedValue.startsWith("auto:", ignoreCase = true)) {
                val localeTag = normalizedValue.substringAfter(':').trim()
                if (localeTag.isNotEmpty()) {
                    return AutoTranslated(localeTag)
                }
            }
            return AutoTranslated(normalizedValue)
        }

        fun fromLocaleTag(localeTag: String): AppLanguage {
            val normalizedTag = normalizeLanguageTag(localeTag)
            val languageCode = Locale.forLanguageTag(normalizedTag).language.lowercase(Locale.ROOT)
            return maintainedEntries.firstOrNull { it.localeTag == languageCode } ?: AutoTranslated(normalizedTag)
        }

        private fun normalizeLanguageTag(localeTag: String): String {
            return Locale.forLanguageTag(localeTag.trim()).toLanguageTag().ifBlank { "en" }
        }
    }
}

sealed class ResolvedAppLanguage(
    open val localeTag: String,
) {
    data object English : ResolvedAppLanguage("en")

    data object Russian : ResolvedAppLanguage("ru")

    data object Japanese : ResolvedAppLanguage("ja")

    data object Ukrainian : ResolvedAppLanguage("uk")

    data class AutoTranslated(
        override val localeTag: String,
    ) : ResolvedAppLanguage(localeTag)
}

enum class LogLanguage(
    val wireValue: String,
) {
    FollowApp("app"),
    English("en"),
    Russian("ru"),
    Japanese("ja"),
    Ukrainian("uk");

    fun resolve(appLanguage: AppLanguage, systemLocale: Locale = Locale.getDefault()): AppLanguage.Maintained {
        return when (this) {
            FollowApp -> {
                when (appLanguage.resolve(systemLocale)) {
                    ResolvedAppLanguage.English -> AppLanguage.Maintained.English
                    ResolvedAppLanguage.Russian -> AppLanguage.Maintained.Russian
                    ResolvedAppLanguage.Japanese -> AppLanguage.Maintained.Japanese
                    ResolvedAppLanguage.Ukrainian -> AppLanguage.Maintained.Ukrainian
                    is ResolvedAppLanguage.AutoTranslated -> AppLanguage.Maintained.English
                }
            }

            English -> AppLanguage.Maintained.English
            Russian -> AppLanguage.Maintained.Russian
            Japanese -> AppLanguage.Maintained.Japanese
            Ukrainian -> AppLanguage.Maintained.Ukrainian
        }
    }

    companion object {
        fun fromWireValue(value: String?): LogLanguage {
            return entries.firstOrNull { it.wireValue == value } ?: FollowApp
        }
    }
}

data class AppSettings(
    val connectionTechnique: ConnectionTechnique = ConnectionTechnique.Tun2SocksStable,
    val performanceProfile: PerformanceProfile = PerformanceProfile.Balanced,
    val themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
    val amoledDarkTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.SystemDefault,
    val logLanguage: LogLanguage = LogLanguage.FollowApp,
    val advancedMode: Boolean = false,
    val socks5ProxySettings: Socks5ProxySettings = Socks5ProxySettings(),
) {
    fun effectiveLogLanguage(): LogLanguage =
        if (advancedMode) logLanguage else LogLanguage.FollowApp
}

class AppSettingsStore private constructor(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val legacyPrefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val vaultStore = VaultJsonMapStore(appContext, VAULT_NAME)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun updateConnectionTechnique(technique: ConnectionTechnique) {
        val persistedTechnique = sanitizeTechnique(technique)
        persist(_settings.value.copy(connectionTechnique = persistedTechnique))
    }

    fun updatePerformanceProfile(profile: PerformanceProfile) {
        persist(_settings.value.copy(performanceProfile = profile))
    }

    fun updateThemeColorMode(mode: ThemeColorMode) {
        persist(_settings.value.copy(themeColorMode = mode))
    }

    fun updateAmoledDarkTheme(enabled: Boolean) {
        persist(_settings.value.copy(amoledDarkTheme = enabled))
    }

    fun updateLanguage(language: AppLanguage) {
        persist(_settings.value.copy(language = language))
    }

    fun updateLogLanguage(language: LogLanguage) {
        persist(_settings.value.copy(logLanguage = language))
    }

    fun updateAdvancedMode(enabled: Boolean) {
        persist(_settings.value.copy(advancedMode = enabled))
    }

    fun updateSocks5ProxySettings(settings: Socks5ProxySettings) {
        val sanitized = settings.copy(port = settings.normalizedPort)
        persist(_settings.value.copy(socks5ProxySettings = sanitized))
    }

    private fun loadSettings(): AppSettings {
        migrateLegacyPrefsIfNeeded()
        val storedTechnique = vaultStore.getString(KEY_CONNECTION_TECHNIQUE)
            ?.let(ConnectionTechnique::fromWireValue)
            ?: ConnectionTechnique.Tun2SocksStable
        val storedProfile = vaultStore.getString(KEY_PERFORMANCE_PROFILE)
            ?.let(PerformanceProfile::fromWireValue)
            ?: PerformanceProfile.Balanced
        val storedThemeModeValue = vaultStore.getString(KEY_THEME_COLOR_MODE)
        val storedThemeColorMode = storedThemeModeValue
            ?.let(ThemeColorMode::fromWireValue)
            ?: ThemeColorMode.Yume
        val storedAmoledDarkTheme = vaultStore.getString(KEY_AMOLED_DARK_THEME)?.toBooleanStrictOrNull()
            ?: (storedThemeModeValue == LEGACY_THEME_MODE_AMOLED)
        val storedLanguage = AppLanguage.fromWireValue(vaultStore.getString(KEY_LANGUAGE))
        val storedLogLanguage = LogLanguage.fromWireValue(vaultStore.getString(KEY_LOG_LANGUAGE))
        val storedAdvancedMode = vaultStore.getString(KEY_ADVANCED_MODE)?.toBooleanStrictOrNull() ?: false
        val storedSocks5Port = vaultStore.getString(KEY_SOCKS5_PORT)?.toIntOrNull()
            ?: Socks5ProxySettings.DEFAULT_SOCKS5_PORT
        val storedSocks5BindMode = Socks5BindMode.fromWireValue(vaultStore.getString(KEY_SOCKS5_BIND_MODE))
            ?: Socks5BindMode.Loopback
        val storedSocks5TunnelCount = vaultStore.getString(KEY_SOCKS5_TUNNEL_COUNT)?.toIntOrNull()
            ?: Socks5ProxySettings.DEFAULT_TUNNEL_COUNT
        return AppSettings(
            connectionTechnique = sanitizeTechnique(storedTechnique),
            performanceProfile = storedProfile,
            themeColorMode = storedThemeColorMode,
            amoledDarkTheme = storedAmoledDarkTheme,
            language = storedLanguage,
            logLanguage = storedLogLanguage,
            advancedMode = storedAdvancedMode,
            socks5ProxySettings = Socks5ProxySettings(
                port = storedSocks5Port,
                bindMode = storedSocks5BindMode,
                tunnelCount = storedSocks5TunnelCount,
            ),
        )
    }

    private fun persist(settings: AppSettings) {
        vaultStore.putString(KEY_CONNECTION_TECHNIQUE, settings.connectionTechnique.wireValue)
        vaultStore.putString(KEY_PERFORMANCE_PROFILE, settings.performanceProfile.wireValue)
        vaultStore.putString(KEY_THEME_COLOR_MODE, settings.themeColorMode.wireValue)
        vaultStore.putString(KEY_AMOLED_DARK_THEME, settings.amoledDarkTheme.toString())
        vaultStore.putString(KEY_LANGUAGE, settings.language.wireValue)
        vaultStore.putString(KEY_LOG_LANGUAGE, settings.logLanguage.wireValue)
        vaultStore.putString(KEY_ADVANCED_MODE, settings.advancedMode.toString())
        vaultStore.putString(KEY_SOCKS5_PORT, settings.socks5ProxySettings.normalizedPort.toString())
        vaultStore.putString(KEY_SOCKS5_BIND_MODE, settings.socks5ProxySettings.bindMode.wireValue)
        vaultStore.putString(KEY_SOCKS5_TUNNEL_COUNT, settings.socks5ProxySettings.normalizedTunnelCount.toString())
        _settings.value = settings
    }

    private fun migrateLegacyPrefsIfNeeded() {
        if (!vaultStore.isEmpty()) {
            return
        }
        if (!legacyPrefs.contains(KEY_CONNECTION_TECHNIQUE) &&
            !legacyPrefs.contains(KEY_PERFORMANCE_PROFILE) &&
            !legacyPrefs.contains(KEY_THEME_COLOR_MODE) &&
            !legacyPrefs.contains(KEY_AMOLED_DARK_THEME) &&
            !legacyPrefs.contains(KEY_LANGUAGE) &&
            !legacyPrefs.contains(KEY_LOG_LANGUAGE) &&
            !legacyPrefs.contains(KEY_ADVANCED_MODE)
        ) {
            return
        }
        legacyPrefs.getString(KEY_CONNECTION_TECHNIQUE, null)?.let { vaultStore.putString(KEY_CONNECTION_TECHNIQUE, it) }
        legacyPrefs.getString(KEY_PERFORMANCE_PROFILE, null)?.let { vaultStore.putString(KEY_PERFORMANCE_PROFILE, it) }
        legacyPrefs.getString(KEY_THEME_COLOR_MODE, null)?.let { vaultStore.putString(KEY_THEME_COLOR_MODE, it) }
        if (legacyPrefs.contains(KEY_AMOLED_DARK_THEME)) {
            vaultStore.putString(KEY_AMOLED_DARK_THEME, legacyPrefs.getBoolean(KEY_AMOLED_DARK_THEME, false).toString())
        }
        legacyPrefs.getString(KEY_LANGUAGE, null)?.let { vaultStore.putString(KEY_LANGUAGE, it) }
        legacyPrefs.getString(KEY_LOG_LANGUAGE, null)?.let { vaultStore.putString(KEY_LOG_LANGUAGE, it) }
        if (legacyPrefs.contains(KEY_ADVANCED_MODE)) {
            vaultStore.putString(KEY_ADVANCED_MODE, legacyPrefs.getBoolean(KEY_ADVANCED_MODE, false).toString())
        }
        legacyPrefs.edit { clear() }
    }

    private fun sanitizeTechnique(technique: ConnectionTechnique): ConnectionTechnique {
        return if (technique == ConnectionTechnique.NativeDirectBeta && !BuildConfig.ENABLE_NATIVE_DIRECT_BETA) {
            ConnectionTechnique.Tun2SocksStable
        } else {
            technique
        }
    }

    companion object {
        private const val PREFS_NAME = "yume_app_settings"
        private const val KEY_CONNECTION_TECHNIQUE = "connection_technique"
        private const val KEY_PERFORMANCE_PROFILE = "performance_profile"
        private const val KEY_THEME_COLOR_MODE = "theme_color_mode"
        private const val KEY_AMOLED_DARK_THEME = "amoled_dark_theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LOG_LANGUAGE = "log_language"
        private const val KEY_ADVANCED_MODE = "advanced_mode"
        private const val KEY_SOCKS5_PORT = "socks5_port"
        private const val KEY_SOCKS5_BIND_MODE = "socks5_bind_mode"
        private const val KEY_SOCKS5_TUNNEL_COUNT = "socks5_tunnel_count"
        private const val LEGACY_THEME_MODE_AMOLED = "amoled"
        private const val VAULT_NAME = "app_settings"

        @Volatile
        private var instance: AppSettingsStore? = null

        fun readLanguage(context: Context): AppLanguage {
            val appContext = context.applicationContext ?: context
            val vaultStore = VaultJsonMapStore(appContext, VAULT_NAME)
            val encrypted = runCatching { vaultStore.getString(KEY_LANGUAGE) }.getOrNull()
            if (!encrypted.isNullOrBlank()) {
                return AppLanguage.fromWireValue(encrypted)
            }
            return AppLanguage.fromWireValue(
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)
            )
        }

        fun get(context: Context): AppSettingsStore {
            val storeContext = context.applicationContext ?: context
            return instance ?: synchronized(this) {
                instance ?: AppSettingsStore(storeContext).also { instance = it }
            }
        }
    }
}
