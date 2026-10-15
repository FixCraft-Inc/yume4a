package com.fixcraft.yume.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

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

        data object ChineseSimplified : Maintained("zh-Hans", "zh-Hans")
    }

    data class AutoTranslated(
        private val targetLocaleTag: String,
    ) : AppLanguage("auto:${normalizeLanguageTag(targetLocaleTag)}") {
        override val localeTag: String = normalizeLanguageTag(targetLocaleTag)
    }

    /** A human community translation from YUME's shared catalog. */
    data class Community(
        private val targetLocaleTag: String,
    ) : AppLanguage("community:${normalizeLanguageTag(targetLocaleTag)}") {
        override val localeTag: String = normalizeLanguageTag(targetLocaleTag)
    }

    val isMaintained: Boolean
        get() = this is Maintained

    val isAutoTranslated: Boolean
        get() = this is AutoTranslated

    /**
     * The language strings come from. [communityTags] are the community
     * languages the app ships. A human community translation of a language
     * wins over on-device translation, and a chosen community language that
     * the app no longer ships resolves like any other tag. A language the app
     * maintains in another script stays English: on-device translation
     * translates a language, not a script, so it would show Traditional
     * Chinese readers machine-translated Simplified Chinese.
     */
    fun resolve(
        systemLocale: Locale = Locale.getDefault(),
        communityTags: Set<String> = emptySet(),
    ): ResolvedAppLanguage {
        val requestedTag = when (this) {
            SystemDefault -> systemLocale.toLanguageTag()
            is Maintained -> localeTag
            is AutoTranslated -> localeTag
            is Community -> localeTag
        }
        val normalizedTag = normalizeLanguageTag(requestedTag)
        val locale = Locale.forLanguageTag(normalizedTag)
        return when (val tag = catalogTag(locale)) {
            Maintained.English.localeTag -> ResolvedAppLanguage.English
            Maintained.Russian.localeTag -> ResolvedAppLanguage.Russian
            Maintained.Japanese.localeTag -> ResolvedAppLanguage.Japanese
            Maintained.Ukrainian.localeTag -> ResolvedAppLanguage.Ukrainian
            Maintained.ChineseSimplified.localeTag -> ResolvedAppLanguage.ChineseSimplified
            in communityTags -> ResolvedAppLanguage.Community(tag)
            else -> if (locale.language.lowercase(Locale.ROOT) in maintainedLanguageCodes) {
                ResolvedAppLanguage.English
            } else {
                ResolvedAppLanguage.AutoTranslated(normalizedTag)
            }
        }
    }

    companion object {
        // Lazy, because these objects subclass AppLanguage: built while the
        // first one touched is still initializing, the list would hold null.
        val maintainedEntries: List<Maintained> by lazy {
            listOf(
                Maintained.English,
                Maintained.Russian,
                Maintained.Japanese,
                Maintained.Ukrainian,
                Maintained.ChineseSimplified,
            )
        }

        /** The language codes the maintained languages are written in, such as zh. */
        val maintainedLanguageCodes: Set<String> by lazy {
            maintainedEntries.map { Locale.forLanguageTag(it.localeTag).language }.toSet()
        }

        // Regions that write Chinese in Traditional characters.
        private val traditionalChineseRegions = setOf("TW", "HK", "MO")

        /**
         * The catalog language a locale reads, as YUME's shared catalog names
         * it: the language code, and for Chinese also the script, since
         * Simplified and Traditional are separate languages there. Java leaves
         * the script empty for zh-CN or zh-TW, so the region decides then:
         * Taiwan, Hong Kong and Macau write Traditional, the rest Simplified.
         */
        fun catalogTag(locale: Locale): String {
            val language = locale.language.lowercase(Locale.ROOT)
            if (language != "zh") return language
            val script = locale.script.ifEmpty {
                if (locale.country.uppercase(Locale.ROOT) in traditionalChineseRegions) "Hant" else "Hans"
            }
            return "zh-$script"
        }

        fun fromWireValue(value: String?): AppLanguage {
            val normalizedValue = value.orEmpty().trim()
            if (normalizedValue.isEmpty() || normalizedValue == SystemDefault.wireValue) {
                return SystemDefault
            }
            maintainedEntries.firstOrNull { it.wireValue == normalizedValue }?.let { return it }
            if (normalizedValue.startsWith("community:", ignoreCase = true)) {
                val localeTag = normalizedValue.substringAfter(':').trim()
                if (localeTag.isNotEmpty()) {
                    return Community(localeTag)
                }
            }
            if (normalizedValue.startsWith("auto:", ignoreCase = true)) {
                val localeTag = normalizedValue.substringAfter(':').trim()
                if (localeTag.isNotEmpty()) {
                    return AutoTranslated(localeTag)
                }
            }
            return AutoTranslated(normalizedValue)
        }

        fun fromLocaleTag(localeTag: String, communityTags: Set<String> = emptySet()): AppLanguage {
            val normalizedTag = normalizeLanguageTag(localeTag)
            val tag = catalogTag(Locale.forLanguageTag(normalizedTag))
            return maintainedEntries.firstOrNull { it.localeTag == tag }
                ?: if (tag in communityTags) Community(tag) else AutoTranslated(normalizedTag)
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

    data object ChineseSimplified : ResolvedAppLanguage("zh-Hans")

    data class AutoTranslated(
        override val localeTag: String,
    ) : ResolvedAppLanguage(localeTag)

    data class Community(
        override val localeTag: String,
    ) : ResolvedAppLanguage(localeTag)
}

data class AppSettings(
    val themeColorMode: ThemeColorMode = ThemeColorMode.Yume,
    val amoledDarkTheme: Boolean = false,
    val language: AppLanguage = AppLanguage.SystemDefault,
    val advancedMode: Boolean = false,
    /** The resolvers the VPN gives the device. Lookups travel through the tunnel. */
    val dnsServers: List<String> = DEFAULT_DNS_SERVERS,
    /** Whether IPv6 enters the tunnel. Off, Android blocks IPv6 while the VPN is up. */
    val carryIpv6: Boolean = false,
) {
    companion object {
        val DEFAULT_DNS_SERVERS = listOf("1.1.1.1", "9.9.9.9")
        const val MAX_DNS_SERVERS = 4

        /**
         * The address literals of a typed list, in order and without
         * duplicates, or null when an entry is not one.
         */
        fun parseDnsServers(text: String): List<String>? {
            val entries = text.split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (entries.isEmpty() || entries.size > MAX_DNS_SERVERS) return null
            if (!entries.all(KitConfig::isAddressLiteral)) return null
            return entries.distinct()
        }
    }
}

class AppSettingsStore private constructor(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val vaultStore = VaultJsonMapStore(appContext, VAULT_NAME)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun updateThemeColorMode(mode: ThemeColorMode) {
        persist(_settings.value.copy(themeColorMode = mode))
    }

    fun updateAmoledDarkTheme(enabled: Boolean) {
        persist(_settings.value.copy(amoledDarkTheme = enabled))
    }

    fun updateLanguage(language: AppLanguage) {
        persist(_settings.value.copy(language = language))
    }

    fun updateAdvancedMode(enabled: Boolean) {
        persist(_settings.value.copy(advancedMode = enabled))
    }

    fun updateDnsServers(servers: List<String>) {
        persist(_settings.value.copy(dnsServers = servers))
    }

    fun updateCarryIpv6(enabled: Boolean) {
        persist(_settings.value.copy(carryIpv6 = enabled))
    }

    private fun loadSettings(): AppSettings {
        val themeColorMode = read(KEY_THEME_COLOR_MODE)?.let(ThemeColorMode::fromWireValue) ?: ThemeColorMode.Yume
        return AppSettings(
            themeColorMode = themeColorMode,
            amoledDarkTheme = read(KEY_AMOLED_DARK_THEME)?.toBooleanStrictOrNull() ?: false,
            language = AppLanguage.fromWireValue(read(KEY_LANGUAGE)),
            advancedMode = read(KEY_ADVANCED_MODE)?.toBooleanStrictOrNull() ?: false,
            dnsServers = read(KEY_DNS_SERVERS)?.let(AppSettings::parseDnsServers) ?: AppSettings.DEFAULT_DNS_SERVERS,
            carryIpv6 = read(KEY_CARRY_IPV6)?.toBooleanStrictOrNull() ?: false,
        )
    }

    // A locked or unreadable vault yields the defaults instead of a crash.
    private fun read(key: String): String? = runCatching { vaultStore.getString(key) }.getOrNull()

    private fun persist(settings: AppSettings) {
        val json = vaultStore.snapshot()
        json.put(KEY_THEME_COLOR_MODE, settings.themeColorMode.wireValue)
        json.put(KEY_AMOLED_DARK_THEME, settings.amoledDarkTheme.toString())
        json.put(KEY_LANGUAGE, settings.language.wireValue)
        json.put(KEY_ADVANCED_MODE, settings.advancedMode.toString())
        json.put(KEY_DNS_SERVERS, settings.dnsServers.joinToString(","))
        json.put(KEY_CARRY_IPV6, settings.carryIpv6.toString())
        vaultStore.replace(json)
        _settings.value = settings
    }

    companion object {
        private const val KEY_THEME_COLOR_MODE = "theme_color_mode"
        private const val KEY_AMOLED_DARK_THEME = "amoled_dark_theme"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ADVANCED_MODE = "advanced_mode"
        private const val KEY_DNS_SERVERS = "dns_servers"
        private const val KEY_CARRY_IPV6 = "carry_ipv6"
        private const val VAULT_NAME = "app_settings"

        @Volatile
        private var instance: AppSettingsStore? = null

        fun readLanguage(context: Context): AppLanguage {
            val appContext = context.applicationContext ?: context
            val vaultStore = VaultJsonMapStore(appContext, VAULT_NAME)
            return AppLanguage.fromWireValue(runCatching { vaultStore.getString(KEY_LANGUAGE) }.getOrNull())
        }

        fun get(context: Context): AppSettingsStore {
            val storeContext = context.applicationContext ?: context
            return instance ?: synchronized(this) {
                instance ?: AppSettingsStore(storeContext).also { instance = it }
            }
        }
    }
}
