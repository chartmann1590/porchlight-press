package com.charleshartman.porchlightpress.data.repo

import android.content.Context
import android.location.Geocoder
import com.charleshartman.porchlightpress.BuildConfig
import com.charleshartman.porchlightpress.data.remote.FeedApi
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.PlaceDto
import com.charleshartman.porchlightpress.data.remote.PlacesDto
import com.charleshartman.porchlightpress.data.remote.PostalDto
import com.charleshartman.porchlightpress.data.remote.PostalEntryDto
import com.charleshartman.porchlightpress.domain.Place
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

/**
 * Pickers backed by public/locations/{country}.json, downloaded on first use
 * and cached on-device. Gazetteer licenses/attribution: see GeoAttribution.
 */
class LocationRepository(
    private val context: Context,
    private val api: FeedApi,
    private val supportedMajor: Int = BuildConfig.SUPPORTED_FEED_API_MAJOR,
) {
    private val json = NetworkModule.feedJson

    suspend fun places(countryIso2: String): List<PlaceDto> =
        withContext(Dispatchers.IO) {
            val cc = countryIso2.lowercase()
            val cached = readCache<List<PlaceDto>>("locations-$cc.json")
            try {
                val resp = api.places("locations/$cc.json")
                val body = resp.body()
                if (resp.isSuccessful && body != null && body.apiVersion <= supportedMajor) {
                    writeCache("locations-$cc.json", body.places)
                    return@withContext body.places
                }
            } catch (e: IOException) {
                // Offline: fall through to cache.
            }
            cached ?: emptyList()
        }

    suspend fun postalEntries(countryIso2: String): List<PostalEntryDto> =
        withContext(Dispatchers.IO) {
            val cc = countryIso2.lowercase()
            val cached = readCache<List<PostalEntryDto>>("postal-$cc.json")
            try {
                val resp = api.postal("locations/$cc-postal.json")
                val body = resp.body()
                if (resp.isSuccessful && body != null && body.apiVersion <= supportedMajor) {
                    writeCache("postal-$cc.json", body.postal)
                    return@withContext body.postal
                }
            } catch (e: IOException) {
                // Offline: fall through to cache.
            }
            cached ?: emptyList()
        }

    // -- Cascading picker helpers (pure, unit-tested) -----------------------

    fun countries(places: List<PlaceDto>): List<PlaceDto> =
        places.filter { it.type == "country" }.sortedBy { it.name }

    fun admin1s(places: List<PlaceDto>, country: String): List<PlaceDto> =
        places.filter { it.type == "admin1" && it.country.equals(country, true) }
            .sortedBy { it.name }

    fun counties(places: List<PlaceDto>, admin1: String?): List<PlaceDto> =
        places.filter { it.type == "admin2" && (admin1 == null || it.admin1 == admin1) }
            .sortedBy { it.name }

    fun cities(places: List<PlaceDto>, admin2: String?, query: String = ""): List<PlaceDto> {
        val q = query.trim().lowercase()
        return places
            .filter { it.type == "city" && (admin2 == null || it.admin2 == admin2) }
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.aliases.any { a -> a.lowercase().contains(q) } }
            .sortedBy { it.name }
    }

    fun placeToFollowed(place: PlaceDto, label: String? = null): Place {
        val id = "place:${place.country.lowercase()}:${(place.city ?: place.admin2 ?: place.admin1 ?: place.name).lowercase().replace(Regex("[^a-z0-9]+"), "-")}"
        val auto = listOfNotNull(
            place.city,
            place.admin1?.substringAfter("-")?.takeIf { place.city != null },
        ).joinToString(", ").ifBlank { place.name }
        return Place(
            id = id,
            label = label ?: auto,
            country = place.country.uppercase(),
            admin1 = place.admin1,
            admin2 = place.admin2,
            city = place.city,
            metro = place.metro,
            tz = place.timezone,
        )
    }

    private inline fun <reified T> readCache(name: String): T? {
        val file = File(context.filesDir, name)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<T>(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    private inline fun <reified T> writeCache(name: String, value: T) {
        try {
            File(context.filesDir, name).writeText(json.encodeToString(value))
        } catch (e: IOException) {
            // Cache is best-effort.
        }
    }

    @Suppress("unused")
    fun placesResponseForTest(places: List<PlaceDto>): PlacesDto =
        PlacesDto(apiVersion = 1, country = "US", places = places)

    @Suppress("unused")
    fun postalResponseForTest(entries: List<PostalEntryDto>): PostalDto =
        PostalDto(apiVersion = 1, country = "US", postal = entries)
}

/** On-device geocoding abstraction (Android Geocoder; fakes in tests). */
interface GeoLookup {
    /** Reverse-geocode coarse coords to a followed place, or null. */
    suspend fun reverseGeocode(lat: Double, lon: Double): Place?

    /** Resolve "country + postal code" text to candidate places. */
    suspend fun geocodePostal(countryIso2: String, code: String): List<Place>
}

class AndroidGeoLookup(private val context: Context) : GeoLookup {
    override suspend fun reverseGeocode(lat: Double, lon: Double): Place? =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext null
            try {
                @Suppress("DEPRECATION")
                val hits = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val a = hits?.firstOrNull() ?: return@withContext null
                val cc = a.countryCode?.uppercase() ?: return@withContext null
                val city = a.locality ?: a.subAdminArea ?: return@withContext null
                // admin1 code mapping needs the gazetteer; leave null here and
                // let the confirm step enrich it from the downloaded places.
                return@withContext Place(
                    id = "geo:${cc.lowercase()}:${city.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                    label = listOfNotNull(city, a.adminArea).joinToString(", "),
                    country = cc,
                    admin1 = null,
                    admin2 = a.subAdminArea,
                    city = city,
                    metro = null,
                    // Raw coordinates are not stored beyond 2 decimals.
                    lat = "%.2f".format(Locale.US, lat).toDouble(),
                    lon = "%.2f".format(Locale.US, lon).toDouble(),
                    tz = null,
                )
            } catch (e: IOException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }

    override suspend fun geocodePostal(countryIso2: String, code: String): List<Place> =
        withContext(Dispatchers.IO) {
            if (!Geocoder.isPresent()) return@withContext emptyList()
            try {
                @Suppress("DEPRECATION")
                val hits = Geocoder(context, Locale.getDefault())
                    .getFromLocationName("$code, $countryIso2", 5) ?: return@withContext emptyList()
                hits.mapNotNull { a ->
                    val cc = a.countryCode?.uppercase() ?: return@mapNotNull null
                    val city = a.locality ?: a.subAdminArea ?: return@mapNotNull null
                    Place(
                        id = "geo:${cc.lowercase()}:${city.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                        label = listOfNotNull(city, a.adminArea).joinToString(", "),
                        country = cc,
                        admin1 = null,
                        admin2 = a.subAdminArea,
                        city = city,
                        lat = a.latitude.let { "%.2f".format(Locale.US, it).toDouble() },
                        lon = a.longitude.let { "%.2f".format(Locale.US, it).toDouble() },
                    )
                }
            } catch (e: IOException) {
                emptyList()
            } catch (e: IllegalArgumentException) {
                emptyList()
            }
        }
}
