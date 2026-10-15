package com.fixcraft.yume.i18n

import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ResolvedAppLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Simplified Chinese is a maintained language of its own script: the phone's
 * Simplified locales read it, and Traditional locales never get it or a
 * machine translation into Simplified in its place.
 */
class ChineseLanguageTest {

    private fun phone(tag: String, community: Set<String> = emptySet()) =
        AppLanguage.SystemDefault.resolve(Locale.forLanguageTag(tag), community)

    @Test
    fun simplifiedLocalesReadTheMaintainedTranslation() {
        for (tag in listOf("zh-CN", "zh-SG", "zh-Hans", "zh-Hans-CN", "zh")) {
            assertEquals(tag, ResolvedAppLanguage.ChineseSimplified, phone(tag))
        }
        assertEquals("返回", AppStrings.forLanguage(ResolvedAppLanguage.ChineseSimplified).back)
    }

    @Test
    fun traditionalLocalesStayEnglishUntilTheirOwnTranslationShips() {
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant", "zh-Hant-CN")) {
            assertEquals(tag, ResolvedAppLanguage.English, phone(tag))
            assertEquals(tag, ResolvedAppLanguage.Community("zh-Hant"), phone(tag, setOf("zh-Hant")))
        }
    }

    @Test
    fun choicesResolveByScript() {
        assertEquals(AppLanguage.Maintained.ChineseSimplified, AppLanguage.fromLocaleTag("zh-CN"))
        assertEquals(AppLanguage.Community("zh-Hant"), AppLanguage.fromLocaleTag("zh-TW", setOf("zh-Hant")))
        assertEquals(AppLanguage.Maintained.ChineseSimplified, AppLanguage.fromWireValue("zh-Hans"))
        // An on-device choice saved before Chinese was maintained now reads it.
        assertEquals(
            ResolvedAppLanguage.ChineseSimplified,
            AppLanguage.fromWireValue("auto:zh").resolve(Locale.ENGLISH),
        )
        assertEquals("zh-Hans", AppLanguage.catalogTag(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant", AppLanguage.catalogTag(Locale.TRADITIONAL_CHINESE))
        assertEquals("ja", AppLanguage.catalogTag(Locale.JAPAN))
    }

    @Test
    fun pickerListsATraditionalCommunityTranslationBesideSimplified() {
        val credit = TranslationCredit("Example Studio", TranslationCredit.Kind.Studio, null)
        val listed = listOf(CommunityLanguage("zh-Hant", "繁體中文", 90, listOf(credit)))
        assertEquals(listOf(AppLanguage.Community("zh-Hant")), AppLanguageCatalog.communityLanguages(listed))
        assertEquals("简体中文", AppLanguageCatalog.displayName(AppLanguage.Maintained.ChineseSimplified))
    }
}
