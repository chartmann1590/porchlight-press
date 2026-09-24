package com.charleshartman.porchlightpress.data.repo

import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.StoryFts
import com.charleshartman.porchlightpress.data.local.StoryTranslation
import com.charleshartman.porchlightpress.data.local.UiTranslation
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.StoryLocationDto
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Wraps ML Kit Translator + RemoteModelManager. Faked in unit tests. */
interface MlTranslatorEngine : Closeable {
    suspend fun translate(text: String, sourceLang: String, targetLang: String): String
    suspend fun ensureModel(lang: String, wifiOnly: Boolean): Boolean
    suspend fun deleteModel(lang: String): Boolean
    suspend fun isDownloaded(lang: String): Boolean
    fun supportedTags(): List<String>
}

/** Minimal Task<T>.await() so we don't pull in coroutines-play-services. */
private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T =
    suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

class MlKitTranslatorEngine : MlTranslatorEngine {
    private val clients = mutableMapOf<String, Translator>()
    private val lock = Any()

    private fun clientFor(sourceLang: String, targetLang: String): Translator {
        val key = "$sourceLang>$targetLang"
        synchronized(lock) {
            return clients.getOrPut(key) {
                val options = TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.fromLanguageTag(sourceLang) ?: TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.fromLanguageTag(targetLang) ?: TranslateLanguage.ENGLISH)
                    .build()
                Translation.getClient(options)
            }
        }
    }

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String =
        withContext(Dispatchers.IO) {
            clientFor(sourceLang, targetLang).translate(text).await()
        }

    override suspend fun ensureModel(lang: String, wifiOnly: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val conditions = DownloadConditions.Builder()
                    .apply { if (wifiOnly) requireWifi() }
                    .build()
                // Warm both directions against English; the edition source is English.
                clientFor(TranslateLanguage.ENGLISH, lang).downloadModelIfNeeded(conditions).await()
                true
            } catch (e: Exception) {
                false
            }
        }

    override suspend fun deleteModel(lang: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val models = com.google.mlkit.common.model.RemoteModelManager.getInstance()
                    .getDownloadedModels(
                        com.google.mlkit.nl.translate.TranslateRemoteModel::class.java,
                    ).await()
                var ok = true
                for (m in models) {
                    if (m.language == lang) {
                        com.google.mlkit.common.model.RemoteModelManager.getInstance()
                            .deleteDownloadedModel(m).await()
                    }
                }
                ok
            } catch (e: Exception) {
                false
            }
        }

    override suspend fun isDownloaded(lang: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val models = com.google.mlkit.common.model.RemoteModelManager.getInstance()
                    .getDownloadedModels(
                        com.google.mlkit.nl.translate.TranslateRemoteModel::class.java,
                    ).await()
                models.any { it.language == lang }
            } catch (e: Exception) {
                false
            }
        }

    override fun supportedTags(): List<String> =
        TranslateLanguage.getAllLanguages().map { it }.sorted()

    override fun close() {
        synchronized(lock) {
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
        }
    }
}

/** Fake for unit tests: records calls, returns canned translations. */
class FakeTranslatorEngine(
    var translated: (String) -> String = { "[t]$it" },
    var downloadOk: Boolean = true,
) : MlTranslatorEngine {
    val downloaded = mutableSetOf("en")
    var translateCalls = 0

    override suspend fun translate(text: String, sourceLang: String, targetLang: String): String {
        translateCalls++
        return translated(text)
    }

    override suspend fun ensureModel(lang: String, wifiOnly: Boolean): Boolean {
        if (downloadOk) downloaded += lang
        return downloadOk
    }

    override suspend fun deleteModel(lang: String): Boolean {
        downloaded -= lang
        return true
    }

    override suspend fun isDownloaded(lang: String): Boolean = lang in downloaded

    override fun supportedTags(): List<String> = listOf("en", "es", "fr", "de")

    override fun close() = Unit
}

/**
 * TranslationRepository (MASTER_PLAN §14): wraps ML Kit, protects format
 * placeholders, caches into UiTranslation/StoryTranslation, closes
 * translators with the lifecycle.
 */
