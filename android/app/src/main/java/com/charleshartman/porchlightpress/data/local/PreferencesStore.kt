package com.charleshartman.porchlightpress.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.prefsStore: DataStore<Preferences> by preferencesDataStore(name = "porchlight_prefs")

/** All user preferences. Analytics/crash consent default OFF (opt-in). */
data class AppPrefs(
    val onboardingDone: Boolean = false,
    val readingStarted: Boolean = false,
    /** ML Kit language tag, default "en". */
    val appLanguage: String = "en",
    val activeLocationId: String? = null,
    val interests: Set<String> = emptySet(),
    val theme: String = "system",
    val layout: String = "classic",
    val textScale: Double = 1.0,
    val clockFormat: String = "system",
    val notifySevere: Boolean = false,
    val notifyBreaking: Boolean = false,
    val notifyEditions: Boolean = false,
    val analyticsConsent: Boolean = false,
    val crashConsent: Boolean = false,
)

class PreferencesStore(private val context: Context) {
    private object Keys {
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val READING_STARTED = booleanPreferencesKey("reading_started")
        val APP_LANGUAGE = stringPreferencesKey("app_language")
        val ACTIVE_LOCATION = stringPreferencesKey("active_location_id")
        val INTERESTS = stringSetPreferencesKey("interests")
        val THEME = stringPreferencesKey("theme")
        val LAYOUT = stringPreferencesKey("layout")
        val TEXT_SCALE = doublePreferencesKey("text_scale")
        val CLOCK_FORMAT = stringPreferencesKey("clock_format")
        val NOTIFY_SEVERE = booleanPreferencesKey("notify_severe")
        val NOTIFY_BREAKING = booleanPreferencesKey("notify_breaking")
        val NOTIFY_EDITIONS = booleanPreferencesKey("notify_editions")
        val ANALYTICS = booleanPreferencesKey("analytics_consent")
        val CRASH = booleanPreferencesKey("crash_consent")
    }

    val prefs: Flow<AppPrefs> = context.prefsStore.data.map { p ->
        AppPrefs(
            onboardingDone = p[Keys.ONBOARDING_DONE] ?: false,
            readingStarted = p[Keys.READING_STARTED] ?: false,
            appLanguage = p[Keys.APP_LANGUAGE] ?: "en",
            activeLocationId = p[Keys.ACTIVE_LOCATION],
            interests = p[Keys.INTERESTS] ?: emptySet(),
            theme = p[Keys.THEME] ?: "system",
            layout = p[Keys.LAYOUT] ?: "classic",
            textScale = p[Keys.TEXT_SCALE] ?: 1.0,
            clockFormat = p[Keys.CLOCK_FORMAT] ?: "system",
            notifySevere = p[Keys.NOTIFY_SEVERE] ?: false,
            notifyBreaking = p[Keys.NOTIFY_BREAKING] ?: false,
            notifyEditions = p[Keys.NOTIFY_EDITIONS] ?: false,
            analyticsConsent = p[Keys.ANALYTICS] ?: false,
            crashConsent = p[Keys.CRASH] ?: false,
        )
    }

    suspend fun snapshot(): AppPrefs = prefs.first()

    suspend fun setOnboardingDone(done: Boolean) = edit { it[Keys.ONBOARDING_DONE] = done }
    suspend fun setReadingStarted(started: Boolean) = edit { it[Keys.READING_STARTED] = started }
    suspend fun setAppLanguage(lang: String) = edit { it[Keys.APP_LANGUAGE] = lang }
    suspend fun setActiveLocationId(id: String?) = edit {
        if (id == null) it.remove(Keys.ACTIVE_LOCATION) else it[Keys.ACTIVE_LOCATION] = id
    }
    suspend fun setInterests(ids: Set<String>) = edit { it[Keys.INTERESTS] = ids }
    suspend fun setTheme(theme: String) = edit { it[Keys.THEME] = theme }
    suspend fun setLayout(layout: String) = edit { it[Keys.LAYOUT] = layout }
    suspend fun setTextScale(scale: Double) = edit { it[Keys.TEXT_SCALE] = scale }
    suspend fun setClockFormat(format: String) = edit { it[Keys.CLOCK_FORMAT] = format }
    suspend fun setNotifySevere(on: Boolean) = edit { it[Keys.NOTIFY_SEVERE] = on }
    suspend fun setNotifyBreaking(on: Boolean) = edit { it[Keys.NOTIFY_BREAKING] = on }
    suspend fun setNotifyEditions(on: Boolean) = edit { it[Keys.NOTIFY_EDITIONS] = on }
    suspend fun setAnalyticsConsent(on: Boolean) = edit { it[Keys.ANALYTICS] = on }
    suspend fun setCrashConsent(on: Boolean) = edit { it[Keys.CRASH] = on }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.prefsStore.edit(block)
    }
}
