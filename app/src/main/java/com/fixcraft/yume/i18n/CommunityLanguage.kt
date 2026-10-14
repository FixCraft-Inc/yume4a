package com.fixcraft.yume.i18n

/** Who translated a language, maintained or community, as YUME's catalog credits them. */
data class TranslationCredit(
    val name: String,
    val kind: Kind,
    val url: String?,
) {
    enum class Kind {
        Person,
        Studio,
    }
}

/**
 * A human community translation that the generated [CommunityLanguages] lists.
 * Messages it leaves out, including those its catalog reserves for
 * maintainers, stay English.
 */
data class CommunityLanguage(
    val localeTag: String,
    val name: String,
    val coveragePercent: Int,
    val credits: List<TranslationCredit>,
) {
    val complete: Boolean
        get() = coveragePercent >= 100
}

/** The language codes of the community translations this app ships. */
internal val communityLanguageTags: Set<String>
    get() = CommunityLanguages.all.mapTo(linkedSetOf()) { it.localeTag }

internal fun communityLanguage(localeTag: String): CommunityLanguage? =
    CommunityLanguages.all.firstOrNull { it.localeTag == localeTag }
