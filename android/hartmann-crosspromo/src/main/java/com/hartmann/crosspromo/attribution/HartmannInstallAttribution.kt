package com.hartmann.crosspromo.attribution

import android.content.Context
import android.net.Uri
import com.hartmann.crosspromo.analytics.CrossPromoAnalytics
import com.hartmann.crosspromo.model.PromoEvent

/**
 * Optional Google Play Install Referrer attribution component.
 * Executes on first launch of the target application to identify installs
 * that originated from a Hartmann Studios cross-promotion card.
 */
object HartmannInstallAttribution {

    private const val PREFS_NAME = "hartmann_crosspromo_attribution"
    private const val KEY_CHECKED = "attribution_checked"

    /**
     * Attempts to read and parse install referrer attribution on first launch.
     */
    fun checkInstallReferrer(
        context: Context,
        analytics: CrossPromoAnalytics,
        onAttributionFound: ((sourcePackage: String, campaign: String) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CHECKED, false)) {
            return // Already checked on previous launch
        }

        // Mark checked to prevent duplicate install logging
        prefs.edit().putBoolean(KEY_CHECKED, true).apply()

        // Inspect referrer via Google Play Install Referrer API (if library present)
        try {
            val clientClass = Class.forName("com.android.installreferrer.api.InstallReferrerClient")
            val builderMethod = clientClass.getMethod("newBuilder", Context::class.java)
            val builder = builderMethod.invoke(null, context.applicationContext)
            val buildMethod = builder.javaClass.getMethod("build")
            val client = buildMethod.invoke(builder)

            val listenerClass = Class.forName("com.android.installreferrer.api.InstallReferrerStateListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onInstallReferrerSetupFinished") {
                    val responseCode = args?.get(0) as? Int ?: -1
                    if (responseCode == 0) { // InstallReferrerResponse.OK
                        try {
                            val getDetailsMethod = client.javaClass.getMethod("getInstallReferrer")
                            val response = getDetailsMethod.invoke(client)
                            val getReferrerUrlMethod = response.javaClass.getMethod("getInstallReferrer")
                            val referrerUrl = getReferrerUrlMethod.invoke(response) as? String

                            if (!referrerUrl.isNullOrBlank()) {
                                processReferrerString(context, referrerUrl, analytics, onAttributionFound)
                            }
                        } catch (_: Exception) {}
                        try {
                            val endMethod = client.javaClass.getMethod("endConnection")
                            endMethod.invoke(client)
                        } catch (_: Exception) {}
                    }
                }
                null
            }

            val startMethod = client.javaClass.getMethod("startConnection", listenerClass)
            startMethod.invoke(client, proxy)
        } catch (_: ClassNotFoundException) {
            // InstallReferrerClient library not linked; skip cleanly
        } catch (_: Throwable) {
            // Graceful silent fallback
        }
    }

    /**
     * Parses Google Play UTM referrer parameters.
     */
    fun processReferrerString(
        context: Context,
        referrerUrl: String,
        analytics: CrossPromoAnalytics,
        onAttributionFound: ((sourcePackage: String, campaign: String) -> Unit)? = null
    ) {
        try {
            // Referrer may be formatted as "utm_source=com.hartmann.app1&utm_medium=crosspromo&utm_campaign=hartmann_crosspromo"
            val dummyUri = Uri.parse("https://play.google.com/store/apps/details?$referrerUrl")
            val medium = dummyUri.getQueryParameter("utm_medium")
            val source = dummyUri.getQueryParameter("utm_source")
            val campaign = dummyUri.getQueryParameter("utm_campaign") ?: "hartmann_crosspromo"

            if (medium == "crosspromo" && !source.isNullOrBlank()) {
                val event = PromoEvent(
                    event = "crosspromo_install",
                    sourcePackage = source,
                    targetPackage = context.packageName,
                    placement = "install_referrer",
                    selectionType = campaign
                )
                analytics.logInstall(event)
                onAttributionFound?.invoke(source, campaign)
            }
        } catch (_: Exception) {
            // Silently ignore malformed referrer strings
        }
    }
}
