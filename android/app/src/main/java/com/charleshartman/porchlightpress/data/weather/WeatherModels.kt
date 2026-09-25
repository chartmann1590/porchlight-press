package com.charleshartman.porchlightpress.data.weather

import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * Phase 7 weather domain model. One model for both providers (NWS + MET
 * Norway). All temperatures are stored in Celsius; display converts to °F
 * for US places ([formatTemp]).
 */

/** Coordinates rounded to a ~0.1° bucket (about 10 km). Privacy-friendly and
 * accurate enough for forecasts and alerts. Documented in the privacy policy. */
data class WeatherBucket(val lat: Double, val lon: Double) {
    /** Stable Room cache key, e.g. "42.8,-73.9". */
    val key: String = String.format(Locale.US, "%.1f,%.1f", lat, lon)

    companion object {
        /** Round raw coordinates to the 0.1° bucket (half-up, away from the
         * raw value's extra precision). */
        fun from(lat: Double, lon: Double): WeatherBucket {
            val rLat = kotlin.math.round(lat * 10.0) / 10.0
            val rLon = kotlin.math.round(lon * 10.0) / 10.0
            return WeatherBucket(rLat, rLon)
        }
    }
}

enum class WeatherCondition(val icon: String) {
    CLEAR("☀️"),
    PARTLY_CLOUDY("⛅"),
    CLOUDY("☁️"),
    FOG("🌫️"),
    DRIZZLE("🌦️"),
    SHOWERS("🌦️"),
    RAIN("🌧️"),
    THUNDERSTORM("⛈️"),
    SNOW("🌨️"),
    SLEET("🌨️"),
    WINDY("💨"),
    UNKNOWN("🌡️"),
}

/** English condition label; translated on-device via ML Kit when needed. */
fun conditionLabel(condition: WeatherCondition): String = when (condition) {
    WeatherCondition.CLEAR -> "Clear"
    WeatherCondition.PARTLY_CLOUDY -> "Partly cloudy"
    WeatherCondition.CLOUDY -> "Cloudy"
    WeatherCondition.FOG -> "Fog"
    WeatherCondition.DRIZZLE -> "Drizzle"
    WeatherCondition.SHOWERS -> "Showers"
    WeatherCondition.RAIN -> "Rain"
    WeatherCondition.THUNDERSTORM -> "Thunderstorms"
    WeatherCondition.SNOW -> "Snow"
    WeatherCondition.SLEET -> "Sleet"
    WeatherCondition.WINDY -> "Windy"
    WeatherCondition.UNKNOWN -> "Unknown"
}

/** Current conditions. `shortText` is the provider's English phrase (NWS
 * shortForecast / MET symbol words), translated for display when needed. */
@Serializable
data class CurrentWeather(
    val tempC: Double,
    val feelsLikeC: Double,
    val humidityPct: Int? = null,
    val windKph: Double? = null,
    val windDir: String? = null,
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    val shortText: String = "",
    val precipPct: Int? = null,
)

@Serializable
data class HourlyPoint(
    /** ISO-8601 start time as sent by the provider. */
    val timeIso: String,
    val tempC: Double,
    val precipPct: Int? = null,
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    val shortText: String = "",
)

@Serializable
data class DailyPoint(
    /** ISO-8601 calendar date (yyyy-MM-dd) in the place's timezone. */
    val dateIso: String,
    val hiC: Double,
    val loC: Double,
    val precipPct: Int? = null,
    val condition: WeatherCondition = WeatherCondition.UNKNOWN,
    val shortText: String = "",
)

@Serializable
data class WeatherAlert(
    /** Stable provider ID (NWS alert ID / URN); used for notify dedupe. */
    val id: String,
    /** e.g. "Tornado Warning". Carried untranslated in notifications. */
    val event: String,
    val headline: String,
    /** Full NWS text, rendered as plain text (never HTML). */
    val description: String,
    val instruction: String? = null,
    /** NWS severity: Extreme, Severe, Moderate, Minor, Unknown. */
    val severity: String = "Unknown",
    val urgency: String? = null,
    val sender: String = "",
    val effective: String? = null,
    val expires: String? = null,
    val ends: String? = null,
    val areaDesc: String? = null,
    /** Link to the alert / weather.gov. */
    val link: String? = null,
)

/**
 * Warning-level = fires a severe-weather notification and shows the original
 * English inline under any translation. Simplest rule consistent with the
 * plan: the event name says "Warning", or NWS severity is Extreme/Severe.
 */
fun WeatherAlert.isWarningLevel(): Boolean =
    event.contains("warning", ignoreCase = true) ||
        severity.equals("Extreme", ignoreCase = true) ||
        severity.equals("Severe", ignoreCase = true)

/** Banner order: most severe first. */
fun WeatherAlert.severityRank(): Int = when (severity.lowercase()) {
    "extreme" -> 0
    "severe" -> 1
    "moderate" -> 2
    "minor" -> 3
    else -> 4
}

/**
 * Assembled snapshot for one bucket: forecast pieces plus alerts plus
 * provenance. `alertsFromFallback` is true when the forecast came from MET
 * Norway but alerts (if any) came from NWS, or when alerts are unavailable
 * (non-US place) — the UI shows the matching banner/attribution.
 */
@Serializable
data class WeatherData(
    val current: CurrentWeather,
    val hourly: List<HourlyPoint> = emptyList(),
    val daily: List<DailyPoint> = emptyList(),
    val alerts: List<WeatherAlert> = emptyList(),
    /** "nws" or "met". */
    val provider: String = "nws",
    /** True when alerts could not be fetched (non-US place, or NWS down). */
    val alertsUnavailable: Boolean = false,
    val fetchedAt: Long = 0L,
    val expiresAt: Long = 0L,
)

fun weatherAttribution(provider: String): String = when (provider) {
    "met" -> "Weather data: MET Norway (CC BY 4.0)"
    else -> "Weather data: National Weather Service"
}

// ---------------------------------------------------------------------------
// Room payload wrappers (stored as JSON in WeatherCache.payloadJson).
// ---------------------------------------------------------------------------

@Serializable
data class ForecastPayload(
    val current: CurrentWeather,
    val hourly: List<HourlyPoint> = emptyList(),
    val daily: List<DailyPoint> = emptyList(),
    val provider: String = "nws",
    val fetchedAt: Long = 0L,
    val expiresAt: Long = 0L,
)

@Serializable
data class AlertsPayload(
    val alerts: List<WeatherAlert> = emptyList(),
    /** "nws" when fetched; "none" when unavailable (cached as unavailable). */
    val source: String = "nws",
    val fetchedAt: Long = 0L,
    val expiresAt: Long = 0L,
)

/** NWS gridpoint lookup, cached permanently per bucket. */
@Serializable
data class NwsPoints(
    val forecastUrl: String,
    val hourlyUrl: String,
    val observationStationsUrl: String,
    val zone: String = "",
)
