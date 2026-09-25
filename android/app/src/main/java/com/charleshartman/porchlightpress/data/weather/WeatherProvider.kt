package com.charleshartman.porchlightpress.data.weather

import java.io.IOException
import kotlinx.serialization.json.Json

/**
 * Provider interface (Phase 7 spec): forecast, hourly, alerts, current per
 * 0.1° bucket. Throwing is part of the contract — [WeatherRepository]
 * catches provider failures, serves cache, and falls back NWS → MET Norway.
 *
 * Results are wrapped in [WeatherOutcome] so the UI can show cached data
 * with an "as of" time when the provider is down (`stale = true`).
 */
class WeatherException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class WeatherOutcome<out T>(val value: T, val stale: Boolean, val fetchedAt: Long)

interface WeatherProvider {
    val id: String
    suspend fun current(bucket: WeatherBucket): WeatherOutcome<CurrentWeather>
    suspend fun hourly(bucket: WeatherBucket): WeatherOutcome<List<HourlyPoint>>
    suspend fun forecast(bucket: WeatherBucket): WeatherOutcome<List<DailyPoint>>
    suspend fun alerts(bucket: WeatherBucket): WeatherOutcome<List<WeatherAlert>>
}

// ---------------------------------------------------------------------------
// Raw per-URL cache (Room-backed by the repository). Honors Expires /
// Cache-Control, sends If-None-Match / If-Modified-Since, never refetches
// before expiry, and enforces the minimum refresh (15 min forecast, 5 min
// alerts). NWS /points is cached permanently per bucket.
// ---------------------------------------------------------------------------

data class RawCacheEntry(
    val body: String,
    val etag: String?,
    val lastModified: String?,
    val fetchedAt: Long,
    val expiresAt: Long,
)

interface WeatherCacheStore {
    suspend fun load(key: String): RawCacheEntry?
    suspend fun save(key: String, entry: RawCacheEntry)
}

data class CachedBody(
    val body: String,
    /** True when the network failed and a cached body was served instead. */
    val stale: Boolean,
    val fetchedAt: Long,
    val expiresAt: Long,
)

object CachedFetcher {
    suspend fun getCached(
        store: WeatherCacheStore,
        fetcher: WeatherFetcher,
        key: String,
        url: String,
        minRefreshMs: Long,
        permanent: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): CachedBody {
        val cached = store.load(key)
        if (cached != null && (permanent || now < cached.expiresAt || now - cached.fetchedAt < minRefreshMs)) {
            return CachedBody(cached.body, stale = false, cached.fetchedAt, cached.expiresAt)
        }
        val outcome = try {
            fetcher.get(url, cached?.etag, cached?.lastModified)
        } catch (e: IOException) {
            if (cached != null) return CachedBody(cached.body, stale = true, cached.fetchedAt, cached.expiresAt)
            throw WeatherException("Weather request failed for $url", e)
        }
        return when (outcome) {
            FetchOutcome.NotModified -> {
                if (cached == null) throw WeatherException("Unexpected 304 for uncached $url")
                // Touch the entry: keep the body, extend the window. 304
                // responses rarely repeat Expires, so fall back to the
                // minimum refresh rather than hammering the provider.
                val touched = cached.copy(fetchedAt = now, expiresAt = now + minRefreshMs)
                store.save(key, touched)
                CachedBody(touched.body, stale = false, touched.fetchedAt, touched.expiresAt)
            }
            is FetchOutcome.Fresh -> {
                val entry = RawCacheEntry(outcome.body, outcome.etag, outcome.lastModified, now, outcome.expiresAt)
                store.save(key, entry)
                CachedBody(entry.body, stale = false, entry.fetchedAt, entry.expiresAt)
            }
            is FetchOutcome.Error -> {
                if (cached != null) return CachedBody(cached.body, stale = true, cached.fetchedAt, cached.expiresAt)
                throw WeatherException(outcome.message)
            }
        }
    }
}

