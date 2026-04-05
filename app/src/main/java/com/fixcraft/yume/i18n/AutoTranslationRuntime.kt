package com.fixcraft.yume.i18n

import android.content.Context
import androidx.core.content.edit
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateRemoteModel
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

enum class LanguagePreparationStage {
    Downloading,
    Translating,
}

data class LanguagePreparationProgress(
    val stage: LanguagePreparationStage,
    val done: Int,
    val total: Int,
)

private data class TranslationRequest(
    val targetLocaleTag: String,
    val sourceLocaleTag: String,
    val sourceText: String,
)

private data class TranslationSource(
    val localeTag: String,
    val text: String,
)

private enum class TranslationFamily {
    Cyrillic,
    EastAsian,
    Global,
}

object AutoTranslationRuntime {
    private const val PREFS_NAME = "yume_auto_translation_cache"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val translations = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    private val pending = ConcurrentHashMap<String, LinkedHashSet<TranslationRequest>>()
    private val running = ConcurrentHashMap.newKeySet<String>()
    private val revisions = ConcurrentHashMap<String, MutableStateFlow<Int>>()
    private val downloadedLocaleTags = MutableStateFlow<Set<String>>(emptySet())
    private val cyrillicLanguages = setOf(
        "be", "bg", "bs", "cs", "hr", "mk", "pl", "ru", "sk", "sl", "sr", "uk",
        "kk", "ky", "mn", "tg", "tt", "uz",
    )
    private val eastAsianLanguages = setOf(
        "zh", "ko", "ja", "th", "vi",
    )

    fun supportedLocaleTags(): List<String> {
        return TranslateLanguage.getAllLanguages()
            .mapNotNull { normalizeLocaleTag(it) }
            .distinct()
    }

    fun isSupported(localeTag: String?): Boolean = resolveTranslateLanguage(localeTag) != null

    fun revision(localeTag: String): StateFlow<Int> {
        return revisions.getOrPut(localeTag) { MutableStateFlow(0) }.asStateFlow()
    }

    fun downloadedLocaleTagsState(): StateFlow<Set<String>> = downloadedLocaleTags.asStateFlow()

