package com.charleshartman.porchlightpress.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.charleshartman.porchlightpress.AppContainer
import kotlinx.coroutines.runBlocking

/**
 * Translation wrapper: every user-visible string goes through tr().
 * In Compose, use [rememberTr] to obtain a suspend-safe lookup.
 * For string resources that are cached in Room UiTranslation, fall back
 * to English when a placeholder is mangled or translation missing.
 *
 * Simplest option consistent with MASTER_PLAN §14: English strings.xml only;
 * when appLanguage != en, cache lookups via TranslationRepository.tr().
 * Composables should pass (key, English) pairs; tr() returns translated.
 */
@Composable
fun rememberTr(container: AppContainer): (String, String) -> String {
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val lang = prefs?.appLanguage ?: "en"
    // Local cache: we read synchronously from Room via a blocking call
    // for Compose convenience; Room's query is fast (local). Alternative
    // would be Flow but this keeps call sites simple: tr("key","English").
    return remember(lang) {
        { key: String, english: String ->
            if (lang == "en") english else runCatching {
                runBlocking { container.translationRepository.tr(key, english, lang) }
            }.getOrDefault(english)
        }
    }
}

/** Non-Compose helper for tests/workers: synchronous tr via blocking. */
fun trSync(container: AppContainer, key: String, english: String): String {
    val lang = runCatching { runBlocking { container.prefs.snapshot().appLanguage } }.getOrDefault("en")
    if (lang == "en") return english
    return runCatching { runBlocking { container.translationRepository.tr(key, english, lang) } }.getOrDefault(english)
}
