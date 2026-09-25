package com.charleshartman.porchlightpress.data.weather

import kotlinx.serialization.Serializable

/**
 * MET Norway Locationforecast 2.0 compact DTOs (api.met.no, CC BY 4.0).
 * Unknown fields ignored: the API adds detail keys over time.
 */

@Serializable
data class MetCompact(val properties: MetProps = MetProps())

@Serializable
data class MetProps(val timeseries: List<MetSeries> = emptyList())

@Serializable
data class MetSeries(val time: String = "", val data: MetData = MetData())

@Serializable
data class MetData(
    val instant: MetInstant = MetInstant(),
    val next_1_hours: MetNext? = null,
    val next_6_hours: MetNext? = null,
    val next_12_hours: MetNext? = null,
)

@Serializable
data class MetInstant(val details: MetDetails = MetDetails())

@Serializable
data class MetDetails(
    val air_temperature: Double = Double.NaN,
    val feels_like: Double? = null,
    val relative_humidity: Double? = null,
    val wind_speed: Double? = null,
    val wind_from_direction: Double? = null,
    val precipitation_amount: Double? = null,
    val probability_of_precipitation: Double? = null,
)

@Serializable
data class MetNext(
    val summary: MetSummary = MetSummary(),
    val details: MetPrecipDetails? = null,
)

@Serializable
data class MetSummary(val symbol_code: String = "")

@Serializable
data class MetPrecipDetails(
    val precipitation_amount: Double? = null,
    val probability_of_precipitation: Double? = null,
)

private fun MetSeries.symbol(): String =
    data.next_1_hours?.summary?.symbol_code
        ?: data.next_6_hours?.summary?.symbol_code
        ?: data.next_12_hours?.summary?.symbol_code
        ?: ""

private fun MetSeries.precipPct(): Int? =
    data.next_1_hours?.details?.probability_of_precipitation?.toInt()
        ?: data.next_6_hours?.details?.probability_of_precipitation?.toInt()
        ?: data.next_12_hours?.details?.probability_of_precipitation?.toInt()
        ?: data.instant.details.probability_of_precipitation?.toInt()

private fun windDirOrNull(deg: Double?): String? {
    if (deg == null) return null
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val idx = (((deg % 360.0 + 360.0) % 360.0) / 45.0 + 0.5).toInt() % 8
    return dirs[idx]
}

/**
 * Map the compact timeseries (hourly steps) to the shared domain model.
 * MET has no alerts endpoint: alerts are always empty (the repository marks
 * them unavailable outside the US).
 */
fun MetCompact.toDomain(placeTz: String?): Triple<CurrentWeather, List<HourlyPoint>, List<DailyPoint>> {
    val series = properties.timeseries
    if (series.isEmpty()) {
        return Triple(
            CurrentWeather(Double.NaN, Double.NaN),
            emptyList(),
            emptyList(),
        )
    }
    val first = series.first()
    val d = first.data.instant.details
    val sym = first.symbol()
    val current = CurrentWeather(
        tempC = d.air_temperature,
        feelsLikeC = d.feels_like
            ?: WeatherMath.feelsLikeC(d.air_temperature, d.relative_humidity?.toInt(), d.wind_speed?.let { it * 3.6 }),
        humidityPct = d.relative_humidity?.toInt(),
        // MET wind_speed is m/s → kph.
        windKph = d.wind_speed?.times(3.6),
        windDir = windDirOrNull(d.wind_from_direction),
        condition = WeatherConditionMapper.metCondition(sym),
        shortText = WeatherConditionMapper.metShortText(sym),
        precipPct = first.precipPct(),
    )
    val hourly = series.take(24).map { s ->
        val det = s.data.instant.details
        val sSym = s.symbol()
        HourlyPoint(
            timeIso = s.time,
            tempC = det.air_temperature,
            precipPct = s.precipPct(),
            condition = WeatherConditionMapper.metCondition(sSym),
            shortText = WeatherConditionMapper.metShortText(sSym),
        )
    }
    val zone = runCatching { java.time.ZoneId.of(placeTz) }.getOrDefault(java.time.ZoneOffset.UTC)
    val byDay = series.groupBy { entry ->
        runCatching { java.time.Instant.parse(entry.time).atZone(zone).toLocalDate().toString() }
            .getOrDefault(entry.time.take(10))
    }.toSortedMap()
    // Drop a partial first day when it has fewer than 6 samples and more days exist.
    val days = byDay.values.toList().let { all ->
        if (all.size > 7 && all.first().size < 6) all.drop(1) else all
    }.take(7)
    val daily = days.map { day ->
        val temps = day.map { it.data.instant.details.air_temperature }.filter { !it.isNaN() }
        val midday = day.minByOrNull { entry ->
            val h = runCatching { java.time.Instant.parse(entry.time).atZone(zone).hour }.getOrDefault(12)
            kotlin.math.abs(h - 13)
        } ?: day.first()
        val mSym = midday.symbol()
        DailyPoint(
            dateIso = runCatching {
                java.time.Instant.parse(day.first().time).atZone(zone).toLocalDate().toString()
            }.getOrDefault(day.first().time.take(10)),
            hiC = temps.maxOrNull() ?: Double.NaN,
            loC = temps.minOrNull() ?: Double.NaN,
            precipPct = day.mapNotNull { it.precipPct() }.maxOrNull(),
            condition = WeatherConditionMapper.metCondition(mSym),
            shortText = WeatherConditionMapper.metShortText(mSym),
        )
    }
    return Triple(current, hourly, daily)
}
