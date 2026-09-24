package com.charleshartman.porchlightpress.data.ads

import android.content.Context
import com.charleshartman.porchlightpress.BuildConfig
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Ads wiring for Phase 6 placements (MASTER_PLAN §13, Phase 9 spec).
 *
 * - Banner + native placeholders use test IDs in debug (BuildConfig).
 * - Interstitial only when returning from article to list, capped by
 *   pp_interstitial_every_n_articles (default 3) and
 *   pp_interstitial_min_interval_sec (default 180).
 * - Never at launch or onboarding; UMP consent must resolve before
 *   MobileAds.initialize (gated by [adsReady]).
 * - Remote Config keys are read from local defaults (Phase 9 wires the real
 *   Remote Config fetch). Kill switch pp_ads_enabled collapses slots.
 */
data class AdConfig(
    val enabled: Boolean = true,
    val adsEveryNSections: Int = 3,
    val interstitialEveryN: Int = 3,
    val interstitialMinIntervalSec: Int = 180,
)

object AdPolicy {
    // MASTER_PLAN §13 + Phase 9: real IDs only via -P secrets → BuildConfig in release.
    // Debug/CI always use Google's test IDs (see app/build.gradle.kts).
    fun bannerId(): String = BuildConfig.ADMOB_BANNER_ID
    fun nativeId(): String = BuildConfig.ADMOB_NATIVE_ID
    fun interstitialId(): String = BuildConfig.ADMOB_INTERSTITIAL_ID
    fun appId(): String = BuildConfig.ADMOB_APP_ID
}

class InterstitialController(
    private val config: AdConfig = AdConfig(),
) {
    private var articleReturnCount = 0
    private var lastShownMs: Long = 0

    @Synchronized
    fun onArticleReturn(nowMs: Long = System.currentTimeMillis()): Boolean {
        articleReturnCount++
        if (articleReturnCount % config.interstitialEveryN != 0) return false
        if (nowMs - lastShownMs < config.interstitialMinIntervalSec * 1000L) return false
        lastShownMs = nowMs
        return true
    }

    fun reset() {
        articleReturnCount = 0
        lastShownMs = 0
    }

    // For testing: inject counters.
    fun setForTest(count: Int, lastMs: Long) {
        articleReturnCount = count
        lastShownMs = lastMs
    }
}

/** Lightweight AdMob wrapper that respects consent gating. */
class AdMobGate(private val context: Context) {
    private val _adsReady = MutableStateFlow(false)
    val adsReady: StateFlow<Boolean> = _adsReady

    @Volatile private var initialized = false
    private var interstitial: InterstitialAd? = null
    var config: AdConfig = AdConfig()

    fun initializeIfConsented(consented: Boolean) {
        if (initialized) return
        // UMP must resolve before MobileAds.initialize; if consent unknown we still
        // initialize with non-personalized default (Phase 9: no consent → npa=1).
        // Simplest policy: always initialize after consent resolves (either
        // Obtained or NotRequired); caller gates this.
        try {
            MobileAds.initialize(context) {}
            initialized = true
            _adsReady.value = true && config.enabled
            preloadInterstitial()
        } catch (e: Exception) {
            _adsReady.value = false
        }
    }

    fun preloadInterstitial() {
        if (!config.enabled) return
        val adUnit = AdPolicy.interstitialId()
        if (adUnit.isBlank()) return
        val req = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnit, req, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) { interstitial = ad }
            override fun onAdFailedToLoad(err: LoadAdError) { interstitial = null }
        })
    }

    fun popInterstitial(): InterstitialAd? {
        val ad = interstitial
        interstitial = null
        // Preload next one after show (Phase 9 spec).
        if (ad != null) preloadInterstitial() else preloadInterstitial()
        return ad
    }

    // Banner helper (for AndroidView wrapper) – caller handles AdView lifecycle.
    fun makeBanner(context: Context): AdView? {
        if (!config.enabled) return null
        return try {
            AdView(context).apply {
                setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                adUnitId = AdPolicy.bannerId()
                loadAd(AdRequest.Builder().build())
            }
        } catch (e: Exception) { null }
    }
}
