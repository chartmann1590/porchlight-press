package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.MetCompact
import com.charleshartman.porchlightpress.data.weather.WeatherCondition
import com.charleshartman.porchlightpress.data.weather.WeatherConditionMapper
import com.charleshartman.porchlightpress.data.weather.toDomain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** MET Norway compact fixture → domain mapping. */
class MetNoParsingTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private fun parsed() = json.decodeFromString<MetCompact>(testResource("met-compact.json")).toDomain("Europe/Oslo")

    @Test
    fun currentFromFirstSeries() {
        val (current, _, _) = parsed()
        assertEquals(14.2, current.tempC, 0.01)
        assertEquals(71, current.humidityPct)
        // 3.1 m/s → kph.
        assertEquals(11.16, current.windKph!!, 0.01)
        assertEquals("SW", current.windDir)
        assertEquals(WeatherCondition.PARTLY_CLOUDY, current.condition)
        assertEquals(10, current.precipPct)
        // Mild: feels-like passes through.
        assertEquals(14.2, current.feelsLikeC, 0.01)
    }

    @Test
    fun hourlyTakesAllSeries() {
        val (_, hourly, _) = parsed()
        assertEquals(16, hourly.size)
        assertEquals(WeatherCondition.CLEAR, hourly[2].condition)
        assertEquals(WeatherCondition.RAIN, hourly[6].condition)
        assertEquals(WeatherCondition.FOG, hourly[8].condition)
    }

    @Test
    fun dailyGroupsByLocalDate() {
        val (_, _, daily) = parsed()
        assertEquals(2, daily.size)
        assertEquals(15.1, daily[0].hiC, 0.01)
        assertEquals(11.8, daily[0].loC, 0.01)
        assertEquals(85, daily[0].precipPct)
        assertEquals(14.4, daily[1].hiC, 0.01)
        assertEquals(9.6, daily[1].loC, 0.01)
    }

    @Test
    fun next6And12HourFallbacks() {
        val (_, hourly, _) = parsed()
        // Last two entries carry only next_6_hours / next_12_hours.
        assertEquals(WeatherCondition.PARTLY_CLOUDY, hourly[14].condition)
        assertEquals(22, hourly[14].precipPct)
        assertEquals(WeatherCondition.SHOWERS, hourly[15].condition)
        assertEquals(40, hourly[15].precipPct)
    }

    @Test
    fun symbolMapping() {
        assertEquals(WeatherCondition.CLEAR, WeatherConditionMapper.metCondition("clearsky_day"))
        assertEquals(WeatherCondition.CLEAR, WeatherConditionMapper.metCondition("clearsky_night"))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, WeatherConditionMapper.metCondition("fair_night"))
        assertEquals(WeatherCondition.THUNDERSTORM, WeatherConditionMapper.metCondition("heavyrainandthunder_day"))
        assertEquals(WeatherCondition.SNOW, WeatherConditionMapper.metCondition("heavysnowshowers_night"))
        assertEquals(WeatherCondition.SLEET, WeatherConditionMapper.metCondition("sleet"))
        assertEquals(WeatherCondition.DRIZZLE, WeatherConditionMapper.metCondition("lightdrizzle"))
        assertEquals(WeatherCondition.UNKNOWN, WeatherConditionMapper.metCondition("not_a_symbol"))
    }

    @Test
    fun emptyCompactDegrades() {
        val (current, hourly, daily) = MetCompact().toDomain(null)
        assertTrue(current.tempC.isNaN())
        assertTrue(hourly.isEmpty())
        assertTrue(daily.isEmpty())
    }
}