class NwsProvider(
    private val fetcher: WeatherFetcher,
    private val store: WeatherCacheStore,
    private val placeTz: String? = null,
) : WeatherProvider {
    override val id: String = "nws"

    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    companion object {
        const val MIN_FORECAST_MS: Long = 15L * 60L * 1000L
        const val MIN_ALERTS_MS: Long = 5L * 60L * 1000L
    }

    private suspend fun points(bucket: WeatherBucket): NwsPoints {
        val key = "${bucket.key}:nws-points"
        val cached = store.load(key)
        if (cached != null) {
            return runCatching { json.decodeFromString<NwsPoints>(cached.body) }.getOrNull()
                ?: fetchPoints(bucket, key)
        }
        return fetchPoints(bucket, key)
    }

    private suspend fun fetchPoints(bucket: WeatherBucket, key: String): NwsPoints {
        // Tiny, immutable per bucket: fetch once, keep permanently.
        val url = "https://api.weather.gov/points/${bucket.lat},${bucket.lon}"
        val outcome = try {
            fetcher.get(url, null, null)
        } catch (e: IOException) {
            throw WeatherException("NWS /points failed for ${bucket.key}", e)
        }
        val body = when (outcome) {
            is FetchOutcome.Fresh -> outcome.body
            FetchOutcome.NotModified -> throw WeatherException("Unexpected 304 for $url")
            is FetchOutcome.Error -> throw WeatherException(outcome.message)
        }
        // The /points body is small; the parsed gridpoint set is what we keep.
        val props = json.decodeFromString<NwsPointsResponse>(body).properties
        if (props.forecast.isBlank() || props.forecastHourly.isBlank()) {
            throw WeatherException("NWS /points has no forecast URLs for ${bucket.key}")
        }
        val pts = NwsPoints(
            forecastUrl = props.forecast,
            hourlyUrl = props.forecastHourly,
            observationStationsUrl = props.observationStations,
            zone = props.forecastZone,
        )
        store.save(key, RawCacheEntry(json.encodeToString(NwsPoints.serializer(), pts), null, null, System.currentTimeMillis(), Long.MAX_VALUE))
        return pts
    }

    override suspend fun forecast(bucket: WeatherBucket): WeatherOutcome<List<DailyPoint>> {
        val pts = points(bucket)
        val got = CachedFetcher.getCached(store, fetcher, "${bucket.key}:nws-forecast", pts.forecastUrl, MIN_FORECAST_MS)
        val periods = json.decodeFromString<NwsForecastResponse>(got.body).properties.periods
        return WeatherOutcome(periods.toDaily(placeTz), got.stale, got.fetchedAt)
    }

    override suspend fun hourly(bucket: WeatherBucket): WeatherOutcome<List<HourlyPoint>> {
        val pts = points(bucket)
        val got = CachedFetcher.getCached(store, fetcher, "${bucket.key}:nws-hourly", pts.hourlyUrl, MIN_FORECAST_MS)
        val periods = json.decodeFromString<NwsForecastResponse>(got.body).properties.periods
        return WeatherOutcome(periods.take(24).map { it.toHourly() }, got.stale, got.fetchedAt)
    }

    override suspend fun current(bucket: WeatherBucket): WeatherOutcome<CurrentWeather> {
        val hourlyOutcome = runCatching { hourly(bucket) }.getOrNull()
        val hourlyFirst = hourlyOutcome?.value?.firstOrNull()
        val pts = points(bucket)
        if (pts.observationStationsUrl.isBlank()) {
            val h = hourlyFirst ?: throw WeatherException("No observation station for ${bucket.key}")
            return WeatherOutcome(
                CurrentWeather(h.tempC, WeatherMath.feelsLikeC(h.tempC, null, null), null, null, null, h.condition, h.shortText, h.precipPct),
                stale = hourlyOutcome?.stale ?: false,
                fetchedAt = hourlyOutcome?.fetchedAt ?: System.currentTimeMillis(),
            )
        }
        val stationsGot = CachedFetcher.getCached(store, fetcher, "${bucket.key}:nws-stations", pts.observationStationsUrl, MIN_FORECAST_MS)
        val station = json.decodeFromString<NwsStationsResponse>(stationsGot.body).features.firstOrNull()
        if (station == null || station.properties.stationIdentifier.isBlank()) {
            val h = hourlyFirst ?: throw WeatherException("No observation station for ${bucket.key}")
            return WeatherOutcome(
                CurrentWeather(h.tempC, WeatherMath.feelsLikeC(h.tempC, null, null), null, null, null, h.condition, h.shortText, h.precipPct),
                stale = stationsGot.stale || hourlyOutcome?.stale == true,
                fetchedAt = stationsGot.fetchedAt,
            )
        }
        val obsUrl = "https://api.weather.gov/stations/${station.properties.stationIdentifier}/observations/latest"
        val obsGot = CachedFetcher.getCached(store, fetcher, "${bucket.key}:nws-obs", obsUrl, MIN_FORECAST_MS)
        val obs = json.decodeFromString<NwsObservationResponse>(obsGot.body).properties
        return WeatherOutcome(obs.toCurrent(hourlyFirst), obsGot.stale || stationsGot.stale, obsGot.fetchedAt)
    }

    override suspend fun alerts(bucket: WeatherBucket): WeatherOutcome<List<WeatherAlert>> {
        val url = "https://api.weather.gov/alerts/active?point=${bucket.lat},${bucket.lon}"
        val got = CachedFetcher.getCached(store, fetcher, "${bucket.key}:nws-alerts", url, MIN_ALERTS_MS)
        val alerts = json.decodeFromString<NwsAlertsResponse>(got.body).features.map { it.toAlert() }
        return WeatherOutcome(alerts, got.stale, got.fetchedAt)
    }
}

