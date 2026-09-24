package com.charleshartman.porchlightpress.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.charleshartman.porchlightpress.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Translation wrapper: every user-visible string goes through tr().
 * In Compose, use [rememberTr] to obtain a lookup that never blocks the
 * main thread: English returns synchronously, and cached translations swap
 * in via state once the background Room read completes.
 *
 * Simplest option consistent with MASTER_PLAN §14: English strings.xml only;
 * when appLanguage != en, cache lookups via TranslationRepository.tr().
 * Composables should pass (key, English) pairs; tr() returns translated.
 */
@Composable
fun rememberTr(container: AppContainer): (String, String) -> String {
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val lang = prefs?.appLanguage ?: "en"
    val scope = rememberCoroutineScope()
    val cache = remember(lang) { mutableStateMapOf<String, String>() }
    val lookup: (String, String) -> String = remember(lang) {
        val inFlight = mutableSetOf<String>()
        val fn: (String, String) -> String = { key, english ->
            if (lang == "en") {
                english
            } else {
                val cached = cache[key]
                if (cached != null) {
                    cached
                } else {
                    if (inFlight.add(key)) {
                        scope.launch {
                            val t = runCatching {
                                container.translationRepository.tr(key, english, lang)
                            }.getOrDefault(english)
                            // Cache even English-equal results so we don't relaunch.
                            cache[key] = t
                        }
                    }
                    english
                }
            }
        }
        fn
    }
    return lookup
}

/**
 * Non-Compose helper for tests/workers. Blocks the calling thread, so call
 * only from background threads — never from a Composable or the main thread.
 */
fun trSync(container: AppContainer, key: String, english: String): String {
    val lang = runCatching { runBlocking { container.prefs.snapshot().appLanguage } }.getOrDefault("en")
    if (lang == "en") return english
    return runCatching { runBlocking { container.translationRepository.tr(key, english, lang) } }.getOrDefault(english)
}
