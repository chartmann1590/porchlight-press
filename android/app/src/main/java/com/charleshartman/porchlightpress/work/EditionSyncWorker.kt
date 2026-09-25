package com.charleshartman.porchlightpress.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import coil.imageLoader
import coil.request.ImageRequest
import com.charleshartman.porchlightpress.MainActivity
import com.charleshartman.porchlightpress.PorchlightApp
import com.charleshartman.porchlightpress.data.local.SavedLocation
import com.charleshartman.porchlightpress.data.remote.StoryImageDto
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.domain.FeedResult
import com.charleshartman.porchlightpress.domain.Place
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Rules stay pure so quiet hours and the daily cap can be verified without Android. */
object NewsNotificationPolicy {
    fun inQuietHours(hour: Int, start: Int, end: Int): Boolean =
        if (start == end) false else if (start < end) hour in start until end
        else hour >= start || hour < end

    fun breakingCandidates(
        stories: List<com.charleshartman.porchlightpress.data.remote.StoryDto>,
        interests: Set<String>,
        known: Set<String>,
        quota: Int,
    ): List<com.charleshartman.porchlightpress.data.remote.StoryDto> =
        stories.filter { it.breaking && it.id !in known &&
            (interests.isEmpty() || it.category in interests) }.take(quota.coerceAtLeast(0))
}

/** Downloads followed papers and alerts while preserving offline copies. */
class EditionSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? PorchlightApp ?: return Result.success()
        val c = app.container
        val prefs = c.prefs.snapshot()
        val rows = c.db.savedLocationDao().all()
        var failed = false
        for (loc in rows) {
            val place = loc.toPlace()
            try {
                val latest = c.editionRepository.sync(place, api = c.feedApi)
                if (latest is FeedResult.Ok) {
                    val content = c.editionRepository.cachedContent(loc.id)
                    if (content != null) {
                        val stories = content.sections.flatMap { it.second }.distinctBy { it.id }
                        if (prefs.appLanguage != "en") {
                            for (story in stories) {
                                runCatching {
                                    c.translationRepository.translateStoryOnce(
                                        story.id, story.version, story.headline, story.dek,
                                        story.body, prefs.appLanguage)
                                }
                            }
                        }
                        if (!prefs.dataSaver && isUnmetered()) {
                            val heroes = content.sections.mapNotNull { it.second.firstOrNull() }
                            withContext(Dispatchers.IO) {
                                heroes.take(8).forEach { story ->
                                    val url = story.imageJson?.let {
                                        runCatching { NetworkModule.feedJson.decodeFromString<StoryImageDto>(it).url }
                                            .getOrNull()
                                    }?.takeIf { it.startsWith("https://") }
                                    if (url != null) runCatching {
                                        applicationContext.imageLoader.execute(
                                            ImageRequest.Builder(applicationContext).data(url).build())
                                    }
                                }
                            }
                        }
                    }
                    val path = latest.value.feedPath
                    if (path.endsWith("latest.json")) {
                        checkBreaking(c, loc, path.removeSuffix("latest.json") + "breaking.json", prefs)
                    }
                    checkEditionReady(c, loc, latest.value.editionId, latest.value.generatedAt, prefs)
                } else if (latest is FeedResult.Error) failed = true
            } catch (e: Exception) {
                android.util.Log.w("Porchlight", "Edition sync failed for " + loc.id, e)
                failed = true
            }
        }
        val now = System.currentTimeMillis()
        runCatching {
            c.db.editionDao().deleteOlderThan(now - 3L * 24 * 60 * 60 * 1000)
            c.db.storyDao().deleteExpiredUnsaved(
                Instant.ofEpochMilli(now - 7L * 24 * 60 * 60 * 1000).toString())
            c.db.weatherDao().deleteExpired(now)
            c.db.storyDao().pruneOrphanFts()
            c.db.storyDao().pruneOrphanSources()
            c.db.storyDao().pruneOrphanTranslations()
        }
        applicationContext.getSharedPreferences("edition_sync", Context.MODE_PRIVATE)
            .edit().putLong("last_check", now).apply()
        return if (failed) Result.retry() else Result.success()
    }

    private fun isUnmetered(): Boolean {
        val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return !cm.isActiveNetworkMetered
    }

    private suspend fun checkBreaking(
        c: com.charleshartman.porchlightpress.AppContainer,
        loc: SavedLocation,
        path: String,
        prefs: com.charleshartman.porchlightpress.data.local.AppPrefs,
    ) {
        if (!prefs.notifyBreaking) return
        val hour = LocalTime.now(runCatching { ZoneId.of(loc.tz ?: "") }
            .getOrDefault(ZoneId.systemDefault())).hour
        if (NewsNotificationPolicy.inQuietHours(hour, prefs.quietStartHour, prefs.quietEndHour)) return
        val response = runCatching { c.feedApi.edition(path) }.getOrNull()
        val feed = response?.body()?.takeIf { response.isSuccessful } ?: return
        val state = applicationContext.getSharedPreferences("news_notifications", Context.MODE_PRIVATE)
        val day = java.time.LocalDate.now().toString()
        val count = if (state.getString("day", "") == day) state.getInt("count", 0) else 0
        val known = state.getStringSet("known", emptySet()) ?: emptySet()
        val selected = NewsNotificationPolicy.breakingCandidates(feed.stories, prefs.interests,
            known, 3 - count)
        if (selected.isEmpty()) return
        NewsNotifications.ensureChannels(applicationContext)
        selected.forEach { story ->
            NewsNotifications.show(applicationContext, NewsNotifications.BREAKING,
                story.headline, loc.label, story.id)
        }
        state.edit().putString("day", day).putInt("count", count + selected.size)
            .putStringSet("known", (known + selected.map { it.id }).toList().takeLast(500).toSet()).apply()
    }

    private fun checkEditionReady(
        c: com.charleshartman.porchlightpress.AppContainer,
        loc: SavedLocation,
        id: String,
        generatedAt: String,
        prefs: com.charleshartman.porchlightpress.data.local.AppPrefs,
    ) {
        val state = applicationContext.getSharedPreferences("news_notifications", Context.MODE_PRIVATE)
        val key = "edition_" + loc.id
        val previous = state.getString(key, null)
        state.edit().putString(key, id).apply()
        if (previous == null || previous == id) return
        val hour = runCatching { Instant.parse(generatedAt).atZone(ZoneId.of(loc.tz ?: "")).hour }
            .getOrDefault(LocalTime.now().hour)
        val morning = hour < 15
        if (!(if (morning) prefs.notifyMorning else prefs.notifyEvening)) return
        val nowHour = LocalTime.now(runCatching { ZoneId.of(loc.tz ?: "") }
            .getOrDefault(ZoneId.systemDefault())).hour
        if (NewsNotificationPolicy.inQuietHours(nowHour, prefs.quietStartHour, prefs.quietEndHour)) return
        NewsNotifications.show(applicationContext, NewsNotifications.EDITION,
            if (morning) "Your morning paper is ready" else "Your evening paper is ready",
            loc.label, null)
    }

    private fun SavedLocation.toPlace() =
        Place(id, label, country, admin1, admin2, city, metro, lat, lon, tz)
}

