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
    val displayName: String,
    val description: String,
) {
    Tun2SocksStable(
        wireValue = "tun2socks_stable",
        displayName = "Tun2Socks stable",
        description = "Stable Android path through tun2socks.",
    ),
    NativeDirectBeta(
        wireValue = "native_direct_beta",
        displayName = "Native direct beta",
        description = "Bridge-backed beta path with tighter tuning.",
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

enum class PerformanceProfile(
    val wireValue: String,
    val displayName: String,
    val description: String,
) {
    Latency(
        wireValue = "latency",
        displayName = "Latency",
        description = "Lower queueing for interactive traffic.",
    ),
    Balanced(
        wireValue = "balanced",
        displayName = "Balanced",
        description = "Default mixed-use profile.",
    ),
    Throughput(
        wireValue = "throughput",
        displayName = "Throughput",
        description = "Larger buffers for sustained transfers.",
    );

    companion object {
        fun fromWireValue(value: String): PerformanceProfile? = entries.firstOrNull { it.wireValue == value }
    }
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
            else -> ResolvedAppLanguage.AutoTranslated(normalizedTag)
        }
    }

    companion object {
        val maintainedEntries: List<Maintained> = listOf(
            Maintained.English,
            Maintained.Russian,
            Maintained.Japanese,
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

    data class AutoTranslated(
        override val localeTag: String,
    ) : ResolvedAppLanguage(localeTag)
}

data class AppSettings(
    val connectionTechnique: ConnectionTechnique = ConnectionTechnique.Tun2SocksStable,
    val performanceProfile: PerformanceProfile = PerformanceProfile.Balanced,
    val themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
    val amoledDarkTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.SystemDefault,
)

class AppSettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    private fun loadSettings(): AppSettings {
        val storedTechnique = prefs.getString(KEY_CONNECTION_TECHNIQUE, null)
            ?.let(ConnectionTechnique::fromWireValue)
            ?: ConnectionTechnique.Tun2SocksStable
        val storedProfile = prefs.getString(KEY_PERFORMANCE_PROFILE, null)
            ?.let(PerformanceProfile::fromWireValue)
            ?: PerformanceProfile.Balanced
        val storedThemeModeValue = prefs.getString(KEY_THEME_COLOR_MODE, null)
        val storedThemeColorMode = storedThemeModeValue
            ?.let(ThemeColorMode::fromWireValue)
            ?: ThemeColorMode.Yume
        val storedAmoledDarkTheme = prefs.getBoolean(
            KEY_AMOLED_DARK_THEME,
            storedThemeModeValue == LEGACY_THEME_MODE_AMOLED,
        )
        val storedLanguage = AppLanguage.fromWireValue(prefs.getString(KEY_LANGUAGE, null))
        return AppSettings(
            connectionTechnique = sanitizeTechnique(storedTechnique),
            performanceProfile = storedProfile,
            themeColorMode = storedThemeColorMode,
            amoledDarkTheme = storedAmoledDarkTheme,
            language = storedLanguage,
        )
    }

    private fun persist(settings: AppSettings) {
        prefs.edit {
            putString(KEY_CONNECTION_TECHNIQUE, settings.connectionTechnique.wireValue)
            putString(KEY_PERFORMANCE_PROFILE, settings.performanceProfile.wireValue)
            putString(KEY_THEME_COLOR_MODE, settings.themeColorMode.wireValue)
            putBoolean(KEY_AMOLED_DARK_THEME, settings.amoledDarkTheme)
            putString(KEY_LANGUAGE, settings.language.wireValue)
        }
        _settings.value = settings
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
        private const val LEGACY_THEME_MODE_AMOLED = "amoled"

        @Volatile
        private var instance: AppSettingsStore? = null

        fun readLanguage(context: Context): AppLanguage {
            return AppLanguage.fromWireValue(
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_LANGUAGE, null)
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
