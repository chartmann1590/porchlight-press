package com.charleshartman.porchlightpress.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Retries a failed ML Kit translation-model download in the background
 * (onboarding continues in English meanwhile, with a progress banner).
 */
class TranslationModelWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val lang = inputData.getString(KEY_LANG) ?: return Result.failure()
        val wifiOnly = inputData.getBoolean(KEY_WIFI_ONLY, false)
        val app = applicationContext as? com.charleshartman.porchlightpress.PorchlightApp
            ?: return Result.failure()
        val ok = app.container.translationRepository.ensureModel(lang, wifiOnly)
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        const val KEY_LANG = "lang"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
