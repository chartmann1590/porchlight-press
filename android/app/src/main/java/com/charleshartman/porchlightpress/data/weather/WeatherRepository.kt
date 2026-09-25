package com.charleshartman.porchlightpress.data.weather

import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.WeatherCache
import com.charleshartman.porchlightpress.domain.Place
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** Room-backed [WeatherCacheStore]: raw per-URL bodies, keyed "$bucket:<kind>". */
class RoomWeatherCacheStore(private val db: AppDatabase) : WeatherCacheStore {
    override suspend fun load(key: String): RawCacheEntry? {
        val row = db.weatherDao().byBucket(key) ?: return null
        return RawCacheEntry(row.payloadJson, row.etag, row.lastModified, row.fetchedAt, row.expiresAt)
    }

    override suspend fun save(key: String, entry: RawCacheEntry) {
        db.weatherDao().upsert(
            WeatherCache(
                bucketKey = key,
                provider = "raw",
                payloadJson = entry.body,
                fetchedAt = entry.fetchedAt,
                expiresAt = entry.expiresAt,
                etag = entry.etag,
                lastModified = entry.lastModified,
            ),
        )
    }
}

/**
 * Weather repository: bucket rounding, provider selection, fallback, and
 * graceful degradation. US places use NWS first with MET Norway as the
 * fallback when NWS fails; everywhere else uses MET Norway directly (alerts
 * then marked unavailable). Room [WeatherCache] backs the raw per-URL store
 * (permanent NWS gridpoints, expiring forecast/alerts with validators).
 */
