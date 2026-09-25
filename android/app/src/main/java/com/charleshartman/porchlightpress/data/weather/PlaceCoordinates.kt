package com.charleshartman.porchlightpress.data.weather

import android.content.Context
import android.location.Geocoder
import com.charleshartman.porchlightpress.domain.Place
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Resolve a followed [Place] to coordinates for the weather bucket.
 * Order: stored lat/lon (GPS path) → on-device Android Geocoder
 * (forward-geocode of the place label; cached nowhere, resolved per
 * refresh) → null (weather shows "unavailable", the paper still works).
 * Raw coordinates are never stored; only the rounded 0.1° bucket leaves
 * the device (in the provider request path).
 */
interface CoordsResolver {
    suspend fun coordsFor(place: Place): Pair<Double, Double>?
}

class AndroidCoordsResolver(private val context: Context) : CoordsResolver {
    override suspend fun coordsFor(place: Place): Pair<Double, Double>? {
        if (place.lat != null && place.lon != null) {
            return place.lat to place.lon
        }
        return withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            val queries = buildList {
                if (place.city != null) {
                    val admin = place.admin1?.substringAfter("-") ?: place.country
                    add("${place.city}, $admin")
                }
                add(place.label)
                if (place.admin2 != null) add(place.admin2)
            }
            for (q in queries) {
                try {
                    @Suppress("DEPRECATION")
                    val hits = Geocoder(context, Locale.getDefault()).getFromLocationName(q, 1)
                    val h = hits?.firstOrNull()
                    if (h != null) return@withContext h.latitude to h.longitude
                } catch (e: IOException) {
                    // Offline / no backend: try the next query, then give up.
                } catch (e: IllegalArgumentException) {
                    // Bad query string: try the next one.
                }
            }
            null
        }
    }
}

/** Test fake: canned coordinates per place id (or a constant). */
class FakeCoordsResolver(
    private val coords: Pair<Double, Double>? = 42.81 to -73.93,
) : CoordsResolver {
    val byPlace = mutableMapOf<String, Pair<Double, Double>?>()
    var calls = 0
    override suspend fun coordsFor(place: Place): Pair<Double, Double>? {
        calls++
        return if (byPlace.containsKey(place.id)) byPlace[place.id] else coords
    }
}
