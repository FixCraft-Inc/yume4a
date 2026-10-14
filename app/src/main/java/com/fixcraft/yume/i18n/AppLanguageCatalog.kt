package com.fixcraft.yume.i18n

import com.fixcraft.yume.data.AppLanguage
import java.util.Locale

enum class AppLanguageGroup {
    Maintained,
    Community,
    AutoTranslated,
}

data class AppLanguageOption(
    val language: AppLanguage,
    val group: AppLanguageGroup,
    val displayName: String,
)

object AppLanguageCatalog {
    private val maintainedLanguageCodes = AppLanguage.maintainedEntries
        .map { Locale.forLanguageTag(it.localeTag).language.lowercase(Locale.ROOT) }
        .toSet()

    fun maintainedLanguages(): List<AppLanguage> = listOf(AppLanguage.SystemDefault) + AppLanguage.maintainedEntries

    /** Shipped community translations, by name. */
    fun communityLanguages(
        listed: List<CommunityLanguage> = CommunityLanguages.all,
    ): List<AppLanguage.Community> = listed
        .filter { it.localeTag !in maintainedLanguageCodes }
        .sortedBy { it.name }
        .map { AppLanguage.Community(it.localeTag) }

    /** On-device translation, for languages no person translated. */
    fun autoTranslatedLanguages(): List<AppLanguage.AutoTranslated> {
        val community = communityLanguageTags
        return AutoTranslationRuntime.supportedLocaleTags()
            .mapNotNull { localeTag ->
                val languageCode = Locale.forLanguageTag(localeTag).language.lowercase(Locale.ROOT)
                localeTag.takeIf {
                    languageCode.isNotBlank() && languageCode !in maintainedLanguageCodes && languageCode !in community
                }
            }
            .map(AppLanguage::fromLocaleTag)
            .filterIsInstance<AppLanguage.AutoTranslated>()
            .distinctBy { Locale.forLanguageTag(it.localeTag).language.lowercase(Locale.ROOT) }
            .sortedBy { displayName(it) }
    }

    fun groupedOptions(): List<AppLanguageOption> {
        val maintained = maintainedLanguages().map {
            AppLanguageOption(
                language = it,
                group = AppLanguageGroup.Maintained,
                displayName = displayName(it),
            )
        }
        val community = communityLanguages().map {
            AppLanguageOption(
                language = it,
                group = AppLanguageGroup.Community,
                displayName = displayName(it),
            )
        }
        val autoTranslated = autoTranslatedLanguages().map {
            AppLanguageOption(
                language = it,
                group = AppLanguageGroup.AutoTranslated,
                displayName = displayName(it),
            )
        }
        return maintained + community + autoTranslated
    }

    fun displayName(language: AppLanguage): String {
        return when (language) {
            AppLanguage.SystemDefault -> "System default"
            AppLanguage.Maintained.English -> "English"
            AppLanguage.Maintained.Russian -> "Русский"
            AppLanguage.Maintained.Japanese -> "日本語"
            AppLanguage.Maintained.Ukrainian -> "Українська"
            is AppLanguage.AutoTranslated -> nativeLanguageName(language.localeTag)
            is AppLanguage.Community -> communityLanguage(language.localeTag)?.name
                ?: nativeLanguageName(language.localeTag)
        }
    }

    private fun nativeLanguageName(localeTag: String?): String {
        val locale = Locale.forLanguageTag(localeTag.orEmpty())
        val nativeName = locale.getDisplayName(locale).trim()
        if (nativeName.isEmpty()) {
            return localeTag.orEmpty().ifBlank { "Unknown" }
        }
        return nativeName.replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(locale)
            } else {
                character.toString()
            }
        }
    }
}
