package com.fixcraft.yume.i18n

import android.content.Context
import com.fixcraft.yume.data.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default build's automatic translation: none. Google ML Kit, which
 * translated the interface into languages without bundled strings, sends
 * usage metrics to Google and adds about 16 MB, so it is built only with
 * -PyumeMlKitTranslation=true (src/mlkit). Here no language is offered for
 * automatic translation, nothing is downloaded, and text stays in English
 * unless a bundled translation covers it.
 */
object AutoTranslationRuntime {
    private val noRevision = MutableStateFlow(0).asStateFlow()
    private val noDownloads = MutableStateFlow<Set<String>>(emptySet()).asStateFlow()

    fun supportedLocaleTags(): List<String> = emptyList()

    fun isSupported(localeTag: String?): Boolean = false

    fun revision(localeTag: String): StateFlow<Int> = noRevision

    fun downloadedLocaleTagsState(): StateFlow<Set<String>> = noDownloads

    suspend fun refreshDownloadedLocaleTags(): Set<String> = emptySet()

    suspend fun deleteDownloadedLanguage(context: Context, targetLocaleTag: String): Boolean = false

    fun getOrQueue(context: Context, targetLocaleTag: String, english: String): String = english

    fun prewarm(context: Context, targetLocaleTag: String, seeds: Iterable<String>) = Unit

    suspend fun needsPreparation(context: Context, language: AppLanguage): Boolean = false

    suspend fun prepareLanguage(
        context: Context,
        language: AppLanguage,
        onProgress: (LanguagePreparationProgress) -> Unit = {},
    ): Boolean = true
}