class TranslationRepository(
    private val db: AppDatabase,
    private val engine: MlTranslatorEngine,
) : Closeable {

    companion object {
        private val PLACEHOLDER = Regex("%(\\d+\\$)?[sd]")
        private const val TOKEN_FMT = "__PPPH%d__"

        /** Swap %1$s/%d for protected tokens; returns text + token table. */
        fun protect(text: String): Pair<String, List<String>> {
            val found = mutableListOf<String>()
            val guarded = PLACEHOLDER.replace(text) { m ->
                found += m.value
                TOKEN_FMT.format(found.size - 1)
            }
            return guarded to found
        }

        /** Restore tokens; null when a token was mangled (caller keeps English). */
        fun unprotect(translated: String, table: List<String>): String? {
            var out = translated
            table.forEachIndexed { i, orig ->
                val token = TOKEN_FMT.format(i)
                if (!out.contains(token)) return null
                out = out.replace(token, orig)
            }
            return out
        }
    }

    fun supportedLanguages(): List<String> = engine.supportedTags()

    suspend fun ensureModel(lang: String, wifiOnly: Boolean): Boolean {
        if (lang == "en") return true
        return engine.ensureModel(lang, wifiOnly)
    }

    /** Translate one string with placeholder protection; English fallback on mangling. */
    suspend fun translateString(text: String, targetLang: String, sourceLang: String = "en"): String {
        if (targetLang == "en" || text.isBlank()) return text
        val (guarded, table) = protect(text)
        val out = try {
            engine.translate(guarded, sourceLang, targetLang)
        } catch (e: Exception) {
            return text
        }
        if (table.isEmpty()) return out
        return unprotect(out, table) ?: text
    }

    /** Bulk-translate UI strings once and cache into UiTranslation. */
    suspend fun translateUiStrings(
        strings: Map<String, String>,
        targetLang: String,
    ): Map<String, String> {
        if (targetLang == "en") return strings
        val rows = mutableListOf<UiTranslation>()
        val out = strings.mapValues { (key, english) ->
            db.translationDao().uiText(targetLang, key)?.let { return@mapValues it }
            val t = translateString(english, targetLang)
            rows += UiTranslation(targetLang, key, t)
            t
        }
        if (rows.isNotEmpty()) db.translationDao().upsertUiTranslations(rows)
        return out
    }

    /** Cached UI lookup with English fallback. */
    suspend fun tr(key: String, english: String, lang: String): String {
        if (lang == "en") return english
        return db.translationDao().uiText(lang, key) ?: english
    }

    suspend fun translateStory(
        storyId: String,
        version: Int,
        headline: String,
        dek: String?,
        body: String?,
        targetLang: String,
    ): StoryTranslation? {
        if (targetLang == "en") return null
        db.translationDao().storyTranslation(storyId, version, targetLang)?.let { return it }
        val row = StoryTranslation(
            storyId = storyId,
            version = version,
            lang = targetLang,
            headline = translateString(headline, targetLang),
            dek = dek?.let { translateString(it, targetLang) },
            body = body?.let { translateString(it, targetLang) },
        )
        db.translationDao().upsertStoryTranslations(listOf(row))
        db.storyDao().deleteFtsFor(storyId, targetLang)
        val sources = db.storyDao().sourcesFor(storyId)
        val publisher = sources.joinToString(" ") { it.publisher }
        val story = db.storyDao().storyById(storyId)
        val locations = story?.locationsJson?.let { json ->
            runCatching {
                NetworkModule.feedJson.decodeFromString<List<StoryLocationDto>>(json)
            }.getOrNull()?.joinToString(" ") {
                listOfNotNull(it.city, it.admin2, it.metro, it.admin1, it.country)
                    .joinToString(" ")
            }
        } ?: ""
        db.storyDao().upsertFts(
            listOf(
                StoryFts(
                    storyId = storyId,
                    headline = row.headline,
                    dek = row.dek ?: "",
                    body = row.body ?: "",
                    publisher = publisher,
                    locations = locations,
                    lang = targetLang,
                ),
            ),
        )
        return row
    }

    override fun close() = engine.close()
}
