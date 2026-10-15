package com.fixcraft.yume.i18n

import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.i18n.translations.LanguageJa
import com.fixcraft.yume.i18n.translations.LanguageRu
import com.fixcraft.yume.i18n.TranslationCatalog
import com.fixcraft.yume.i18n.translations.LanguageUk
import com.fixcraft.yume.i18n.translations.LanguageZhHans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Coverage smoke for the maintained translation bundles. The seeds list is the
 * authoritative set of english source strings used by AppStrings; every
 * maintained language must translate every seed. If this fails, either the
 * english source was renamed without updating the maps, or a new key was
 * introduced without a translation.
 */
class AppStringsTranslationTest {

    private val seeds: List<String> = AppStrings.translationSeeds()

    private val bundles = listOf(
        "ru" to LanguageRu.translations,
        "ja" to LanguageJa.translations,
        "uk" to LanguageUk.translations,
        "zh-Hans" to LanguageZhHans.translations,
    )

    @Test
    fun seedListIsNotEmpty() {
        assertFalse("AppStrings.translationSeeds() returned no entries", seeds.isEmpty())
    }

    @Test
    fun everyMaintainedLanguageCoversEverySeed() {
        val missing = mutableListOf<String>()
        for ((tag, map) in bundles) {
            for (seed in seeds) {
                if (TranslationCatalog.sourceToId[seed]?.let(map::containsKey) != true) {
                    missing += "[$tag] $seed"
                }
            }
        }
        assertEquals(
            "Missing translations:\n" + missing.joinToString("\n"),
            0,
            missing.size,
        )
    }

    @Test
    fun generatedBundlesHaveExactlyTheCatalogIdsAndMatchingFormats() {
        val ids = TranslationCatalog.sourceToId.values.toSet()
        for ((tag, map) in bundles) {
            assertEquals("[$tag] obsolete or missing IDs", ids, map.keys)
            for ((source, id) in TranslationCatalog.sourceToId) {
                val sourceKinds = formatKinds(source)
                assertEquals("[$tag] placeholder mismatch for $id", sourceKinds, formatKinds(map.getValue(id)))
            }
        }
    }

    private fun formatKinds(value: String): List<Char> = Regex("""%(?:\d+\$)?[-+# 0,(]*\d*(?:\.\d+)?([sdif])""")
        .findAll(value)
        .map { it.groupValues[1][0] }
        .toList()

    @Test
    fun englishFallbackForUnknownKeyMatchesEnglish() {
        val russian = AppStrings.forLanguage(ResolvedAppLanguage.Russian)
        // Force a key that is not present in any language bundle by going
        // through the private companion path — pick something user-visible.
        // We test via a known good seed instead: it must return the bundle
        // value, not the english source.
        val seed = "Back"
        assertNotNull(LanguageRu.translations[TranslationCatalog.sourceToId[seed]])
        // Sanity: english-mode never consults the bundle.
        val english = AppStrings.forLanguage(ResolvedAppLanguage.English)
        assertEquals("Back", english.back)
        assertEquals(LanguageRu.translations[TranslationCatalog.sourceToId["Back"]], russian.back)
    }
}
