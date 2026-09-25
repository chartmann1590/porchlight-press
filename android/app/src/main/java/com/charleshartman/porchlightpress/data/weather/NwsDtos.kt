package com.charleshartman.porchlightpress.data.weather

import kotlinx.serialization.Serializable

/**
 * api.weather.gov DTOs (GeoJSON-flavoured; unknown fields ignored).
 * Mapping to [CurrentWeather]/[HourlyPoint]/[DailyPoint]/[WeatherAlert]
 * lives next to each DTO so parsing fixtures exercise the real path.
 */

@Serializable
data class NwsPointsResponse(val properties: NwsPointsProps = NwsPointsProps())

@Serializable
data class NwsPointsProps(
    val forecast: String = "",
    val forecastHourly: String = "",
    val forecastZone: String = "",
    val observationStations: String = "",
)

@Serializable
data class NwsPop(val value: Int? = null)

@Serializable
data class NwsPeriod(
    val name: String = "",
    val temperature: Double = Double.NaN,
    val temperatureUnit: String = "F",
    val probabilityOfPrecipitation: NwsPop = NwsPop(),
    val windSpeed: String = "",
    val windDirection: String? = null,
    val shortForecast: String = "",
    val detailedForecast: String? = null,
    val isDaytime: Boolean = true,
    val startTime: String = "",
    val endTime: String = "",
    val icon: String? = null,
)

@Serializable
data class NwsForecastResponse(val properties: NwsForecastProps = NwsForecastProps())

@Serializable
data class NwsForecastProps(val periods: List<NwsPeriod> = emptyList())

/** Parse "5 mph" / "5 to 10 mph" / "10 km/h" → kph. Null when unparseable. */
fun parseNwsWindKph(windSpeed: String): Double? {
    val nums = Regex("(\\d+(?:\\.\\d+)?)").findAll(windSpeed).map { it.value.toDouble() }.toList()
    if (nums.isEmpty()) return null
    // "X to Y" ranges: use the midpoint; single values use the value.
    val base = if (nums.size >= 2) (nums[0] + nums[1]) / 2.0 else nums[0]
    return if ("km/h" in windSpeed.lowercase()) base else base * 1.60934
}

/** NWS forecast temperature → Celsius (NaN-safe: NaN stays NaN). */
fun NwsPeriod.tempC(): Double = when (temperatureUnit.uppercase()) {
    "C" -> temperature
    else -> (temperature - 32.0) * 5.0 / 9.0
}

fun NwsPeriod.toHourly(): HourlyPoint = HourlyPoint(
    timeIso = startTime,
    tempC = tempC(),
    precipPct = probabilityOfPrecipitation.value,
    condition = WeatherConditionMapper.nwsCondition(shortForecast, icon),
    shortText = shortForecast,
)

/**
 * Group day/night period pairs into daily hi/lo rows. NWS 7-day forecast is
 * 14 periods (day, night, ...). Each daytime period starts a row; its night
 * sibling (and any second daytime name match) folds in. Falls back to
 * pairing consecutive periods when names are unusual.
 */
fun List<NwsPeriod>.toDaily(placeTz: String?): List<DailyPoint> {
    if (isEmpty()) return emptyList()
    val out = mutableListOf<DailyPoint>()
    var i = 0
    while (i < size && out.size < 7) {
        val first = this[i]
        val second = getOrNull(i + 1)
        val temps = listOf(first.tempC()) +
            (if (second != null) listOf(second.tempC()) else emptyList())
        val valid = temps.filter { !it.isNaN() }
        val day = first.takeIf { it.isDaytime } ?: second
        val night = if (first.isDaytime) second else first
        val precip = listOfNotNull(
            first.probabilityOfPrecipitation.value,
            second?.probabilityOfPrecipitation?.value,
        ).maxOrNull()
        val anchor = day ?: first
        out += DailyPoint(
            dateIso = dayDateIso(anchor.startTime, placeTz),
            hiC = valid.maxOrNull() ?: Double.NaN,
            loC = (night?.tempC() ?: valid.minOrNull() ?: Double.NaN),
            precipPct = precip,
            condition = WeatherConditionMapper.nwsCondition(anchor.shortForecast, anchor.icon),
            shortText = anchor.shortForecast,
        )
        i += if (second != null) 2 else 1
    }
    return out
}