/**
 * MET Norway provider (worldwide + US fallback). One compact document feeds
 * current/hourly/forecast; alerts are always empty (marked unavailable by
 * the repository). Results are memoized briefly in memory so the three
 * interface calls made back-to-back cost one HTTP request.
 */
class MetNoProvider(
    private val fetcher: WeatherFetcher,
    private val store: WeatherCacheStore,
    private val placeTz: String? = null,
) : WeatherProvider {
    override val id: String = "met"

    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    private var memoKey: String? = null
    private var memoAt: Long = 0L
    private var memo: WeatherOutcome<Triple<CurrentWeather, List<HourlyPoint>, List<DailyPoint>>>? = null

    @Synchronized
    private fun memoGet(key: String): WeatherOutcome<Triple<CurrentWeather, List<HourlyPoint>, List<DailyPoint>>>? {
        if (memoKey == key && System.currentTimeMillis() - memoAt < 60_000L) return memo
        return null
    }

    @Synchronized
    private fun memoPut(
        key: String,
        value: WeatherOutcome<Triple<CurrentWeather, List<HourlyPoint>, List<DailyPoint>>>,
    ) {
        memoKey = key
        memoAt = System.currentTimeMillis()
        memo = value
    }

    private suspend fun load(bucket: WeatherBucket): WeatherOutcome<Triple<CurrentWeather, List<HourlyPoint>, List<DailyPoint>>> {
        memoGet(bucket.key)?.let { return it }
        // MET requires ≤ 4 decimals (bucket has 1) + identifying User-Agent
        // (WeatherHttp/NetworkModule) + conditional requests (CachedFetcher).
        val url = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${bucket.lat}&lon=${bucket.lon}"
        val got = CachedFetcher.getCached(store, fetcher, "${bucket.key}:met-compact", url, NwsProvider.MIN_FORECAST_MS)
        val parsed = json.decodeFromString<MetCompact>(got.body).toDomain(placeTz)
        val out = WeatherOutcome(parsed, got.stale, got.fetchedAt)
        memoPut(bucket.key, out)
        return out
    }

    override suspend fun current(bucket: WeatherBucket): WeatherOutcome<CurrentWeather> {
        val o = load(bucket)
        return WeatherOutcome(o.value.first, o.stale, o.fetchedAt)
    }

    override suspend fun hourly(bucket: WeatherBucket): WeatherOutcome<List<HourlyPoint>> {
        val o = load(bucket)
        return WeatherOutcome(o.value.second, o.stale, o.fetchedAt)
    }

    override suspend fun forecast(bucket: WeatherBucket): WeatherOutcome<List<DailyPoint>> {
        val o = load(bucket)
        return WeatherOutcome(o.value.third, o.stale, o.fetchedAt)
    }

    override suspend fun alerts(bucket: WeatherBucket): WeatherOutcome<List<WeatherAlert>> =
        WeatherOutcome(emptyList(), stale = false, fetchedAt = System.currentTimeMillis())
}

/** Test helper: provider that always fails (drives fallback tests). */
class FailingWeatherProvider(override val id: String = "nws") : WeatherProvider {
    private fun fail(): Nothing = throw WeatherException("failing provider")
    override suspend fun current(bucket: WeatherBucket): WeatherOutcome<CurrentWeather> = fail()
    override suspend fun hourly(bucket: WeatherBucket): WeatherOutcome<List<HourlyPoint>> = fail()
    override suspend fun forecast(bucket: WeatherBucket): WeatherOutcome<List<DailyPoint>> = fail()
    override suspend fun alerts(bucket: WeatherBucket): WeatherOutcome<List<WeatherAlert>> = fail()
}

/** Test helper: canned provider data. */
class FakeWeatherProvider(
    override val id: String,
    var current: CurrentWeather = CurrentWeather(20.0, 20.0),
    var hourly: List<HourlyPoint> = emptyList(),
    var forecast: List<DailyPoint> = emptyList(),
    var alerts: List<WeatherAlert> = emptyList(),
    var stale: Boolean = false,
) : WeatherProvider {
    var calls = 0
    private fun <T> wrap(v: T): WeatherOutcome<T> {
        calls++
        return WeatherOutcome(v, stale, System.currentTimeMillis())
    }
    override suspend fun current(bucket: WeatherBucket): WeatherOutcome<CurrentWeather> = wrap(current)
    override suspend fun hourly(bucket: WeatherBucket): WeatherOutcome<List<HourlyPoint>> = wrap(hourly)
    override suspend fun forecast(bucket: WeatherBucket): WeatherOutcome<List<DailyPoint>> = wrap(forecast)
    override suspend fun alerts(bucket: WeatherBucket): WeatherOutcome<List<WeatherAlert>> = wrap(alerts)
}

class InMemoryWeatherCacheStore : WeatherCacheStore {
    private val map = mutableMapOf<String, RawCacheEntry>()
    override suspend fun load(key: String): RawCacheEntry? = map[key]
    override suspend fun save(key: String, entry: RawCacheEntry) { map[key] = entry }
}