class WeatherRepository(
    private val store: WeatherCacheStore,
    private val nws: WeatherProvider,
    private val met: WeatherProvider,
    private val coords: CoordsResolver,
) {

    // -- Public API ----------------------------------------------------------

    /** Bucket for a place, or null when coordinates can't be resolved. */
    suspend fun bucketFor(place: Place): WeatherBucket? {
        val (lat, lon) = coords.coordsFor(place) ?: return null
        if (lat.isNaN() || lon.isNaN()) return null
        return WeatherBucket.from(lat, lon)
    }

    sealed interface Snapshot {
        /** Live or fresh-cache data. `stale` = provider down, cached "as of". */
        data class Ready(val data: WeatherData, val stale: Boolean) : Snapshot
        /** No coordinates, or no cache and every provider failed. */
        data class Unavailable(val reason: String) : Snapshot
    }

    /**
     * Full snapshot for a place: current + hourly + daily + alerts.
     * Forecast pieces come from NWS (US) or MET (elsewhere / NWS failure);
     * alerts always come from NWS when the place is in the US.
     *
     * Runs on Dispatchers.IO: providers block on HTTP + parse, and callers
     * (ViewModels) collect on Main.
     */
    suspend fun snapshot(place: Place): Snapshot = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        snapshotInner(place)
    }

    private suspend fun snapshotInner(place: Place): Snapshot = coroutineScope {
        val bucket = bucketFor(place)
            ?: return@coroutineScope Snapshot.Unavailable("Weather unavailable")
        val us = place.country.equals("US", ignoreCase = true)
        try {
            if (us) {
                val currentD = async { runCatching { nws.current(bucket) } }
                val hourlyD = async { runCatching { nws.hourly(bucket) } }
                val forecastD = async { runCatching { nws.forecast(bucket) } }
                val alertsD = async { runCatching { nws.alerts(bucket) } }
                val current = currentD.await().getOrNull()
                val hourly = hourlyD.await().getOrNull()
                val forecast = forecastD.await().getOrNull()
                val alerts = alertsD.await().getOrNull()
                if (current != null && hourly != null && forecast != null) {
                    return@coroutineScope Snapshot.Ready(
                        WeatherData(
                            current = current.value,
                            hourly = hourly.value,
                            daily = forecast.value,
                            alerts = (alerts?.value ?: emptyList()).sortedWith(
                                compareBy({ it.severityRank() }, { it.effective ?: "" }),
                            ),
                            provider = "nws",
                            alertsUnavailable = alerts == null,
                            fetchedAt = minOf(current.fetchedAt, hourly.fetchedAt, forecast.fetchedAt),
                            expiresAt = minOf(current.fetchedAt, hourly.fetchedAt, forecast.fetchedAt),
                        ),
                        stale = current.stale || hourly.stale || forecast.stale || alerts?.stale == true,
                    )
                }
                // NWS failed: MET fallback for the forecast (alerts retried
                // once more below so a partial NWS outage still shows them).
                return@coroutineScope metSnapshot(place, bucket, alerts?.value)
            } else {
                return@coroutineScope metSnapshot(place, bucket, null)
            }
        } catch (e: Exception) {
            android.util.Log.w("Porchlight", "Weather snapshot failed for ${place.label} ${bucket.key}", e)
            if (us) {
                return@coroutineScope runCatching { metSnapshot(place, bucket, null) }
                    .getOrElse { Snapshot.Unavailable("Weather unavailable") }
            }
            return@coroutineScope Snapshot.Unavailable("Weather unavailable")
        }
    }

    private suspend fun metSnapshot(
        place: Place,
        bucket: WeatherBucket,
        nwsAlerts: List<WeatherAlert>?,
    ): Snapshot {
        val current = met.current(bucket)
        val hourly = met.hourly(bucket)
        val forecast = met.forecast(bucket)
        val us = place.country.equals("US", ignoreCase = true)
        // US fallback path: keep NWS alerts when we have them; otherwise try
        // NWS alerts once (MET has no alerts endpoint).
        val alerts: List<WeatherAlert>? = when {
            nwsAlerts != null -> nwsAlerts
            us -> runCatching { nws.alerts(bucket).value }.getOrNull()
            else -> null
        }
        return Snapshot.Ready(
            WeatherData(
                current = current.value,
                hourly = hourly.value,
                daily = forecast.value,
                alerts = (alerts ?: emptyList()).sortedWith(
                    compareBy({ it.severityRank() }, { it.effective ?: "" }),
                ),
                provider = "met",
                alertsUnavailable = alerts == null,
                fetchedAt = minOf(current.fetchedAt, hourly.fetchedAt, forecast.fetchedAt),
                expiresAt = minOf(current.fetchedAt, hourly.fetchedAt, forecast.fetchedAt),
            ),
            stale = current.stale || hourly.stale || forecast.stale,
        )
    }

    /**
     * Alerts-only refresh for [AlertCheckWorker]: same provider rules, but
     * only the alerts endpoint (5-min TTL inside the provider cache).
     * IO-shifted like [snapshot]: safe from any caller thread.
     */
    suspend fun alertsFor(place: Place): AlertsResult = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO,
    ) {
        val bucket = bucketFor(place) ?: return@withContext AlertsResult.Unavailable
        if (!place.country.equals("US", ignoreCase = true)) return@withContext AlertsResult.None
        try {
            val out = nws.alerts(bucket)
            AlertsResult.Alerts(out.value, out.stale)
        } catch (e: Exception) {
            android.util.Log.w("Porchlight", "Weather alerts failed for ${bucket.key}", e)
            AlertsResult.Unavailable
        }
    }

    sealed interface AlertsResult {
        data class Alerts(val alerts: List<WeatherAlert>, val stale: Boolean) : AlertsResult
        data object None : AlertsResult
        data object Unavailable : AlertsResult
    }

    /** Find one cached alert for the detail screen (re-reads NWS cache). */
    suspend fun alertById(place: Place, alertId: String): WeatherAlert? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val bucket = bucketFor(place) ?: return@withContext null
            val alerts = runCatching {
                when {
                    place.country.equals("US", ignoreCase = true) -> nws.alerts(bucket).value
                    else -> emptyList()
                }
            }.getOrDefault(emptyList())
            alerts.firstOrNull { it.id == alertId }
        }
}
