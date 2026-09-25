package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.weather.CachedFetcher
import com.charleshartman.porchlightpress.data.weather.FetchOutcome
import com.charleshartman.porchlightpress.data.weather.InMemoryWeatherCacheStore
import com.charleshartman.porchlightpress.data.weather.OkHttpWeatherFetcher
import com.charleshartman.porchlightpress.data.weather.RawCacheEntry
import com.charleshartman.porchlightpress.data.weather.WeatherException
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Cache honoring: no network call before expiry, 304 handling with
 * validators, minimum-refresh gating, stale-on-error, throw-when-empty.
 */
class WeatherCacheTest {

    @Test
    fun freshFetchStoresEntry() = runTest {
        val store = InMemoryWeatherCacheStore()
        val fetcher = FakeWeatherFetcher { _, _, _ -> fresh("body-1", etag = "e1") }
        val got = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 1_000L)
        assertEquals("body-1", got.body)
        assertFalse(got.stale)
        assertEquals(1, fetcher.calls.size)
        assertEquals("e1", store.load("k")?.etag)
    }

    @Test
    fun noNetworkBeforeExpiry() = runTest {
        val store = InMemoryWeatherCacheStore()
        store.save("k", RawCacheEntry("old", "e1", null, fetchedAt = 1_000L, expiresAt = 500_000L))
        val fetcher = FakeWeatherFetcher { _, _, _ -> fresh("new") }
        val got = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 2_000L)
        assertEquals("old", got.body)
        assertFalse(got.stale)
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun expiredSendsValidatorsAndHandles304() = runTest {
        val store = InMemoryWeatherCacheStore()
        store.save("k", RawCacheEntry("old", "e1", "lm1", fetchedAt = 1_000L, expiresAt = 2_000L))
        val fetcher = FakeWeatherFetcher { _, _, _ -> FetchOutcome.NotModified }
        // Past expiry AND past the minimum refresh: conditional request.
        val got = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 500_000L)
        assertEquals("old", got.body)
        assertFalse(got.stale)
        assertEquals(1, fetcher.calls.size)
        assertEquals("e1", fetcher.calls.single().etag)
        assertEquals("lm1", fetcher.calls.single().lastModified)
        // 304 touches the entry: a repeat call stays cached (min refresh).
        val again = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 500_001L)
        assertEquals("old", again.body)
        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun networkErrorServesStaleCache() = runTest {
        val store = InMemoryWeatherCacheStore()
        store.save("k", RawCacheEntry("old", null, null, fetchedAt = 1_000L, expiresAt = 2_000L))
        val fetcher = FakeWeatherFetcher { _, _, _ -> throw java.io.IOException("offline") }
        val got = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 500_000L)
        assertEquals("old", got.body)
        assertTrue(got.stale)
    }

    @Test
    fun networkErrorWithoutCacheThrows() = runTest {
        val store = InMemoryWeatherCacheStore()
        val fetcher = FakeWeatherFetcher { _, _, _ -> throw java.io.IOException("offline") }
        try {
            CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 1_000L)
            fail("expected WeatherException")
        } catch (e: WeatherException) {
            assertTrue(e.message!!.contains("https://x/y"))
        }
    }

    @Test
    fun httpErrorWithCacheIsStale() = runTest {
        val store = InMemoryWeatherCacheStore()
        store.save("k", RawCacheEntry("old", null, null, fetchedAt = 1_000L, expiresAt = 2_000L))
        val fetcher = FakeWeatherFetcher { _, _, _ -> FetchOutcome.Error(500, "boom") }
        val got = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 500_000L)
        assertEquals("old", got.body)
        assertTrue(got.stale)
    }

    @Test
    fun permanentPointsNeverRefetch() = runTest {
        val store = InMemoryWeatherCacheStore()
        store.save("k", RawCacheEntry("pts", null, null, fetchedAt = 1_000L, expiresAt = Long.MAX_VALUE))
        val fetcher = FakeWeatherFetcher { _, _, _ -> fresh("new") }
        val got = CachedFetcher.getCached(store, fetcher, "k", "https://x/y", 60_000L, now = 9_999_999_999L, permanent = true)
        assertEquals("pts", got.body)
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun maxAgeWinsOverExpires() {
        val now = 1_000_000L
        val h = Headers.headersOf(
            "Cache-Control", "max-age=3600",
            "Expires", "Thu, 24 Sep 2026 12:00:00 GMT",
        )
        assertEquals(now + 3_600_000L, OkHttpWeatherFetcher.parseExpiresAt(h, now, 60_000L))
    }

    @Test
    fun expiresUsedWithoutMaxAge() {
        val now = 1_700_000_000_000L // Sep 2023, before the fixture date.
        val h = Headers.headersOf("Expires", "Thu, 24 Sep 2026 12:00:00 GMT")
        val expected = java.time.ZonedDateTime.parse(
            "Thu, 24 Sep 2026 12:00:00 GMT",
            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
        ).toInstant().toEpochMilli()
        assertEquals(expected, OkHttpWeatherFetcher.parseExpiresAt(h, now, 60_000L))
    }

    @Test
    fun defaultTtlWithoutHeaders() {
        val now = 1_000_000L
        val h = Headers.headersOf()
        assertEquals(now + 60_000L, OkHttpWeatherFetcher.parseExpiresAt(h, now, 60_000L))
    }
}
