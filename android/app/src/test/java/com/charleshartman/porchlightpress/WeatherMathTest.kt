package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.WeatherMath
import com.charleshartman.porchlightpress.data.weather.parseNwsWindKph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherMathTest {

    @Test
    fun heatIndexMatchesNwsExample() {
        // 90°F + 60% RH ≈ 100°F (NWS chart).
        assertEquals(100.0, WeatherMath.heatIndexF(90.0, 60.0), 1.5)
    }

    @Test
    fun windChillMatchesNwsExample() {
        // 30°F + 10 mph ≈ 21°F (NWS chart).
        assertEquals(21.0, WeatherMath.windChillF(30.0, 10.0), 1.0)
    }

    @Test
    fun feelsLikeUsesHeatIndexWhenHot() {
        val feels = WeatherMath.feelsLikeC(32.2, 60, null)
        assertEquals(37.6, feels, 1.0)
    }

    @Test
    fun feelsLikeUsesWindChillWhenColdAndWindy() {
        val feels = WeatherMath.feelsLikeC(-1.1, null, 16.1)
        assertEquals(-6.0, feels, 1.0)
    }

    @Test
    fun feelsLikePassesThroughWhenMild() {
        assertEquals(20.0, WeatherMath.feelsLikeC(20.0, 65, 14.5), 0.0)
        // Cold but calm: no wind chill below the 3 mph gate.
        assertEquals(-1.1, WeatherMath.feelsLikeC(-1.1, null, 1.0), 0.0)
    }

    @Test
    fun formatTempUsVsWorld() {
        assertEquals("68°F", WeatherMath.formatTemp(20.0, "US"))
        assertEquals("20°C", WeatherMath.formatTemp(20.0, "NO"))
        assertEquals("32°F", WeatherMath.formatTemp(0.0, "us"))
    }

    @Test
    fun parseNwsWind() {
        assertEquals(8.05, parseNwsWindKph("5 mph")!!, 0.01)
        // Ranges use the midpoint: 7.5 mph → kph.
        assertEquals(12.07, parseNwsWindKph("5 to 10 mph")!!, 0.01)
        assertEquals(10.0, parseNwsWindKph("10 km/h")!!, 0.01)
        assertNull(parseNwsWindKph("Calm"))
        assertNull(parseNwsWindKph(""))
    }
}
