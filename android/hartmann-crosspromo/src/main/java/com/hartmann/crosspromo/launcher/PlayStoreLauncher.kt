package com.hartmann.crosspromo.launcher

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.hartmann.crosspromo.model.PromoApp
import java.net.URLEncoder

/**
 * Safe, resilient launcher for Google Play Store app listings.
 * Enforces Play Store intent resolution with browser fallback and UTM referral attribution.
 * NEVER downloads APKs or sideloads files directly.
 */
object PlayStoreLauncher {

    /**
     * Builds standard Google Play attribution referrer string:
     * utm_source=<source_pkg>&utm_medium=crosspromo&utm_campaign=hartmann_crosspromo&utm_content=<target_pkg>
     */
    fun buildReferrerString(sourcePackage: String, targetPackage: String): String {
        return try {
            "utm_source=" + URLEncoder.encode(sourcePackage, "UTF-8") +
            "&utm_medium=" + URLEncoder.encode("crosspromo", "UTF-8") +
            "&utm_campaign=" + URLEncoder.encode("hartmann_crosspromo", "UTF-8") +
            "&utm_content=" + URLEncoder.encode(targetPackage, "UTF-8")
        } catch (_: Exception) {
            "utm_source=$sourcePackage&utm_medium=crosspromo&utm_campaign=hartmann_crosspromo&utm_content=$targetPackage"
        }
    }

    /**
     * Safely opens the Google Play Store listing for a recommended app.
     */
    fun openPlayStore(
        context: Context,
        app: PromoApp,
        sourcePackage: String = context.packageName,
        onLaunched: (() -> Unit)? = null
    ): Boolean {
        val targetPackage = app.packageName
        val referrer = buildReferrerString(sourcePackage, targetPackage)
        val encodedReferrer = try {
            URLEncoder.encode(referrer, "UTF-8")
        } catch (_: Exception) {
            referrer
        }

        // 1. Primary approach: market://details?id=<package>&referrer=<encodedReferrer>
        val marketUri = Uri.parse("market://details?id=$targetPackage&referrer=$encodedReferrer")
        val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            setPackage("com.android.vending")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(marketIntent)
            onLaunched?.invoke()
            return true
        } catch (_: ActivityNotFoundException) {
            // Google Play app not found or disabled; fall back to general intent or browser
        } catch (_: SecurityException) {
            // Permission/intent resolution security block; fall back
        }

        // 2. Secondary approach: market:// without explicit vending package restriction
        val genericMarketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(genericMarketIntent)
            onLaunched?.invoke()
            return true
        } catch (_: Exception) {
            // Fall back to web browser
        }

        // 3. Fallback: official HTTPS Play Store URL in web browser
        val webUri = Uri.parse("https://play.google.com/store/apps/details?id=$targetPackage&referrer=$encodedReferrer")
        val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(webIntent)
            onLaunched?.invoke()
            true
        } catch (_: Exception) {
            false // Device cannot handle web URLs; fail silently without crashing host app
        }
    }
}
