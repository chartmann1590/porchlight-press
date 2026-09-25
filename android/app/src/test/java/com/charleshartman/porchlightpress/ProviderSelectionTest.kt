package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.CurrentWeather
import com.charleshartman.porchlightpress.data.weather.DailyPoint
import com.charleshartman.porchlightpress.data.weather.FakeCoordsResolver
import com.charleshartman.porchlightpress.data.weather.FakeWeatherProvider
import com.charleshartman.porchlightpress.data.weather.FetchOutcome
import com.charleshartman.porchlightpress.data.weather.HourlyPoint
import com.charleshartman.porchlightpress.data.weather.InMemoryWeatherCacheStore
import com.charleshartman.porchlightpress.data.weather.MetNoProvider
import com.charleshartman.porchlightpress.data.weather.NwsProvider
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.data.weather.WeatherBucket
import com.charleshartman.porchlightpress.data.weather.WeatherCondition
import com.charleshartman.porchlightpress.data.weather.WeatherException
import com.charleshartman.porchlightpress.data.weather.WeatherOutcome
import com.charleshartman.porchlightpress.data.weather.WeatherProvider
import com.charleshartman.porchlightpress.data.weather.WeatherRepository
import com.charleshartman.porchlightpress.domain.Place
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider selection: US → NWS, non-US → MET, NWS error → MET fallback.
 * Plus the real NWS provider against fixture HTTP (points cached
 * permanently, alerts parsed, validators sent on re-fetch).
 */
class ProviderSelectionTest {

    private val usPlace = Place(
        id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
        admin1 = "US-NY", city = "Schenectady", tz = "America/New_York",
    )
    private val noPlace = Place(
        id = "place:no:oslo", label = "Oslo", country = "NO",
        city = "Oslo", tz = "Europe/Oslo",
    )
    private val coords = FakeCoordsResolver(42.81 to -73.93)

    private fun alert(event: String, severity: String, id: String = event) = WeatherAlert(
        id = id, event = event, headline = "$event headline",
        description = "desc", severity = severity, sender = "NWS",
    )

    private fun repo(nws: WeatherProvider, met: WeatherProvider) =
        WeatherRepository(InMemoryWeatherCacheStore(), nws, met, coords)

    @Test
    fun usUsesNwsWithSortedAlerts() = runTest {
        val nws = FakeWeatherProvider(
            id = "nws",
            current = CurrentWeather(20.0, 20.0, condition = WeatherCondition.CLEAR, shortText = "Sunny"),
            hourly = listOf(HourlyPoint("2026-09-24T14:00:00-04:00", 20.0)),
            forecast = listOf(DailyPoint("2026-09-24", 23.0, 14.0)),
            alerts = listOf(
                alert("Heat Advisory", "Minor", "c"),
                alert("Flood Warning", "Severe", "a"),
                alert("Tornado Warning", "Extreme", "b"),
            ),
        )
        val met = FakeWeatherProvider(id = "met")
        val snap = repo(nws, met).snapshot(usPlace)
        check(snap is WeatherRepository.Snapshot.Ready)
        assertEquals("nws", snap.data.provider)
        assertFalse(snap.data.alertsUnavailable)
        assertEquals(
            listOf("Tornado Warning", "Flood Warning", "Heat Advisory"),
            snap.data.alerts.map { it.event },
        )
        assertTrue(nws.calls > 0)
        assertEquals(0, met.calls)
    }

    @Test
    fun nonUsUsesMetWithAlertsUnavailable() = runTest {
        val nws = FakeWeatherProvider(id = "nws")
        val met = FakeWeatherProvider(
            id = "met",
            current = CurrentWeather(14.2, 14.2, condition = WeatherCondition.RAIN, shortText = "Rain"),
            hourly = listOf(HourlyPoint("2026-09-24T12:00:00Z", 14.2)),
            forecast = listOf(DailyPoint("2026-09-24", 15.1, 11.8)),
        )
        val snap = repo(nws, met).snapshot(noPlace)
        check(snap is WeatherRepository.Snapshot.Ready)
        assertEquals("met", snap.data.provider)
        assertTrue(snap.data.alertsUnavailable)
        assertTrue(snap.data.alerts.isEmpty())
        assertEquals(0, nws.calls)
    }

