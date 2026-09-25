package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.remote.EditionDto
import com.charleshartman.porchlightpress.data.remote.FeedApi
import com.charleshartman.porchlightpress.data.remote.FeedLocationDto
import com.charleshartman.porchlightpress.data.remote.IndexDto
import com.charleshartman.porchlightpress.data.remote.IndexEntryDto
import com.charleshartman.porchlightpress.data.remote.PlaceDto
import com.charleshartman.porchlightpress.data.remote.PlacesDto
import com.charleshartman.porchlightpress.data.remote.PostalDto
import com.charleshartman.porchlightpress.data.remote.PostalEntryDto
import com.charleshartman.porchlightpress.data.remote.SectionDto
import com.charleshartman.porchlightpress.data.remote.StoryDto
import com.charleshartman.porchlightpress.data.remote.StoryLocationDto
import com.charleshartman.porchlightpress.data.remote.StorySourceDto
import com.charleshartman.porchlightpress.data.weather.CurrentWeather
import com.charleshartman.porchlightpress.data.weather.DailyPoint
import com.charleshartman.porchlightpress.data.weather.FakeCoordsResolver
import com.charleshartman.porchlightpress.data.weather.FakeWeatherProvider
import com.charleshartman.porchlightpress.data.weather.FailingWeatherProvider
import com.charleshartman.porchlightpress.data.weather.HourlyPoint
import com.charleshartman.porchlightpress.data.weather.InMemoryWeatherCacheStore
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.data.weather.WeatherCondition
import com.charleshartman.porchlightpress.data.weather.WeatherRepository
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

const val TEST_STORY_ID = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4"

