package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.NwsAlertsResponse
import com.charleshartman.porchlightpress.data.weather.NwsForecastResponse
import com.charleshartman.porchlightpress.data.weather.NwsObservationResponse
import com.charleshartman.porchlightpress.data.weather.NwsPointsResponse
import com.charleshartman.porchlightpress.data.weather.NwsStationsResponse
import com.charleshartman.porchlightpress.data.weather.WeatherCondition
import com.charleshartman.porchlightpress.data.weather.WeatherConditionMapper
import com.charleshartman.porchlightpress.data.weather.isWarningLevel
import com.charleshartman.porchlightpress.data.weather.severityRank
import com.charleshartman.porchlightpress.data.weather.toAlert
import com.charleshartman.porchlightpress.data.weather.toCurrent
import com.charleshartman.porchlightpress.data.weather.toDaily
import com.charleshartman.porchlightpress.data.weather.toHourly
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NWS fixture parsing → domain mapping (points/forecast/hourly/alerts/obs). */
class NwsParsingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun pointsExposesGridpointUrls() {
        val props = json.decodeFromString<NwsPointsResponse>(testResource("nws-points.json")).properties
        assertTrue(props.forecast.endsWith("/gridpoints/ALY/58,56/forecast"))
        assertTrue(props.forecastHourly.endsWith("/forecast/hourly"))
        assertTrue(props.observationStations.endsWith("/stations"))
    }

    @Test
    fun hourlyMapsTempWindCondition() {
        val periods = json.decodeFromString<NwsForecastResponse>(testResource("nws-hourly.json")).properties.periods
        assertEquals(3, periods.size)
        val first = periods.first().toHourly()
        assertEquals(20.0, first.tempC, 0.01) // 68°F
        assertEquals(5, first.precipPct)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, first.condition)
    }

    @Test
    fun dailyPairsDayAndNight() {
        val periods = json.decodeFromString<NwsForecastResponse>(testResource("nws-forecast.json")).properties.periods
        val daily = periods.toDaily(null)
        assertEquals(2, daily.size)
        // Day 1: Thursday 74°F hi / Tonight 58°F lo.
        assertEquals(23.33, daily[0].hiC, 0.01)
        assertEquals(14.44, daily[0].loC, 0.01)
        assertEquals(20, daily[0].precipPct)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, daily[0].condition)
        // Day 2: Friday thunderstorms, 90°F hi.
        assertEquals(32.22, daily[1].hiC, 0.01)
        assertEquals(WeatherCondition.THUNDERSTORM, daily[1].condition)
        assertEquals("2026-09-25", daily[0].dateIso)
        assertEquals("2026-09-26", daily[1].dateIso)
    }

    @Test
    fun alertsMapWithWarningLevels() {
        val alerts = json.decodeFromString<NwsAlertsResponse>(testResource("nws-alerts.json"))
            .features.map { it.toAlert() }
        assertEquals(3, alerts.size)
        val flood = alerts.first { it.event == "Flood Warning" }
        assertEquals("NWS Albany NY", flood.sender)
        assertEquals("Severe", flood.severity)
        assertTrue(flood.description.contains("Mohawk River"))
        assertTrue(flood.instruction!!.contains("Turn around"))
        assertTrue(flood.isWarningLevel())
        val tornado = alerts.first { it.event == "Tornado Warning" }
        assertTrue(tornado.isWarningLevel())
        assertEquals(0, tornado.severityRank())
        assertEquals(1, flood.severityRank())
        val heat = alerts.first { it.event == "Heat Advisory" }
        assertFalse(heat.isWarningLevel())
        assertEquals(3, heat.severityRank())
        assertEquals("https://www.weather.gov/", flood.link)
    }

    @Test
    fun observationBuildsCurrentWithHourlyFallback() {
        val obs = json.decodeFromString<NwsObservationResponse>(testResource("nws-observation.json")).properties
        val hourly = json.decodeFromString<NwsForecastResponse>(testResource("nws-hourly.json"))
            .properties.periods.first().toHourly()
        val cur = obs.toCurrent(hourly)
        assertEquals(21.1, cur.tempC, 0.01)
        assertEquals(65, cur.humidityPct)
        assertEquals(14.5, cur.windKph!!, 0.01)
        assertEquals("NW", cur.windDir)
        // 70°F is mild: feels-like passes through.
        assertEquals(21.1, cur.feelsLikeC, 0.01)
        assertEquals(5, cur.precipPct)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, cur.condition)
    }

    @Test
    fun iconCodeBeatsKeywords() {
        // "Chance Showers" with a showers icon → SHOWERS, not RAIN.
        assertEquals(
            WeatherCondition.SHOWERS,
            WeatherConditionMapper.nwsCondition(
                "Chance Showers",
                "https://api.weather.gov/icons/land/night/shra,30?size=medium",
            ),
        )
        assertEquals(
            WeatherCondition.CLEAR,
            WeatherConditionMapper.nwsCondition("Sunny", "https://api.weather.gov/icons/land/day/skc?size=medium"),
        )
    }

    @Test
    fun stationsExposeIdentifier() {
        val stations = json.decodeFromString<NwsStationsResponse>(testResource("nws-stations.json"))
        assertEquals("KALB", stations.features.first().properties.stationIdentifier)
    }

    @Test
    fun watchWithSevereSeverityCountsAsWarningLevel() {
        val watch = com.charleshartman.porchlightpress.data.weather.WeatherAlert(
            id = "x", event = "Flood Watch", headline = "h", description = "d", severity = "Severe",
        )
        assertTrue(watch.isWarningLevel())
        val statement = com.charleshartman.porchlightpress.data.weather.WeatherAlert(
            id = "y", event = "Special Weather Statement", headline = "h", description = "d", severity = "Unknown",
        )
        assertFalse(statement.isWarningLevel())
    }
}