    @Test
    fun nwsTotalFailureFallsBackToMet() = runTest {
        val snap = repo(
            com.charleshartman.porchlightpress.data.weather.FailingWeatherProvider("nws"),
            FakeWeatherProvider(
                id = "met",
                current = CurrentWeather(20.0, 20.0),
                hourly = listOf(HourlyPoint("t", 20.0)),
                forecast = listOf(DailyPoint("2026-09-24", 23.0, 14.0)),
            ),
        ).snapshot(usPlace)
        check(snap is WeatherRepository.Snapshot.Ready)
        assertEquals("met", snap.data.provider)
        assertTrue(snap.data.alertsUnavailable)
    }

    @Test
    fun nwsForecastFailureKeepsNwsAlerts() = runTest {
        // Partial NWS outage: forecast endpoints down, alerts endpoint up.
        val partialNws = object : WeatherProvider {
            override val id = "nws"
            private fun fail(): Nothing = throw WeatherException("forecast down")
            override suspend fun current(b: WeatherBucket): WeatherOutcome<CurrentWeather> = fail()
            override suspend fun hourly(b: WeatherBucket): WeatherOutcome<List<HourlyPoint>> = fail()
            override suspend fun forecast(b: WeatherBucket): WeatherOutcome<List<DailyPoint>> = fail()
            override suspend fun alerts(b: WeatherBucket): WeatherOutcome<List<WeatherAlert>> =
                WeatherOutcome(listOf(alert("Flood Warning", "Severe")), false, 1L)
        }
        val snap = repo(
            partialNws,
            FakeWeatherProvider(
                id = "met",
                current = CurrentWeather(20.0, 20.0),
                hourly = listOf(HourlyPoint("t", 20.0)),
                forecast = listOf(DailyPoint("2026-09-24", 23.0, 14.0)),
            ),
        ).snapshot(usPlace)
        check(snap is WeatherRepository.Snapshot.Ready)
        assertEquals("met", snap.data.provider)
        assertFalse(snap.data.alertsUnavailable)
        assertEquals(listOf("Flood Warning"), snap.data.alerts.map { it.event })
    }

    @Test
    fun noCoordsIsUnavailable() = runTest {
        val r = WeatherRepository(
            InMemoryWeatherCacheStore(),
            FakeWeatherProvider(id = "nws"),
            FakeWeatherProvider(id = "met"),
            FakeCoordsResolver(null),
        )
        val snap = r.snapshot(usPlace)
        check(snap is WeatherRepository.Snapshot.Unavailable)
    }

    @Test
    fun realNwsProviderAgainstFixtures() = runTest {
        val fetcher = FakeWeatherFetcher { url, _, _ ->
            val body = when {
                "/alerts/active" in url -> testResource("nws-alerts.json")
                "forecast/hourly" in url -> testResource("nws-hourly.json")
                "/gridpoints/" in url && url.endsWith("/stations") -> testResource("nws-stations.json")
                "/stations/" in url -> testResource("nws-observation.json")
                "/forecast" in url -> testResource("nws-forecast.json")
                "/points/" in url -> testResource("nws-points.json")
                else -> throw AssertionError("unexpected url $url")
            }
            fresh(body, etag = "etag-${body.length}")
        }
        val store = InMemoryWeatherCacheStore()
        val nws = NwsProvider(fetcher, store, "America/New_York")
        val bucket = WeatherBucket.from(42.814, -73.930)

        val cur = nws.current(bucket)
        assertEquals(21.1, cur.value.tempC, 0.01)
        assertFalse(cur.stale)
        val hourly = nws.hourly(bucket)
        assertEquals(3, hourly.value.size)
        val forecast = nws.forecast(bucket)
        assertEquals(2, forecast.value.size)
        val alerts = nws.alerts(bucket)
        assertEquals(3, alerts.value.size)

        // /points fetched once, then cached permanently per bucket.
        assertEquals(1, fetcher.callsTo("/points/").size)
        nws.current(bucket)
        assertEquals(1, fetcher.callsTo("/points/").size)
    }

