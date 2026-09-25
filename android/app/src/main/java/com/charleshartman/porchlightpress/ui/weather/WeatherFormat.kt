package com.charleshartman.porchlightpress.ui.weather

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Shared weather helpers: translated-text lookup (ML Kit, ephemeral — weather
 * text is not cached in Room) and alert time formatting.
 */

/** Translate one weather/alert string; English (or failure) returns as-is. */
@Composable
fun rememberWeatherTr(container: AppContainer, lang: String): suspend (String) -> String {
    val repo = container.translationRepository
    return { english: String ->
        if (lang == "en" || english.isBlank()) english
        else runCatching { repo.translateString(english, lang) }.getOrDefault(english)
    }
}

/** Translated string state for one English source (re-runs on lang change). */
@Composable
fun translatedWeatherText(
    container: AppContainer,
    lang: String,
    english: String,
): String {
    val tr by produceState(initialValue = english, container, lang, english) {
        value = if (lang == "en" || english.isBlank()) {
            english
        } else {
            runCatching { container.translationRepository.translateString(english, lang) }
                .getOrDefault(english)
        }
    }
    return tr
}

/** "Until 11:00 PM" from expires/ends, in the device zone. */
fun alertUntilText(alert: WeatherAlert, use24h: Boolean): String? {
    val iso = alert.expires ?: alert.ends ?: return null
    return try {
        val zdt = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault())
        val pat = if (use24h) "HH:mm" else "h:mm a"
        "Until " + zdt.format(java.time.format.DateTimeFormatter.ofPattern(pat))
    } catch (e: Exception) {
        null
    }
}

/** "2 PM" / "14:00" for an hourly point. */
fun hourlyLabel(timeIso: String, use24h: Boolean): String {
    return try {
        val zdt = OffsetDateTime.parse(timeIso).atZoneSameInstant(ZoneId.systemDefault())
        val pat = if (use24h) "HH:00" else "h a"
        zdt.format(java.time.format.DateTimeFormatter.ofPattern(pat))
    } catch (e: Exception) {
        timeIso.take(16).replace("T", " ")
    }
}

/** "Mon" + "Sep 24" for a daily dateIso (yyyy-MM-dd). */
fun dailyLabels(dateIso: String): Pair<String, String> {
    return try {
        val d = java.time.LocalDate.parse(dateIso)
        d.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) to
            d.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    } catch (e: Exception) {
        dateIso to ""
    }
}

/** "as of 6:41 AM" for a fetchedAt epoch. */
fun asOfLabel(fetchedAt: Long, context: android.content.Context, use24h: Boolean): String {
    return try {
        val zdt = java.time.Instant.ofEpochMilli(fetchedAt).atZone(ZoneId.systemDefault())
        val pat = if (use24h) "HH:mm" else "h:mm a"
        "as of " + zdt.format(java.time.format.DateTimeFormatter.ofPattern(pat))
    } catch (e: Exception) {
        ""
    }
}

fun is24h(context: android.content.Context, clockOverride: String): Boolean {
    if (clockOverride == "24h") return true
    if (clockOverride == "12h") return false
    return android.text.format.DateFormat.is24HourFormat(context)
}
