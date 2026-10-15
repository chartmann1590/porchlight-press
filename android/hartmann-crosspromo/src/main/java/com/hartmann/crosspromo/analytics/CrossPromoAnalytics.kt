package com.hartmann.crosspromo.analytics

import android.content.Context
import android.os.Bundle
import com.hartmann.crosspromo.api.CrossPromoApi
import com.hartmann.crosspromo.model.PromoEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Pluggable analytics contract for cross-promotion performance tracking.
 */
interface CrossPromoAnalytics {
    fun logImpression(event: PromoEvent)
    fun logClick(event: PromoEvent)
    fun logInstall(event: PromoEvent)
}

/**
 * No-op implementation when analytics tracking is disabled or in unit tests.
 */
class NoOpAnalytics : CrossPromoAnalytics {
    override fun logImpression(event: PromoEvent) {}
    override fun logClick(event: PromoEvent) {}
    override fun logInstall(event: PromoEvent) {}
}

/**
 * Dispatches events to Hartmann Cross-Promotion backend asynchronously.
 */
class BackendAnalyticsAdapter(
    private val api: CrossPromoApi,
    private val baseUrl: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : CrossPromoAnalytics {

    override fun logImpression(event: PromoEvent) {
        scope.launch { api.sendEvent(baseUrl, event) }
    }

    override fun logClick(event: PromoEvent) {
        scope.launch { api.sendEvent(baseUrl, event) }
    }

    override fun logInstall(event: PromoEvent) {
        scope.launch { api.sendEvent(baseUrl, event) }
    }
}

/**
 * Safe Firebase Analytics adapter using reflection.
 * If Firebase is present in the host application, logs 'crosspromo_impression' and 'crosspromo_click'.
 * If Firebase is absent, gracefully no-ops without crashing.
 */
class FirebaseAnalyticsAdapter(context: Context) : CrossPromoAnalytics {
    private var firebaseInstance: Any? = null
    private var logEventMethod: java.lang.reflect.Method? = null

    init {
        try {
            val firebaseAnalyticsClass = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            val getInstanceMethod = firebaseAnalyticsClass.getMethod("getInstance", Context::class.java)
            firebaseInstance = getInstanceMethod.invoke(null, context.applicationContext)
            logEventMethod = firebaseAnalyticsClass.getMethod("logEvent", String::class.java, Bundle::class.java)
        } catch (_: Throwable) {
            // Firebase Analytics not present in host classpath; perfectly normal
            firebaseInstance = null
            logEventMethod = null
        }
    }

    override fun logImpression(event: PromoEvent) {
        sendFirebaseEvent("crosspromo_impression", event)
    }

    override fun logClick(event: PromoEvent) {
        sendFirebaseEvent("crosspromo_click", event)
    }

    override fun logInstall(event: PromoEvent) {
        sendFirebaseEvent("crosspromo_install", event)
    }

    private fun sendFirebaseEvent(eventName: String, event: PromoEvent) {
        val instance = firebaseInstance ?: return
        val method = logEventMethod ?: return

        try {
            val bundle = Bundle().apply {
                putString("source_package", event.sourcePackage)
                putString("target_package", event.targetPackage)
                putString("placement", event.placement)
                event.selectionType?.let { putString("selection_type", it) }
                event.rankPosition?.let { putInt("rank_position", it) }
                event.sessionId?.let { putString("session_id", it) }
            }
            method.invoke(instance, eventName, bundle)
        } catch (_: Throwable) {
            // Non-critical logging failure
        }
    }
}

/**
 * Combines multiple analytics adapters into a single multi-cast target.
 */
class CompositeAnalytics(
    private val adapters: List<CrossPromoAnalytics>
) : CrossPromoAnalytics {
    override fun logImpression(event: PromoEvent) {
        adapters.forEach { it.logImpression(event) }
    }

    override fun logClick(event: PromoEvent) {
        adapters.forEach { it.logClick(event) }
    }

    override fun logInstall(event: PromoEvent) {
        adapters.forEach { it.logInstall(event) }
    }
}
