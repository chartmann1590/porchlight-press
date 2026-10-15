package com.hartmann.crosspromo

import android.content.Context
import com.hartmann.crosspromo.analytics.BackendAnalyticsAdapter
import com.hartmann.crosspromo.analytics.CompositeAnalytics
import com.hartmann.crosspromo.analytics.CrossPromoAnalytics
import com.hartmann.crosspromo.analytics.FirebaseAnalyticsAdapter
import com.hartmann.crosspromo.analytics.NoOpAnalytics
import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.attribution.HartmannInstallAttribution
import com.hartmann.crosspromo.cache.PromoCache
import com.hartmann.crosspromo.launcher.PlayStoreLauncher
import com.hartmann.crosspromo.model.PromoApp
import com.hartmann.crosspromo.model.PromoEvent
import com.hartmann.crosspromo.repository.CrossPromoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Main entry point for Hartmann Studios Dynamic Cross-Promotion SDK.
 * Minimal configuration required; automatically detects host package name.
 */
object HartmannCrossPromo {

    private const val DEFAULT_API_BASE_URL = "https://hartmann-crosspromo.charles-h-hartmann1.workers.dev"
    private const val OPT_OUT_PREFS = "hartmann_crosspromo_flags"
    private const val OPT_OUT_KEY = "user_opted_out"

    @Volatile
    private var initialized = false
    private lateinit var appContext: Context
    private lateinit var sourcePackage: String
    private lateinit var cache: PromoCache
    private lateinit var api: CrossPromoApi
    private lateinit var repository: CrossPromoRepository
    private var analytics: CrossPromoAnalytics = NoOpAnalytics()

    /**
     * Host-controlled kill switch. When false, components render nothing and
     * no network calls are made. Defaults to true after [initialize].
     */
    @Volatile
    var enabled: Boolean = false
        private set

    // Deduplication set for impressions in current session: "<placement>::<package>"
    private val sessionImpressionSet = mutableSetOf<String>()

    /**
     * Initializes the Cross-Promotion SDK.
     * Safe to call multiple times; subsequent calls are ignored.
     */
    fun initialize(
        context: Context,
        apiBaseUrl: String = DEFAULT_API_BASE_URL,
        customAnalytics: CrossPromoAnalytics? = null,
        enableInstallReferrerCheck: Boolean = true
    ) {
        if (initialized) return

        appContext = context.applicationContext
        sourcePackage = appContext.packageName
        cache = PromoCache(appContext)
        api = CrossPromoApi()
        repository = CrossPromoRepository(api, cache, apiBaseUrl)

        // Assemble analytics: Backend + Firebase (safe reflection) + Custom
        val adapters = mutableListOf<CrossPromoAnalytics>()
        adapters.add(BackendAnalyticsAdapter(api, apiBaseUrl))
        adapters.add(FirebaseAnalyticsAdapter(appContext))
        customAnalytics?.let { adapters.add(it) }
        analytics = CompositeAnalytics(adapters)

        // Optional first-launch install referrer tracking
        if (enableInstallReferrerCheck) {
            HartmannInstallAttribution.checkInstallReferrer(appContext, analytics)
        }

        // Restore a previously persisted user opt-out across launches.
        enabled = !appContext.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(OPT_OUT_KEY, false)
        initialized = true
    }

    /**
     * User opt-out. When set, promo components render nothing, no network
     * requests fire, and the choice persists across launches until changed.
     */
    fun setUserOptOut(optedOut: Boolean) {
        check(initialized) { "HartmannCrossPromo.initialize() must be called first" }
        appContext.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OPT_OUT_KEY, optedOut)
            .apply()
        enabled = !optedOut
    }

    /**
     * Whether the user has opted out of cross-promotions.
     */
    fun isUserOptedOut(): Boolean {
        if (!initialized) return false
        return appContext.getSharedPreferences(OPT_OUT_PREFS, Context.MODE_PRIVATE)
            .getBoolean(OPT_OUT_KEY, false)
    }

    /** Test-visible accessor for the session cache. */
    internal fun cacheForTesting(): PromoCache? = if (initialized) cache else null

    /** Test-only: clears singleton state so unit tests are isolated. */
    internal fun resetForTesting() {
        initialized = false
        enabled = false
        synchronized(sessionImpressionSet) { sessionImpressionSet.clear() }
    }

    /**
     * Retrieves stream of recommended applications.
     * Returns an empty flow when the SDK is disabled or the user opted out.
     */
    fun getRecommendations(
        placement: String = "home",
        limit: Int = 3
    ): Flow<List<PromoApp>> {
        if (!initialized || !enabled) {
            return emptyFlow()
        }
        return repository.getRecommendations(
            sourcePackage = sourcePackage,
            placement = placement,
            limit = limit
        )
    }

    /**
     * Handles card click: logs event and opens Google Play safely.
     */
    fun onAppClicked(
        context: Context,
        app: PromoApp,
        placement: String
    ) {
        if (!initialized || !enabled) return

        val event = PromoEvent(
            event = "promo_click",
            sourcePackage = sourcePackage,
            targetPackage = app.packageName,
            placement = placement,
            rankPosition = app.rankPosition,
            selectionType = app.selectionType,
            sessionId = cache.sessionId
        )

        analytics.logClick(event)
        PlayStoreLauncher.openPlayStore(context, app, sourcePackage)
    }

    /**
     * Handles card impression: logs event once per session per (placement, card).
     */
    fun onAppImpression(
        app: PromoApp,
        placement: String
    ) {
        if (!initialized || !enabled) return

        val dedupeKey = "${placement}::${app.packageName}"
        val isFirstTimeInSession: Boolean
        synchronized(sessionImpressionSet) {
            isFirstTimeInSession = sessionImpressionSet.add(dedupeKey)
        }

        if (isFirstTimeInSession) {
            cache.markShownInSession(placement, app.packageName)
            val event = PromoEvent(
                event = "promo_impression",
                sourcePackage = sourcePackage,
                targetPackage = app.packageName,
                placement = placement,
                rankPosition = app.rankPosition,
                selectionType = app.selectionType,
                sessionId = cache.sessionId
            )
            analytics.logImpression(event)
        }
    }
}
