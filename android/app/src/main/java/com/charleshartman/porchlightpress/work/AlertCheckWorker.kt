package com.charleshartman.porchlightpress.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.charleshartman.porchlightpress.PorchlightApp
import com.charleshartman.porchlightpress.data.local.NotifiedAlert
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.data.weather.WeatherRepository
import com.charleshartman.porchlightpress.data.weather.isWarningLevel
import java.util.concurrent.TimeUnit

/**
 * Pure decision logic for the severe-weather worker (unit-tested):
 * Warning-level events whose ID has not been notified yet.
 */
object AlertCheckLogic {
    fun newWarnings(alerts: List<WeatherAlert>, knownIds: Set<String>): List<WeatherAlert> =
        alerts.filter { it.isWarningLevel() && it.id !in knownIds }
}

/**
 * Fetches NWS alerts for the home bucket only (15-min periodic, requires
 * network + battery-not-low) and notifies for Warning-level events not yet
 * notified (dedupe by alert ID in Room). Never throws: a bad run logs and
 * succeeds so the periodic schedule survives.
 */
class AlertCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? PorchlightApp ?: return Result.success()
        val container = app.container
        return try {
            val prefs = container.prefs.snapshot()
            if (!prefs.notifySevere) return Result.success()
            val locationId = prefs.activeLocationId ?: return Result.success()
            val loc = container.db.savedLocationDao().byId(locationId) ?: return Result.success()
            val place = com.charleshartman.porchlightpress.domain.Place(
                loc.id, loc.label, loc.country, loc.admin1, loc.admin2, loc.city,
                loc.metro, loc.lat, loc.lon, loc.tz,
            )
            when (val res = container.weatherRepository.alertsFor(place)) {
                is WeatherRepository.AlertsResult.Alerts -> {
                    val fresh = AlertCheckLogic.newWarnings(res.alerts, knownIds(container, res.alerts))
                    android.util.Log.i(
                        "Porchlight",
                        "Alert check: ${res.alerts.size} active, ${fresh.size} new warnings (stale=${res.stale})",
                    )
                    val lang = prefs.appLanguage
                    for (alert in fresh) {
                        val (eventT, headT) = translateForNotify(container, alert, lang)
                        AlertNotifications.showWarning(applicationContext, alert, eventT, headT)
                    }
                    if (fresh.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        container.db.notifiedAlertDao().insertAll(
                            fresh.map { NotifiedAlert(it.id, now, it.event) },
                        )
                    }
                    // Prune rows older than 30 days so the table stays small.
                    runCatching {
                        container.db.notifiedAlertDao()
                            .pruneOlderThan(System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L)
                    }
                }
                WeatherRepository.AlertsResult.None,
                WeatherRepository.AlertsResult.Unavailable,
                -> Unit
            }
            Result.success()
        } catch (e: Exception) {
            android.util.Log.w("Porchlight", "Alert check failed", e)
            Result.success()
        }
    }

    private suspend fun knownIds(
        container: com.charleshartman.porchlightpress.AppContainer,
        alerts: List<WeatherAlert>,
    ): Set<String> {
        if (alerts.isEmpty()) return emptySet()
        return runCatching {
            container.db.notifiedAlertDao().knownIds(alerts.map { it.id }).toSet()
        }.getOrDefault(emptySet())
    }

    /** Translate event + headline for the notification; nulls = English. */
    private suspend fun translateForNotify(
        container: com.charleshartman.porchlightpress.AppContainer,
        alert: WeatherAlert,
        lang: String,
    ): Pair<String?, String?> {
        if (lang == "en") return null to null
        return try {
            val repo = container.translationRepository
            val e = repo.translateString(alert.event, lang).takeIf { it != alert.event }
            val h = repo.translateString(alert.headline.ifBlank { alert.event }, lang)
                .takeIf { it != alert.headline }
            e to h
        } catch (e: Exception) {
            null to null
        }
    }
}

/** Schedule / cancel the periodic alert check from the notifySevere toggle. */
object AlertScheduler {
    const val WORK_NAME = "alert-check"

    fun ensure(context: Context, enabled: Boolean) {
        android.util.Log.i("Porchlight", "AlertScheduler.ensure enabled=$enabled")
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        val req = PeriodicWorkRequestBuilder<AlertCheckWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .build()
        wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }
}
