package com.charleshartman.porchlightpress.data

import android.content.Context
import android.os.Bundle
import com.charleshartman.porchlightpress.R
import com.charleshartman.porchlightpress.data.ads.AdConfig
import com.charleshartman.porchlightpress.data.local.AppPrefs
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.perf.FirebasePerformance
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Optional Firebase: a checkout without configuration uses local defaults only. */
class OptionalServices(private val context: Context) {
    private val configured = runCatching { FirebaseApp.getApps(context).isNotEmpty() }.getOrDefault(false)
    private val _ads = MutableStateFlow(AdConfig())
    val ads: StateFlow<AdConfig> = _ads
    private var analyticsAllowed = false

    fun applyConsent(prefs: AppPrefs) {
        analyticsAllowed = prefs.analyticsConsent
        if (!configured) return
        runCatching {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(prefs.analyticsConsent)
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(prefs.crashConsent)
            if (!prefs.crashConsent) FirebaseCrashlytics.getInstance().deleteUnsentReports()
            FirebasePerformance.getInstance().isPerformanceCollectionEnabled = prefs.crashConsent
        }
    }

    /** Only fixed route names: no story IDs, locations, searches, or URLs. */
    fun screen(route: String) {
        if (!configured || !analyticsAllowed) return
        val name = route.substringBefore('/')
        if (name !in setOf("front", "section", "article", "weather", "saved", "search", "settings", "about", "papers", "pdf", "alert")) return
        runCatching {
            FirebaseAnalytics.getInstance(context).logEvent(FirebaseAnalytics.Event.SCREEN_VIEW,
                Bundle().apply { putString(FirebaseAnalytics.Param.SCREEN_NAME, name) })
        }
    }

    fun refreshConfig() {
        if (!configured) return
        runCatching {
            val config = FirebaseRemoteConfig.getInstance()
            config.setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder()
                .setMinimumFetchIntervalInSeconds(43_200).build())
            config.setDefaultsAsync(R.xml.remote_config_defaults).addOnCompleteListener {
                config.fetchAndActivate().addOnCompleteListener {
                    _ads.value = AdConfig(
                        enabled = config.getBoolean("pp_ads_enabled"),
                        adsEveryNSections = config.getLong("pp_ads_every_n_sections").toInt().coerceIn(1, 20),
                        interstitialEveryN = config.getLong("pp_interstitial_every_n_articles").toInt().coerceIn(3, 100),
                        interstitialMinIntervalSec = config.getLong("pp_interstitial_min_interval_sec").toInt().coerceIn(180, 86400),
                    )
                }
            }
        }
    }
}