    @Test
    fun realMetProviderAgainstFixture() = runTest {
        val fetcher = FakeWeatherFetcher { url, _, _ ->
            check("locationforecast" in url)
            fresh(testResource("met-compact.json"))
        }
        val met = MetNoProvider(fetcher, InMemoryWeatherCacheStore(), "Europe/Oslo")
        val bucket = WeatherBucket.from(59.914, 10.752)
        assertEquals(14.2, met.current(bucket).value.tempC, 0.01)
        assertEquals(16, met.hourly(bucket).value.size)
        assertEquals(2, met.forecast(bucket).value.size)
        assertTrue(met.alerts(bucket).value.isEmpty())
        // Three interface calls back-to-back cost one HTTP request (memo).
        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun nwsPropagatesHeaderExpiryToOutcomes() = runTest {
        // Expiry comes from the response headers (Expires/Cache-Control),
        // not from fetchedAt: every outcome must carry the header value.
        val headerExpiry = 9_999_999_999L
        val fetcher = FakeWeatherFetcher { url, _, _ ->
            val body = when {
                "/alerts/active" in url -> testResource("nws-alerts.json")
                "forecast/hourly" in url -> testResource("nws-hourly.json")
                "/gridpoints/" in url && url.endsWith("/stations") -> testResource("nws-stations.json")
                "/stations/" in url -> testResource("nws-observation.json")
                "/forecast" in url -> testResource("nws-forecast.json")
                "/points/" in url -> testResource("nws-points.json")
                else -> throw AssertionError("unexpected url $url")
            }
            FetchOutcome.Fresh(body, etag = null, lastModified = null, expiresAt = headerExpiry)
        }
        val nws = NwsProvider(fetcher, InMemoryWeatherCacheStore(), "America/New_York")
        val bucket = WeatherBucket.from(42.814, -73.930)
        assertEquals(headerExpiry, nws.forecast(bucket).expiresAt)
        assertEquals(headerExpiry, nws.hourly(bucket).expiresAt)
        assertEquals(headerExpiry, nws.current(bucket).expiresAt)
        assertEquals(headerExpiry, nws.alerts(bucket).expiresAt)
    }

    @Test
    fun snapshotExpiryIsMinOfHeaderExpiries() = runTest {
        // Regression: WeatherData.expiresAt must be the minimum of the
        // pieces' header expiries — never min(fetchedAt).
        val expiring = object : WeatherProvider {
            override val id = "nws"
            override suspend fun current(b: WeatherBucket) =
                WeatherOutcome(CurrentWeather(20.0, 20.0), false, 1000L, 5000L)
            override suspend fun hourly(b: WeatherBucket) =
                WeatherOutcome(listOf(HourlyPoint("t", 20.0)), false, 2000L, 3000L)
            override suspend fun forecast(b: WeatherBucket) =
                WeatherOutcome(listOf(DailyPoint("2026-09-24", 23.0, 14.0)), false, 1500L, 7000L)
            override suspend fun alerts(b: WeatherBucket) =
                WeatherOutcome(emptyList<WeatherAlert>(), false, 1800L, 6000L)
        }
        val snap = WeatherRepository(InMemoryWeatherCacheStore(), expiring, FakeWeatherProvider("met"), coords)
            .snapshot(usPlace)
        check(snap is WeatherRepository.Snapshot.Ready)
        assertEquals(1000L, snap.data.fetchedAt)
        assertEquals(3000L, snap.data.expiresAt)
    }
}
