package com.fixcraft.yume.i18n

// Built only with -PyumeMlKitTranslation=true. The default build uses
// src/nomlkit's version, which offers no automatic translation.

import android.content.Context
import androidx.core.content.edit
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.i18n.translations.LanguageJa
import com.fixcraft.yume.i18n.translations.LanguageRu
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.LinkedHashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class TranslationRequest(
    val targetLocaleTag: String,
    val sourceLocaleTag: String,
    val sourceText: String,
)

private enum class TranslationFamily { Cyrillic, EastAsian, Global }

object AutoTranslationRuntime {
    private const val PREFS_NAME = "yume_auto_translation_cache"

    // Languages explicitly worth offering on top of whatever ML Kit reports.
    // Some locales are routinely missing from getAllLanguages() on older
    // Play Services revisions; this keeps the list stable across devices.
    private val documentedLanguageTags = listOf(
        "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de",
        "el", "en", "eo", "es", "et", "fa", "fi", "fr", "ga", "gl",
        "gu", "he", "hi", "hr", "ht", "hu", "id", "is", "it", "ja",
        "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl",
        "no", "pl", "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw",
        "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh",
    )

    // Picking ru/ja as the source language for related-script targets gives
    // markedly better NMT output than always translating from English.
    private val cyrillicLanguages = setOf(
        "be", "bg", "bs", "cs", "hr", "mk", "pl", "ru", "sk", "sl", "sr", "uk",
        "kk", "ky", "mn", "tg", "tt", "uz",
    )
    private val eastAsianLanguages = setOf("zh", "ko", "ja", "th", "vi")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translations = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val pending = ConcurrentHashMap<String, LinkedHashSet<TranslationRequest>>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val revisions = ConcurrentHashMap<String, MutableStateFlow<Int>>()
    private val downloadedLocaleTags = MutableStateFlow<Set<String>>(emptySet())

    fun supportedLocaleTags(): List<String> {
        val results = LinkedHashSet<String>()
        documentedLanguageTags.mapNotNull(::bestSupportedLocaleTag).forEach(results::add)
        TranslateLanguage.getAllLanguages().mapNotNull(::normalizeLocaleTag).forEach(results::add)
        Locale.getISOLanguages().mapNotNull(::bestSupportedLocaleTag).forEach(results::add)
        Locale.getAvailableLocales()
            .mapNotNull { bestSupportedLocaleTag(it.toLanguageTag()) }
            .forEach(results::add)
        return results.toList()
    }

    fun isSupported(localeTag: String?): Boolean = resolveTranslateLanguage(localeTag) != null

    fun revision(localeTag: String): StateFlow<Int> =
        revisions.getOrPut(localeTag) { MutableStateFlow(0) }.asStateFlow()

    fun downloadedLocaleTagsState(): StateFlow<Set<String>> = downloadedLocaleTags.asStateFlow()

    suspend fun refreshDownloadedLocaleTags(): Set<String> {
        val models = awaitTask(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
        )
        val downloaded = models.mapNotNull { normalizeLocaleTag(it.language) }.toSet()
        downloadedLocaleTags.value = downloaded
        return downloaded
    }

    suspend fun deleteDownloadedLanguage(context: Context, targetLocaleTag: String): Boolean {
        val normalizedTarget = normalizeLocaleTag(targetLocaleTag) ?: return false
        val targetLanguage = resolveTranslateLanguage(normalizedTarget) ?: return false
        val manager = RemoteModelManager.getInstance()
        runCatching {
            awaitTask(manager.deleteDownloadedModel(TranslateRemoteModel.Builder(targetLanguage).build()))
        }.onFailure { return false }

        clearCachedTranslations(context.applicationContext, normalizedTarget)
        val remaining = refreshDownloadedLocaleTags().toMutableSet()
        val helperSource = helperSourceLocaleTag(normalizedTarget) ?: run {
            refreshDownloadedLocaleTags()
            return true
        }
        val familyStillNeeded = AppLanguageCatalog.autoTranslatedLanguages().any { language ->
            val localeTag = normalizeLocaleTag(language.localeTag) ?: return@any false
            localeTag != normalizedTarget &&
                localeTag in remaining &&
                helperSourceLocaleTag(localeTag) == helperSource
        }
        if (!familyStillNeeded) {
            resolveTranslateLanguage(helperSource)?.let { sourceLanguage ->
                runCatching {
                    awaitTask(manager.deleteDownloadedModel(TranslateRemoteModel.Builder(sourceLanguage).build()))
                }
            }
        }
        refreshDownloadedLocaleTags()
        return true
    }

