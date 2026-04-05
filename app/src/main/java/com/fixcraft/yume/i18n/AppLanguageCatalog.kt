package com.fixcraft.yume.i18n

import com.fixcraft.yume.data.AppLanguage
import java.util.Locale

enum class AppLanguageGroup {
    Maintained,
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

    fun autoTranslatedLanguages(): List<AppLanguage.AutoTranslated> {
        return AutoTranslationRuntime.supportedLocaleTags()
            .mapNotNull { localeTag ->
                val languageCode = Locale.forLanguageTag(localeTag).language.lowercase(Locale.ROOT)
                localeTag.takeIf { languageCode.isNotBlank() && languageCode !in maintainedLanguageCodes }
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
        val autoTranslated = autoTranslatedLanguages().map {
            AppLanguageOption(
                language = it,
                group = AppLanguageGroup.AutoTranslated,
                displayName = displayName(it),
            )
        }
        return maintained + autoTranslated
    }

    fun displayName(language: AppLanguage): String {
        return when (language) {
            AppLanguage.SystemDefault -> "System default"
            AppLanguage.Maintained.English -> "English"
            AppLanguage.Maintained.Russian -> "Русский"
            AppLanguage.Maintained.Japanese -> "日本語"
            is AppLanguage.AutoTranslated -> nativeLanguageName(language.localeTag)
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
