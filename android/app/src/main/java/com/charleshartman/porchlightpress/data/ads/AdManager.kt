package com.charleshartman.porchlightpress.data.ads

import android.content.Context
import com.charleshartman.porchlightpress.BuildConfig
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
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
    var config: AdConfig = AdConfig(),
) {
    private var articleReturnCount = 0
    private var lastShownMs: Long? = null

    @Synchronized
    fun onArticleReturn(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!config.enabled) return false
        articleReturnCount++
        if (articleReturnCount % config.interstitialEveryN.coerceAtLeast(1) != 0) return false
        val last = lastShownMs
        if (last != null && nowMs - last < config.interstitialMinIntervalSec * 1000L) return false
        lastShownMs = nowMs
        return true
    }

    fun reset() {
        articleReturnCount = 0
        lastShownMs = null
    }

    // For testing: inject counters.
    fun setForTest(count: Int, lastMs: Long?) {
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
        if (!consented) {
            _adsReady.value = false
            interstitial = null
            return
        }
        // MobileAds.initialize is idempotent, but the ready flag and preload
        // must follow the current config: ads can flip from disabled (no
        // consent yet) to enabled later, so never get stuck by an early call.
        if (initialized) {
            _adsReady.value = config.enabled
            if (config.enabled) preloadInterstitial()
            return
        }
        // UMP must resolve before MobileAds.initialize; if consent unknown we still
        // initialize with non-personalized default (Phase 9: no consent → npa=1).
        // Simplest policy: always initialize after consent resolves (either
        // Obtained or NotRequired); caller gates this.
        try {
            MobileAds.initialize(context) {}
            initialized = true
            _adsReady.value = config.enabled
            preloadInterstitial()
        } catch (e: Exception) {
            android.util.Log.w("Porchlight", "MobileAds.initialize failed; ads disabled", e)
            _adsReady.value = false
        }
    }

    fun preloadInterstitial() {
        if (!config.enabled || !_adsReady.value) return
        val adUnit = AdPolicy.interstitialId()
        if (adUnit.isBlank()) return
        val req = AdRequest.Builder().build()
        InterstitialAd.load(context, adUnit, req, object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                android.util.Log.i("Porchlight", "Interstitial ad loaded")
                interstitial = ad
            }
            override fun onAdFailedToLoad(err: LoadAdError) {
                android.util.Log.i("Porchlight", "Interstitial ad failed: ${err.code} ${err.message}")
                interstitial = null
            }
        })
    }

    fun popInterstitial(): InterstitialAd? {
        if (!config.enabled || !_adsReady.value) return null
        val ad = interstitial
        interstitial = null
        // Preload the next one after each show (Phase 9 spec).
        preloadInterstitial()
        return ad
    }

    // Banner helper (for AndroidView wrapper) – caller handles AdView lifecycle.
    fun makeBanner(context: Context): AdView? {
        if (!config.enabled || !_adsReady.value) return null
        return try {
            AdView(context).apply {
                setAdSize(com.google.android.gms.ads.AdSize.BANNER)
                adUnitId = AdPolicy.bannerId()
                adListener = object : AdListener() {
                    override fun onAdLoaded() {
                        android.util.Log.i("Porchlight", "Banner ad loaded")
                    }

                    override fun onAdFailedToLoad(err: LoadAdError) {
                        android.util.Log.i("Porchlight", "Banner ad failed: ${err.code} ${err.message}")
                    }
                }
                loadAd(AdRequest.Builder().build())
            }
        } catch (e: Exception) { null }
    }

    /**
     * Load one native ad (Google test ID in debug, real unit from CI secrets
     * in release). Must be called on the main thread. The caller owns the
     * returned ad and must destroy() it when done.
     */
    fun loadNative(onLoaded: (NativeAd) -> Unit, onFailed: () -> Unit = {}) {
        if (!config.enabled || !_adsReady.value) {
            onFailed()
            return
        }
        val adUnit = AdPolicy.nativeId()
        if (adUnit.isBlank()) {
            onFailed()
            return
        }
        try {
            AdLoader.Builder(context, adUnit)
                .forNativeAd { ad ->
                    android.util.Log.i(
                        "Porchlight",
                        "Native ad loaded: headline=${ad.headline != null} " +
                            "body=${ad.body != null} icon=${ad.icon != null} " +
                            "cta=${ad.callToAction != null} media=${ad.mediaContent != null}",
                    )
                    onLoaded(ad)
                }
                .withAdListener(object : AdListener() {
                    override fun onAdFailedToLoad(err: LoadAdError) {
                        android.util.Log.i("Porchlight", "Native ad failed: ${err.code} ${err.message}")
                        onFailed()
                    }
                })
                .build()
                .loadAd(AdRequest.Builder().build())
        } catch (e: Exception) {
            android.util.Log.w("Porchlight", "Native ad load threw", e)
            onFailed()
        }
    }
}