    fun getOrQueue(context: Context, targetLocaleTag: String, english: String): String {
        val request = buildRequest(targetLocaleTag, english) ?: return english
        cachedTranslation(context.applicationContext, request)?.let { return it }
        enqueue(context.applicationContext, request)
        return request.sourceText
    }

    fun prewarm(context: Context, targetLocaleTag: String, seeds: Iterable<String>) {
        val appContext = context.applicationContext
        seeds.forEach { english ->
            buildRequest(targetLocaleTag, english)?.let { request ->
                if (cachedTranslation(appContext, request) == null) enqueue(appContext, request)
            }
        }
    }

    suspend fun needsPreparation(context: Context, language: AppLanguage): Boolean {
        val resolved = AppLocaleManager.resolveLanguage(context, language)
        if (resolved !is ResolvedAppLanguage.AutoTranslated) return false
        val appContext = context.applicationContext
        return AppStrings.translationSeeds().any { english ->
            buildRequest(resolved.localeTag, english)?.let { cachedTranslation(appContext, it) == null } == true
        }
    }

    suspend fun prepareLanguage(
        context: Context,
        language: AppLanguage,
        onProgress: (LanguagePreparationProgress) -> Unit = {},
    ): Boolean {
        val resolved = AppLocaleManager.resolveLanguage(context, language)
        if (resolved !is ResolvedAppLanguage.AutoTranslated) return true
        val appContext = context.applicationContext
        val requests = AppStrings.translationSeeds()
            .mapNotNull { buildRequest(resolved.localeTag, it) }
            .distinct()
            .filter { cachedTranslation(appContext, it) == null }
        if (requests.isEmpty()) return true
        onProgress(LanguagePreparationProgress(LanguagePreparationStage.Downloading, 0, requests.size))
        return requests.groupBy { familyForLocale(it.targetLocaleTag) }.all { (_, familyRequests) ->
            val translator = createTranslator(familyRequests.first()) ?: return@all false
            try {
                awaitTask(translator.downloadModelIfNeeded())
                refreshDownloadedLocaleTags()
                familyRequests.forEachIndexed { index, request ->
                    onProgress(
                        LanguagePreparationProgress(
                            stage = LanguagePreparationStage.Translating,
                            done = index + 1,
                            total = familyRequests.size,
                        )
                    )
                    val translated = awaitTask(translator.translate(request.sourceText))
                    if (translated.isBlank()) return@all false
                    cacheTranslation(appContext, request, translated)
                }
                true
            } finally {
                translator.close()
            }
        }
    }

    private fun enqueue(context: Context, request: TranslationRequest) {
        val queue = pending.getOrPut(request.targetLocaleTag) { LinkedHashSet() }
        val added = synchronized(queue) { queue.add(request) }
        if (!added || !running.add(request.targetLocaleTag)) return
        scope.launch {
            try {
                processQueue(context, request.targetLocaleTag)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                synchronized(queue) { queue.clear() }
            } finally {
                running.remove(request.targetLocaleTag)
                if (synchronized(queue) { queue.isNotEmpty() }) enqueuePending(context, request.targetLocaleTag)
            }
        }
    }

    private fun enqueuePending(context: Context, targetLocaleTag: String) {
        if (!running.add(targetLocaleTag)) return
        scope.launch {
            try {
                processQueue(context, targetLocaleTag)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                pending[targetLocaleTag]?.let { queue -> synchronized(queue) { queue.clear() } }
            } finally {
                running.remove(targetLocaleTag)
                val queue = pending[targetLocaleTag]
                if (queue != null && synchronized(queue) { queue.isNotEmpty() }) {
                    enqueuePending(context, targetLocaleTag)
                }
            }
        }
    }

    private fun processQueue(context: Context, targetLocaleTag: String) {
        while (true) {
            val batch = nextBatch(targetLocaleTag)
            if (batch.isEmpty()) return
            batch.groupBy { familyForLocale(it.targetLocaleTag) }.forEach { (_, requests) ->
                val translator = createTranslator(requests.first()) ?: return@forEach
                try {
                    Tasks.await(translator.downloadModelIfNeeded())
                    requests.forEach { request ->
                        runCatching { Tasks.await(translator.translate(request.sourceText)) }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { cacheTranslation(context.applicationContext, request, it) }
                    }
                } finally {
                    translator.close()
                }
            }
        }
    }

    private fun nextBatch(targetLocaleTag: String): List<TranslationRequest> {
        val queue = pending[targetLocaleTag] ?: return emptyList()
        return synchronized(queue) {
            val iterator = queue.iterator()
            buildList {
                repeat(12) {
                    if (!iterator.hasNext()) return@repeat
                    add(iterator.next())
                    iterator.remove()
                }
            }
        }
    }