    suspend fun refreshDownloadedLocaleTags(): Set<String> {
        val models = awaitTask(
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java)
        )
        val downloaded = models
            .mapNotNull { normalizeLocaleTag(it.language) }
            .toSet()
        downloadedLocaleTags.value = downloaded
        return downloaded
    }

    suspend fun deleteDownloadedLanguage(context: Context, targetLocaleTag: String): Boolean {
        val normalizedTarget = normalizeLocaleTag(targetLocaleTag) ?: return false
        val targetLanguage = resolveTranslateLanguage(normalizedTarget) ?: return false
        val manager = RemoteModelManager.getInstance()
        runCatching {
            awaitTask(manager.deleteDownloadedModel(TranslateRemoteModel.Builder(targetLanguage).build()))
        }.onFailure {
            return false
        }
        clearCachedTranslations(context.applicationContext, normalizedTarget)
        val remaining = refreshDownloadedLocaleTags().toMutableSet()
        val sourceLocaleTag = helperSourceLocaleTag(normalizedTarget)
        if (sourceLocaleTag != null) {
            val familyStillNeeded = AppLanguageCatalog.autoTranslatedLanguages().any { language ->
                val localeTag = normalizeLocaleTag(language.localeTag) ?: return@any false
                localeTag != normalizedTarget &&
                    localeTag in remaining &&
                    helperSourceLocaleTag(localeTag) == sourceLocaleTag
            }
            if (!familyStillNeeded) {
                resolveTranslateLanguage(sourceLocaleTag)?.let { sourceLanguage ->
                    runCatching {
                        awaitTask(manager.deleteDownloadedModel(TranslateRemoteModel.Builder(sourceLanguage).build()))
                    }
                    remaining.remove(sourceLocaleTag)
                }
            }
        }
        refreshDownloadedLocaleTags()
        return true
    }

    fun getOrQueue(context: Context, targetLocaleTag: String, seed: TranslationSeed): String {
        val request = buildRequest(targetLocaleTag, seed) ?: return seed.english
        val cached = cachedTranslation(context.applicationContext, request)
        if (cached != null) {
            return cached
        }
        enqueue(context.applicationContext, request)
        return request.sourceText
    }

    fun prewarm(context: Context, targetLocaleTag: String, seeds: Iterable<TranslationSeed>) {
        val appContext = context.applicationContext
        seeds.forEach { seed ->
            buildRequest(targetLocaleTag, seed)?.let { request ->
                if (cachedTranslation(appContext, request) == null) {
                    enqueue(appContext, request)
                }
            }
        }
    }

    suspend fun needsPreparation(context: Context, language: AppLanguage): Boolean {
        val resolved = AppLocaleManager.resolveLanguage(context, language)
        if (resolved !is ResolvedAppLanguage.AutoTranslated) {
            return false
        }
        val appContext = context.applicationContext
        return AppStrings.translationSeeds().any { seed ->
            buildRequest(resolved.localeTag, seed)?.let { cachedTranslation(appContext, it) == null } == true
        }
    }

    suspend fun prepareLanguage(
        context: Context,
        language: AppLanguage,
        onProgress: (LanguagePreparationProgress) -> Unit = {},
    ): Boolean {
        val resolved = AppLocaleManager.resolveLanguage(context, language)
        if (resolved !is ResolvedAppLanguage.AutoTranslated) {
            return true
        }
        val requests = AppStrings.translationSeeds()
            .mapNotNull { buildRequest(resolved.localeTag, it) }
            .distinct()
            .filter { cachedTranslation(context.applicationContext, it) == null }
        if (requests.isEmpty()) {
            return true
        }
        onProgress(LanguagePreparationProgress(LanguagePreparationStage.Downloading, 0, requests.size))
        val requestsBySource = requests.groupBy { familyForLocale(it.targetLocaleTag) }
        return requestsBySource.all { (family, familyRequests) ->
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
                    val translated = runCatching {
                        awaitTask(translator.translate(request.sourceText))
                    }.getOrNull()
                    if (!translated.isNullOrBlank()) {
                        cacheTranslation(context.applicationContext, request, translated)
                    }
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
        if (!added || !running.add(request.targetLocaleTag)) {
            return
        }
        scope.launch {
            try {
                processQueue(context, request.targetLocaleTag)
            } finally {
                running.remove(request.targetLocaleTag)
                if (synchronized(queue) { queue.isNotEmpty() }) {
                    enqueuePending(context, request.targetLocaleTag)
                }
            }
        }
    }

    private fun enqueuePending(context: Context, targetLocaleTag: String) {
        if (!running.add(targetLocaleTag)) {
            return
        }
        scope.launch {
            try {
                processQueue(context, targetLocaleTag)
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
            if (batch.isEmpty()) {
                return
            }
            batch.groupBy { familyForLocale(it.targetLocaleTag) }.forEach { (_, requests) ->
                val translator = createTranslator(requests.first()) ?: return@forEach
                try {
                    Tasks.await(translator.downloadModelIfNeeded())
                    requests.forEach { request ->
                        val translated = runCatching {
                            Tasks.await(translator.translate(request.sourceText))
                        }.getOrNull()
                        if (!translated.isNullOrBlank()) {
                            cacheTranslation(context.applicationContext, request, translated)
                        }
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
                    if (!iterator.hasNext()) {
                        return@repeat
                    }
                    add(iterator.next())
                    iterator.remove()
                }
            }
        }
    }

    private fun buildRequest(targetLocaleTag: String, seed: TranslationSeed): TranslationRequest? {
        val normalizedTarget = normalizeLocaleTag(targetLocaleTag) ?: return null
        if (!isSupported(normalizedTarget)) {
            return null
        }
        val source = chooseSource(normalizedTarget, seed)
        return TranslationRequest(
            targetLocaleTag = normalizedTarget,
            sourceLocaleTag = source.localeTag,
            sourceText = source.text.trim(),
        ).takeIf { it.sourceText.isNotEmpty() }
    }

    private fun chooseSource(targetLocaleTag: String, seed: TranslationSeed): TranslationSource {
        return when (familyForLocale(targetLocaleTag)) {
            TranslationFamily.Cyrillic -> TranslationSource("ru", seed.russian.ifBlank { seed.english })
            TranslationFamily.EastAsian -> TranslationSource("ja", seed.japanese.ifBlank { seed.english })
            TranslationFamily.Global -> TranslationSource("en", seed.english)
        }
    }

    private fun familyForLocale(localeTag: String): TranslationFamily {
        val languageCode = Locale.forLanguageTag(localeTag).language.lowercase(Locale.ROOT)
        return when {
            languageCode in cyrillicLanguages -> TranslationFamily.Cyrillic
            languageCode in eastAsianLanguages -> TranslationFamily.EastAsian
            else -> TranslationFamily.Global
        }
    }

    private fun createTranslator(request: TranslationRequest): Translator? {
        val sourceLanguage = resolveTranslateLanguage(request.sourceLocaleTag) ?: return null
        val targetLanguage = resolveTranslateLanguage(request.targetLocaleTag) ?: return null
        return Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(sourceLanguage)
                .setTargetLanguage(targetLanguage)
                .build()
        )
    }

    private fun helperSourceLocaleTag(targetLocaleTag: String): String? {
        return when (familyForLocale(targetLocaleTag)) {
            TranslationFamily.Cyrillic -> "ru"
            TranslationFamily.EastAsian -> "ja"
            TranslationFamily.Global -> null
        }
    }

    private fun cachedTranslation(context: Context, request: TranslationRequest): String? {
        val key = request.cacheBucket()
        translations[key]?.get(request.sourceText)?.let { return it }
        val persisted = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(cacheKey(request), null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        translations.getOrPut(key) { ConcurrentHashMap() }[request.sourceText] = persisted
        return persisted
    }

    private fun cacheTranslation(context: Context, request: TranslationRequest, translatedText: String) {
        val key = request.cacheBucket()
        val existing = translations.getOrPut(key) { ConcurrentHashMap() }.put(request.sourceText, translatedText)
        if (existing == translatedText) {
            return
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString(cacheKey(request), translatedText)
        }
        revisions.getOrPut(request.targetLocaleTag) { MutableStateFlow(0) }.value += 1
    }

    private fun clearCachedTranslations(context: Context, targetLocaleTag: String) {
        val normalizedTarget = normalizeLocaleTag(targetLocaleTag) ?: return
        translations.keys
            .filter { key -> key.startsWith("$normalizedTarget|") }
            .forEach { key -> translations.remove(key) }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keysToRemove = prefs.all.keys.filter { key -> key.startsWith("auto.$normalizedTarget.") }
        prefs.edit {
            keysToRemove.forEach(::remove)
        }
        revisions.getOrPut(normalizedTarget) { MutableStateFlow(0) }.value += 1
    }

    private fun cacheKey(request: TranslationRequest): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(request.sourceText.toByteArray(StandardCharsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
        return "auto.${request.targetLocaleTag}.${request.sourceLocaleTag}.$digest"
    }

    private fun TranslationRequest.cacheBucket(): String {
        return "${targetLocaleTag}|${sourceLocaleTag}"
    }

    private fun resolveTranslateLanguage(localeTag: String?): String? {
        val normalizedLocale = normalizeLocaleTag(localeTag) ?: return null
        return TranslateLanguage.fromLanguageTag(normalizedLocale)
            ?: TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(normalizedLocale).language)
    }

    private fun normalizeLocaleTag(localeTag: String?): String? {
        val normalized = Locale.forLanguageTag(localeTag.orEmpty().trim()).toLanguageTag()
        return normalized.takeIf { it.isNotBlank() && it != "und" }
    }

    private suspend fun <T> awaitTask(task: Task<T>): T {
        return withContext(Dispatchers.IO) {
            Tasks.await(task)
        }
    }
}
