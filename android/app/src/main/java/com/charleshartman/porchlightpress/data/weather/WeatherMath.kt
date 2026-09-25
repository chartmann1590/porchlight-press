package com.charleshartman.porchlightpress.data.weather

import java.util.Locale
import kotlin.math.pow

/**
 * Temperature math. Feels-like is computed with the NWS heat-index
 * (Rothfusz) and wind-chill formulas when the provider does not supply it.
 */
object WeatherMath {
    fun cToF(c: Double): Double = c * 9.0 / 5.0 + 32.0
    fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0
    fun kphToMph(kph: Double): Double = kph * 0.621371
    fun mphToKph(mph: Double): Double = mph / 0.621371

    /** Display temperature: °F for US places, °C everywhere else. */
    fun formatTemp(tempC: Double, countryIso2: String): String {
        return if (countryIso2.equals("US", ignoreCase = true)) {
            "${kotlin.math.round(cToF(tempC)).toInt()}°F"
        } else {
            "${kotlin.math.round(tempC).toInt()}°C"
        }
    }

    /** Display wind: mph for US, km/h elsewhere. */
    fun formatWind(windKph: Double?, dir: String?, countryIso2: String): String {
        if (windKph == null) return ""
        return if (countryIso2.equals("US", ignoreCase = true)) {
            val mph = kotlin.math.round(kphToMph(windKph)).toInt()
            listOfNotNull("${mph} mph".takeIf { true }, dir).joinToString(" ")
        } else {
            val kph = kotlin.math.round(windKph).toInt()
            listOfNotNull("$kph km/h", dir).joinToString(" ")
        }
    }

    /**
     * Rothfusz heat index in °F. Valid for T ≥ 80°F; callers gate on that.
     * Includes the low-humidity and high-humidity adjustments.
     */
    fun heatIndexF(tF: Double, rhPct: Double): Double {
        val t = tF
        val r = rhPct
        var hi = -42.379 + 2.04901523 * t + 10.14333127 * r -
            0.22475541 * t * r - 0.00683783 * t * t -
            0.05481717 * r * r + 0.00122874 * t * t * r +
            0.00085282 * t * r * r - 0.00000199 * t * t * r * r
        if (r < 13.0 && t in 80.0..112.0) {
            hi -= ((13.0 - r) / 4.0) * ((17.0 - kotlin.math.abs(t - 95.0)) / 17.0).pow(0.5)
        } else if (r > 85.0 && t in 80.0..87.0) {
            hi += ((r - 85.0) / 10.0) * ((87.0 - t) / 5.0)
        }
        return hi
    }

    /** NWS wind chill in °F. Valid for T ≤ 50°F and wind > 3 mph. */
    fun windChillF(tF: Double, windMph: Double): Double =
        35.74 + 0.6215 * tF - 35.75 * windMph.pow(0.16) +
            0.4275 * tF * windMph.pow(0.16)

    /**
     * Feels-like in °C. Heat index when hot (≥ 80°F ≈ 26.7°C with humidity),
     * wind chill when cold (≤ 50°F ≈ 10°C with wind > 3 mph ≈ 4.8 km/h),
     * otherwise the air temperature.
     */
    fun feelsLikeC(tempC: Double, humidityPct: Int?, windKph: Double?): Double {
        val tF = cToF(tempC)
        if (humidityPct != null && tF >= 80.0) {
            return fToC(heatIndexF(tF, humidityPct.toDouble()))
        }
        if (windKph != null && tF <= 50.0 && kphToMph(windKph) > 3.0) {
            return fToC(windChillF(tF, kphToMph(windKph)))
        }
        return tempC
    }

    /** "42.8,-73.9" style bucket formatting guard for tests. */
    fun bucketKey(lat: Double, lon: Double): String =
        String.format(Locale.US, "%.1f,%.1f", lat, lon)
}