    // Detect printf-style format specifiers (%s, %d, %1$s, %.2f, …). NMT pipelines mangle
    // them — "App: %s" routinely comes back as "Приложение:% s" which then crashes
    // String.format. Strings that need substitution are skipped here; AppStrings falls
    // back to the English template, which is the wire-format we control.
    private val formatSpecRegex = Regex("""%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaAtTn%]""")

    private fun buildRequest(targetLocaleTag: String, english: String): TranslationRequest? {
        val normalizedTarget = normalizeLocaleTag(targetLocaleTag) ?: return null
        if (!isSupported(normalizedTarget)) return null
        if (formatSpecRegex.containsMatchIn(english)) return null
        val (sourceTag, sourceText) = when (familyForLocale(normalizedTarget)) {
            TranslationFamily.Cyrillic -> "ru" to (TranslationCatalog.sourceToId[english]?.let(LanguageRu.translations::get) ?: english)
            TranslationFamily.EastAsian -> "ja" to (TranslationCatalog.sourceToId[english]?.let(LanguageJa.translations::get) ?: english)
            TranslationFamily.Global -> "en" to english
        }
        val trimmed = sourceText.trim()
        if (trimmed.isEmpty()) return null
        return TranslationRequest(normalizedTarget, sourceTag, trimmed)
    }

    private fun familyForLocale(localeTag: String): TranslationFamily {
        val code = Locale.forLanguageTag(localeTag).language.lowercase(Locale.ROOT)
        return when {
            code in cyrillicLanguages -> TranslationFamily.Cyrillic
            code in eastAsianLanguages -> TranslationFamily.EastAsian
            else -> TranslationFamily.Global
        }
    }

    private fun createTranslator(request: TranslationRequest): Translator? {
        val source = resolveTranslateLanguage(request.sourceLocaleTag) ?: return null
        val target = resolveTranslateLanguage(request.targetLocaleTag) ?: return null
        return Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()
        )
    }

    private fun helperSourceLocaleTag(targetLocaleTag: String): String? = when (familyForLocale(targetLocaleTag)) {
        TranslationFamily.Cyrillic -> "ru"
        TranslationFamily.EastAsian -> "ja"
        TranslationFamily.Global -> null
    }

    private fun cachedTranslation(context: Context, request: TranslationRequest): String? {
        val bucket = request.cacheBucket()
        translations[bucket]?.get(request.sourceText)?.let { return it }
        val persisted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(cacheKey(request), null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        translations.getOrPut(bucket) { ConcurrentHashMap() }[request.sourceText] = persisted
        return persisted
    }

    private fun cacheTranslation(context: Context, request: TranslationRequest, translatedText: String) {
        val bucket = request.cacheBucket()
        val existing = translations.getOrPut(bucket) { ConcurrentHashMap() }.put(request.sourceText, translatedText)
        if (existing == translatedText) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(cacheKey(request), translatedText)
        }
        revisions.getOrPut(request.targetLocaleTag) { MutableStateFlow(0) }.value += 1
    }

    private fun clearCachedTranslations(context: Context, targetLocaleTag: String) {
        val normalizedTarget = normalizeLocaleTag(targetLocaleTag) ?: return
        translations.keys
            .filter { it.startsWith("$normalizedTarget|") }
            .forEach(translations::remove)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { it.startsWith("auto.$normalizedTarget.") }
        prefs.edit { keysToRemove.forEach(::remove) }
        revisions.getOrPut(normalizedTarget) { MutableStateFlow(0) }.value += 1
    }

    private fun cacheKey(request: TranslationRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(request.sourceText.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "auto.${request.targetLocaleTag}.${request.sourceLocaleTag}.$digest"
    }

    private fun TranslationRequest.cacheBucket(): String = "$targetLocaleTag|$sourceLocaleTag"

    private fun resolveTranslateLanguage(localeTag: String?): String? {
        val normalized = normalizeLocaleTag(localeTag) ?: return null
        return TranslateLanguage.fromLanguageTag(normalized)
            ?: TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(normalized).language)
    }

    private fun bestSupportedLocaleTag(localeTag: String?): String? {
        val normalized = normalizeLocaleTag(localeTag) ?: return null
        if (resolveTranslateLanguage(normalized) != null) return normalized
        val language = Locale.forLanguageTag(normalized).language.lowercase(Locale.ROOT)
        return normalizeLocaleTag(language)?.takeIf { resolveTranslateLanguage(it) != null }
    }

    private fun normalizeLocaleTag(localeTag: String?): String? {
        val normalized = Locale.forLanguageTag(localeTag.orEmpty().trim()).toLanguageTag()
        return normalized.takeIf { it.isNotBlank() && it != "und" }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T =
        withContext(Dispatchers.IO) { Tasks.await(task) }
}
