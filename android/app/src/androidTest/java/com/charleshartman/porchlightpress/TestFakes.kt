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