private fun dayDateIso(startTime: String, placeTz: String?): String {
    return try {
        val zdt = java.time.OffsetDateTime.parse(startTime)
        val zone = runCatching { java.time.ZoneId.of(placeTz) }.getOrDefault(java.time.ZoneOffset.UTC)
        zdt.atZoneSameInstant(zone).toLocalDate().toString()
    } catch (e: Exception) {
        startTime.take(10)
    }
}

// ---------------------------------------------------------------------------
// Alerts
// ---------------------------------------------------------------------------

@Serializable
data class NwsAlertsResponse(val features: List<NwsAlertFeature> = emptyList())

@Serializable
data class NwsAlertFeature(val id: String = "", val properties: NwsAlertProps = NwsAlertProps())

@Serializable
data class NwsAlertProps(
    val event: String = "",
    val headline: String = "",
    val description: String = "",
    val instruction: String? = null,
    val severity: String = "Unknown",
    val urgency: String? = null,
    val senderName: String = "",
    val effective: String? = null,
    val expires: String? = null,
    val ends: String? = null,
    val areaDesc: String? = null,
)

fun NwsAlertFeature.toAlert(): WeatherAlert = WeatherAlert(
    id = id.ifBlank { "${properties.event}-${properties.effective}" },
    event = properties.event,
    headline = properties.headline.ifBlank { properties.event },
    description = properties.description,
    instruction = properties.instruction,
    severity = properties.severity.ifBlank { "Unknown" },
    urgency = properties.urgency,
    sender = properties.senderName,
    effective = properties.effective,
    expires = properties.expires,
    ends = properties.ends,
    areaDesc = properties.areaDesc,
    link = "https://www.weather.gov/",
)

// ---------------------------------------------------------------------------
// Observation stations + latest observation (current conditions)
// ---------------------------------------------------------------------------

@Serializable
data class NwsStationsResponse(val features: List<NwsStationFeature> = emptyList())

@Serializable
data class NwsStationFeature(
    val id: String = "",
    val properties: NwsStationProps = NwsStationProps(),
)

@Serializable
data class NwsStationProps(val stationIdentifier: String = "")

@Serializable
data class NwsValue(val value: Double? = null, val unitCode: String? = null)

@Serializable
data class NwsObsProps(
    val temperature: NwsValue = NwsValue(),
    val relativeHumidity: NwsValue = NwsValue(),
    val heatIndex: NwsValue = NwsValue(),
    val windChill: NwsValue = NwsValue(),
    val windSpeed: NwsValue = NwsValue(),
    val windDirection: NwsValue = NwsValue(),
    val textDescription: String = "",
)

@Serializable
data class NwsObservationResponse(val properties: NwsObsProps = NwsObsProps())

/**
 * Build current conditions from the latest observation, falling back to the
 * first hourly period when the observation (or its fields) is missing.
 * NWS observation units are SI (°C, km/h, %).
 */
fun NwsObsProps.toCurrent(hourlyFallback: HourlyPoint?): CurrentWeather {
    val tempC = temperature.value ?: hourlyFallback?.tempC ?: Double.NaN
    val humidity = relativeHumidity.value?.toInt() ?: null
    val windKph = windSpeed.value
    val feels = heatIndex.value ?: windChill.value
        ?: WeatherMath.feelsLikeC(tempC, humidity, windKph)
    val windDir = windDirection.value?.let { deg -> compass(deg) } ?: hourlyFallback?.let { null }
    return CurrentWeather(
        tempC = tempC,
        feelsLikeC = feels,
        humidityPct = humidity,
        windKph = windKph,
        windDir = windDir,
        condition = hourlyFallback?.condition ?: WeatherConditionMapper.nwsCondition(textDescription, null),
        shortText = textDescription.ifBlank { hourlyFallback?.shortText ?: "" },
        precipPct = hourlyFallback?.precipPct,
    )
}

private fun compass(degrees: Double): String {
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = ((degrees % 360.0 + 360.0) % 360.0 / 45.0 + 0.5).toInt() % 8
    return dirs[idx]
}
