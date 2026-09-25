package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.WeatherBucket
import org.junit.Assert.assertEquals
import org.junit.Test

/** 0.1° bucket: rounding, key format, privacy (no raw precision leaks). */
class WeatherBucketTest {

    @Test
    fun roundsToOneDecimal() {
        val b = WeatherBucket.from(42.814, -73.930)
        assertEquals(42.8, b.lat, 0.0)
        assertEquals(-73.9, b.lon, 0.0)
        assertEquals("42.8,-73.9", b.key)
    }

    @Test
    fun roundsToNearestTenth() {
        val b = WeatherBucket.from(42.86, -73.94)
        assertEquals("42.9,-73.9", b.key)
    }

    @Test
    fun keyHasAtMostOneDecimal() {
        val b = WeatherBucket.from(59.9139, 10.7521)
        assertEquals("59.9,10.8", b.key)
    }

    @Test
    fun sameAreaSharesBucket() {
        // ~10 km privacy bucket: nearby blocks map together.
        assertEquals(
            WeatherBucket.from(42.81, -73.93).key,
            WeatherBucket.from(42.84, -73.91).key,
        )
    }
}
