package com.charleshartman.porchlightpress.data.weather

/**
 * Condition mapping for both providers. NWS prefers the icon-URL code
 * (stable); the shortForecast keywords are the fallback. MET Norway maps the
 * `symbol_code` prefix (day/night/polar-night suffixes stripped).
 */
object WeatherConditionMapper {

    /** NWS icon URLs look like .../icons/land/day/tsra,hi_tsra?size=medium */
    fun nwsCondition(shortForecast: String, iconUrl: String?): WeatherCondition {
        iconUrl?.let { url ->
            val seg = url.substringAfterLast("/").substringBefore("?").substringBefore(",").lowercase()
            iconCodeToCondition(seg)?.let { return it }
        }
        val t = shortForecast.lowercase()
        return when {
            "thunderstorm" in t || "thunder" in t -> WeatherCondition.THUNDERSTORM
            "snow" in t || "flurr" in t || "blizzard" in t -> WeatherCondition.SNOW
            "sleet" in t || "freezing rain" in t || "ice" in t -> WeatherCondition.SLEET
            "rain" in t || "shower" in t -> if ("shower" in t) WeatherCondition.SHOWERS else WeatherCondition.RAIN
            "drizzle" in t -> WeatherCondition.DRIZZLE
            "fog" in t || "haze" in t || "smoke" in t -> WeatherCondition.FOG
            "windy" in t || "breezy" in t || "gust" in t -> WeatherCondition.WINDY
            // "Partly cloudy"/"mostly sunny" before the bare "cloudy" check.
            "partly" in t || "mostly" in t -> WeatherCondition.PARTLY_CLOUDY
            "cloudy" in t || "overcast" in t -> WeatherCondition.CLOUDY
            "sunny" in t || "clear" in t || "fair" in t -> WeatherCondition.CLEAR
            else -> WeatherCondition.UNKNOWN
        }
    }

    private fun iconCodeToCondition(code: String): WeatherCondition? = when (code) {
        "skc", "hot", "cold" -> WeatherCondition.CLEAR
        "few", "sct" -> WeatherCondition.PARTLY_CLOUDY
        "bkn", "ovc" -> WeatherCondition.CLOUDY
        "fog", "nfg", "haze", "smoke", "dust", "ndu", "fu" -> WeatherCondition.FOG
        "rain", "nrain", "hi_rain", "fzra", "hi_fzra", "ra_sn" -> WeatherCondition.RAIN
        "shra", "hi_shra", "nshra", "hi_nshra" -> WeatherCondition.SHOWERS
        "tsra", "hi_tsra", "ntsra", "scttsra", "hi_tsra_sct" -> WeatherCondition.THUNDERSTORM
        "snow", "nsnow", "hi_snow", "blizzard", "nblizzard" -> WeatherCondition.SNOW
        "snip", "ip", "mix", "rasn", "fzra_sn", "sn" -> WeatherCondition.SLEET
        "wind", "nwind", "hi_nwind" -> WeatherCondition.WINDY
        else -> null
    }

    /** MET symbol_code, e.g. "partlycloudy_day", "heavyrainnight". */
    fun metCondition(symbolCode: String): WeatherCondition {
        var base = symbolCode.lowercase()
            .removeSuffix("_day").removeSuffix("_night").removeSuffix("_polartwilight")
        // Any thunder variant (rainandthunder, heavyrainandthunder, ...) first.
        if ("thunder" in base) return WeatherCondition.THUNDERSTORM
        base = base.removePrefix("light").removePrefix("heavy")
        return when {
            base.startsWith("clearsky") -> WeatherCondition.CLEAR
            base.startsWith("fair") -> WeatherCondition.PARTLY_CLOUDY
            base.startsWith("partlycloudy") -> WeatherCondition.PARTLY_CLOUDY
            base.startsWith("cloudy") -> WeatherCondition.CLOUDY
            base.startsWith("fog") -> WeatherCondition.FOG
            base.startsWith("sleet") -> WeatherCondition.SLEET
            base.startsWith("snow") -> WeatherCondition.SNOW
            base.startsWith("drizzle") -> WeatherCondition.DRIZZLE
            base.startsWith("rainshowers") -> WeatherCondition.SHOWERS
            base.startsWith("rain") -> WeatherCondition.RAIN
            else -> WeatherCondition.UNKNOWN
        }
    }

    /** Short English words for a MET symbol (translated for display). */
    fun metShortText(symbolCode: String): String =
        conditionLabel(metCondition(symbolCode))
}