fun testStoryDto() = StoryDto(
    apiVersion = 1,
    id = TEST_STORY_ID,
    headline = "City council approves downtown revitalization project",
    dek = "The 5-2 vote funds streetscape work set to begin in spring.",
    body = "The city council voted 5-2 on Monday to approve a downtown revitalization project.",
    category = "local",
    publishedAt = "2026-09-23T10:00:00Z",
    generatedAt = "2026-09-23T12:00:00Z",
    aiGenerated = true,
    aiModel = "qwen3-4b-q4_k_m",
    version = 1,
    locations = listOf(
        StoryLocationDto(country = "US", admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady", metro = "us-ny-capital-region"),
    ),
    sources = listOf(
        StorySourceDto(
            publisher = "Example Gazette",
            headline = "Council OKs downtown project",
            url = "https://example.com/council-downtown",
            publishedAt = "2026-09-23T09:00:00Z",
            rightsMode = "RSS_EXCERPT_ALLOWED",
        ),
    ),
)

fun testEditionDto() = EditionDto(
    apiVersion = 1,
    editionId = "us-ny-schenectady-latest-2026-09-24",
    kind = "latest",
    generatedAt = "2026-09-24T00:00:00Z",
    location = FeedLocationDto(
        country = "US", admin1 = "US-NY", admin2 = "Schenectady County",
        city = "Schenectady", metro = "us-ny-capital-region",
        label = "Schenectady, NY", timezone = "America/New_York",
    ),
    sections = listOf(SectionDto(id = "top", title = "Top Stories", storyIds = listOf(TEST_STORY_ID))),
    stories = listOf(testStoryDto()),
)

fun testIndexDto(paths: List<String> = listOf("feeds/us/ny/schenectady/latest.json")) = IndexDto(
    apiVersion = 1,
    generatedAt = "2026-09-24T00:00:00Z",
    editions = paths.map {
        IndexEntryDto(
            id = it, kind = "latest", path = it, updatedAt = "2026-09-24T00:00:00Z",
            storyCount = 1,
            location = FeedLocationDto(country = "US", admin1 = "US-NY", city = "Schenectady", label = "Schenectady, NY"),
        )
    },
)

fun testPlacesDto() = PlacesDto(
    apiVersion = 1,
    generatedAt = "2026-09-24T00:00:00Z",
    country = "US",
    places = listOf(
        PlaceDto(name = "United States", type = "country", country = "US"),
        PlaceDto(name = "New York", type = "admin1", country = "US", admin1 = "US-NY", admin1Name = "New York", timezone = "America/New_York"),
        PlaceDto(name = "Schenectady County", type = "admin2", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", metro = "us-ny-capital-region", timezone = "America/New_York"),
        PlaceDto(name = "Schenectady", type = "city", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady", metro = "us-ny-capital-region", timezone = "America/New_York"),
    ),
)

fun testPostalDto() = PostalDto(
    apiVersion = 1,
    generatedAt = "2026-09-24T00:00:00Z",
    country = "US",
    postal = listOf(
        PostalEntryDto(postal = "12308", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady", metro = "us-ny-capital-region"),
    ),
)

/** Fake FeedApi for instrumented tests: canned docs, offline mode, error mode. */
class FakeFeedApi(
    var index: IndexDto = testIndexDto(),
    var editions: Map<String, EditionDto> = mapOf(
        "feeds/us/ny/schenectady/latest.json" to testEditionDto(),
    ),
    var places: PlacesDto = testPlacesDto(),
    var postal: PostalDto = testPostalDto(),
    var offline: Boolean = false,
) : FeedApi {
    override suspend fun index(): Response<IndexDto> {
        if (offline) throw IOException("offline")
        return Response.success(index)
    }

    override suspend fun edition(path: String): Response<EditionDto> {
        if (offline) throw IOException("offline")
        val doc = editions[path]
            ?: return Response.error(404, "not found".toResponseBody("text/plain".toMediaType()))
        return Response.success(doc)
    }

    override suspend fun places(path: String): Response<PlacesDto> {
        if (offline) throw IOException("offline")
        return Response.success(places)
    }

    override suspend fun postal(path: String): Response<PostalDto> {
        if (offline) throw IOException("offline")
        return Response.success(postal)
    }
}

// ---------------------------------------------------------------------------
// Weather fakes (Phase 7): canned NWS/MET providers behind WeatherRepository.
// ---------------------------------------------------------------------------

fun testFloodAlert() = WeatherAlert(
    id = "urn:test:flood-warning-1",
    event = "Flood Warning",
    headline = "Flood Warning issued for Schenectady County",
    description = "The Mohawk River at Schenectady is expected to rise above flood stage this evening.",
    instruction = "Turn around, don't drown.",
    severity = "Severe",
    urgency = "Expected",
    sender = "NWS Albany",
    effective = "2026-09-24T14:15:00-04:00",
    expires = "2026-09-24T23:00:00-04:00",
    areaDesc = "Schenectady County",
    link = "https://www.weather.gov/",
)

/** No coordinates → snapshot Unavailable, no network (keeps tests hermetic). */
fun unavailableWeatherRepo() = WeatherRepository(
    InMemoryWeatherCacheStore(),
    FailingWeatherProvider("nws"),
    FakeWeatherProvider("met"),
    FakeCoordsResolver(null),
)

/** Canned NWS snapshot for a US place (Schenectady bucket). */
fun cannedNwsWeatherRepo(alerts: List<WeatherAlert> = listOf(testFloodAlert())) = WeatherRepository(
    InMemoryWeatherCacheStore(),
    FakeWeatherProvider(
        id = "nws",
        current = CurrentWeather(
            tempC = 21.1, feelsLikeC = 21.1, humidityPct = 65,
            windKph = 14.5, windDir = "NW",
            condition = WeatherCondition.PARTLY_CLOUDY, shortText = "Partly Cloudy",
            precipPct = 5,
        ),
        hourly = (14..19).map { h ->
            HourlyPoint(
                "2026-09-24T${h}:00:00-04:00", tempC = 20.0 + (h - 14),
                precipPct = 5, condition = WeatherCondition.PARTLY_CLOUDY,
                shortText = "Partly Cloudy",
            )
        },
        forecast = listOf(
            DailyPoint("2026-09-24", hiC = 23.3, loC = 14.4, precipPct = 20, condition = WeatherCondition.PARTLY_CLOUDY, shortText = "Partly Cloudy"),
            DailyPoint("2026-09-25", hiC = 24.0, loC = 13.0, precipPct = 80, condition = WeatherCondition.THUNDERSTORM, shortText = "Thunderstorms"),
        ),
        alerts = alerts,
    ),
    FakeWeatherProvider("met"),
    FakeCoordsResolver(42.81 to -73.93),
)

/** Canned MET snapshot for a non-US place (Oslo bucket, no alerts). */
fun cannedMetWeatherRepo() = WeatherRepository(
    InMemoryWeatherCacheStore(),
    FailingWeatherProvider("nws"),
    FakeWeatherProvider(
        id = "met",
        current = CurrentWeather(
            tempC = 14.2, feelsLikeC = 14.2, humidityPct = 71,
            windKph = 11.2, windDir = "SW",
            condition = WeatherCondition.RAIN, shortText = "Rain",
            precipPct = 70,
        ),
        hourly = (12..17).map { h ->
            HourlyPoint(
                "2026-09-24T${h}:00:00Z", tempC = 14.0,
                precipPct = 60, condition = WeatherCondition.RAIN, shortText = "Rain",
            )
        },
        forecast = listOf(
            DailyPoint("2026-09-24", hiC = 15.1, loC = 11.8, precipPct = 85, condition = WeatherCondition.RAIN, shortText = "Rain"),
        ),
    ),
    FakeCoordsResolver(59.91 to 10.75),
)
