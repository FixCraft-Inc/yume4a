package com.fixcraft.yume.i18n

import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ResolvedAppLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Community translations from YUME's catalog: how a choice or the phone's
 * language resolves to one, and that what it leaves out stays English.
 */
class CommunityLanguageTest {

    private val german = setOf("de")

    @Test
    fun communityLanguageResolvesOnlyWhileShipped() {
        val chosen = AppLanguage.Community("de")
        assertEquals(ResolvedAppLanguage.Community("de"), chosen.resolve(Locale.ENGLISH, german))
        // A community language the app no longer ships resolves like any tag.
        assertEquals(ResolvedAppLanguage.AutoTranslated("de"), chosen.resolve(Locale.ENGLISH))
    }

    @Test
    fun phoneLanguagePrefersMaintainedThenCommunity() {
        assertEquals(
            ResolvedAppLanguage.Community("de"),
            AppLanguage.SystemDefault.resolve(Locale.GERMANY, german),
        )
        assertEquals(
            ResolvedAppLanguage.Russian,
            AppLanguage.SystemDefault.resolve(Locale.forLanguageTag("ru"), setOf("ru")),
        )
        assertEquals(AppLanguage.Community("de"), AppLanguage.fromLocaleTag("de-DE", german))
        // A person's translation wins over on-device translation.
        assertEquals(ResolvedAppLanguage.Community("de"), AppLanguage.AutoTranslated("de").resolve(Locale.ENGLISH, german))
    }

    @Test
    fun choiceSurvivesStorage() {
        val chosen = AppLanguage.Community("de")
        assertEquals("community:de", chosen.wireValue)
        assertEquals(chosen, AppLanguage.fromWireValue(chosen.wireValue))
    }

    @Test
    fun untranslatedMessagesStayEnglish() {
        val bundle = object : LanguageBundle {
            override val localeTag = "de"
            override val translations = mapOf(MessageId.NAV_BACK.id to "Zurück")
        }
        val strings = AppStrings.forCommunity(ResolvedAppLanguage.Community("de"), mapOf("de" to bundle))
        assertEquals("Zurück", strings.back)
        assertEquals(MessageId.CLOSE.source, strings.close)
        val missing = AppStrings.forCommunity(ResolvedAppLanguage.Community("de"), emptyMap())
        assertEquals(MessageId.NAV_BACK.source, missing.back)
    }

    @Test
    fun nativeLanguagesNameTheirTranslatorsAndSummariesStayShort() {
        val english = AppStrings.forLanguage(ResolvedAppLanguage.English)
        for (language in AppLanguage.maintainedEntries - AppLanguage.Maintained.English) {
            val credits = MaintainedLanguageCredits.credits.getValue(language.localeTag)
            assertEquals(english.creditNames(credits), english.languageDescription(language))
            assertEquals(english.languageName(language), english.languageSummary(language))
        }
        assertEquals("FixCraft Inc.", english.languageDescription(AppLanguage.Maintained.Russian))
    }

    @Test
    fun catalogListsCommunityLanguagesBesideMaintainedOnes() {
        val credit = TranslationCredit("Example Studio", TranslationCredit.Kind.Studio, null)
        val listed = listOf(
            CommunityLanguage("fr", "Français", 100, listOf(credit)),
            CommunityLanguage("de", "Deutsch", 90, listOf(credit)),
            CommunityLanguage("ru", "Русский", 100, listOf(credit)),
        )
        assertEquals(
            listOf(AppLanguage.Community("de"), AppLanguage.Community("fr")),
            AppLanguageCatalog.communityLanguages(listed),
        )
    }
}