object EditionSyncScheduler {
    private const val PERIODIC = "edition-sync-periodic"
    private const val OPEN = "edition-sync-open"
    fun ensure(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<EditionSyncWorker>(3, TimeUnit.HOURS)
                .setConstraints(constraints).build())
        val state = context.getSharedPreferences("edition_sync", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - state.getLong("last_open_enqueue", 0L) > 60L * 60L * 1000L &&
            now - state.getLong("last_check", 0L) > 60L * 60L * 1000L) {
            state.edit().putLong("last_open_enqueue", now).apply()
            WorkManager.getInstance(context).enqueueUniqueWork(
                OPEN, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<EditionSyncWorker>()
                    .setConstraints(constraints).build())
        }
    }
}

object NewsNotifications {
    const val BREAKING = "breaking_local"
    const val EDITION = "edition_ready"
    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(BREAKING) == null)
            manager.createNotificationChannel(NotificationChannel(BREAKING,
                "Breaking local news", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(EDITION) == null)
            manager.createNotificationChannel(NotificationChannel(EDITION,
                "Edition ready", NotificationManager.IMPORTANCE_LOW))
    }
    fun show(context: Context, channel: String, title: String, text: String, storyId: String?) {
        if (!AlertNotifications.canPost(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (storyId != null) putExtra("open_story_id", storyId)
        }
        val id = (channel + (storyId ?: text)).hashCode() and Int.MAX_VALUE
        val pending = PendingIntent.getActivity(context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(pending).build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}


