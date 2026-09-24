package com.charleshartman.porchlightpress.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTOs mirroring schemas/*.json. Unknown fields are ignored so the app
 * keeps parsing when the publisher adds fields; apiVersion newer than
 * BuildConfig.SUPPORTED_FEED_API_MAJOR is rejected by the repository
 * with an "update the app" state instead.
 */
@Serializable
data class FeedLocationDto(
    val country: String = "US",
    val admin1: String? = null,
    val admin2: String? = null,
    val city: String? = null,
    val metro: String? = null,
    val label: String = "",
    val timezone: String? = null,
)

@Serializable
data class IndexEntryDto(
    val id: String = "",
    val kind: String? = null,
    val path: String = "",
    val updatedAt: String = "",
    val storyCount: Int = 0,
    val location: FeedLocationDto = FeedLocationDto(),
)

@Serializable
data class IndexDto(
    val apiVersion: Int = 1,
    val generatedAt: String = "",
    val editions: List<IndexEntryDto> = emptyList(),
)

@Serializable
data class SectionDto(
    val id: String = "",
    val title: String = "",
    val storyIds: List<String> = emptyList(),
)

@Serializable
data class StorySourceDto(
    val publisher: String = "",
    val headline: String = "",
    val url: String = "",
    val publishedAt: String? = null,
    val rightsMode: String? = null,
    val excerpt: String? = null,
)

@Serializable
data class StoryImageDto(
    val url: String = "",
    val creator: String? = null,
    val license: String? = null,
    val licenseUrl: String? = null,
    val sourceUrl: String? = null,
    val attribution: String = "",
)

@Serializable
data class StoryLocationDto(
    val country: String = "US",
    val admin1: String? = null,
    val admin2: String? = null,
    val city: String? = null,
    val metro: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class StoryRevisionDto(
    val version: Int = 1,
    val updatedAt: String = "",
    val note: String? = null,
)

@Serializable
data class StoryDto(
    val apiVersion: Int = 1,
    val id: String = "",
    val headline: String = "",
    val dek: String? = null,
    val body: String? = null,
    val excerpt: String? = null,
    val category: String = "local",
    val publishedAt: String = "",
    val updatedAt: String? = null,
    val generatedAt: String = "",
    val aiGenerated: Boolean = false,
    val aiModel: String? = null,
    val version: Int = 1,
    val revisions: List<StoryRevisionDto> = emptyList(),
    val confidenceTier: String? = null,
    val breaking: Boolean = false,
    val tags: List<String> = emptyList(),
    val locations: List<StoryLocationDto> = emptyList(),
    val image: StoryImageDto? = null,
    val sources: List<StorySourceDto> = emptyList(),
)

@Serializable
data class EditionDto(
    val apiVersion: Int = 1,
    val editionId: String = "",
    val kind: String = "latest",
    val generatedAt: String = "",
    val location: FeedLocationDto = FeedLocationDto(),
    val sections: List<SectionDto> = emptyList(),
    val stories: List<StoryDto> = emptyList(),
)

/** Published gazetteer slice: public/locations/{country}.json */
@Serializable
data class PlaceDto(
    val name: String = "",
    val type: String = "",
    val country: String = "US",
    val admin1: String? = null,
    val admin1Name: String? = null,
    val admin2: String? = null,
    val city: String? = null,
    val metro: String? = null,
    val timezone: String? = null,
    val aliases: List<String> = emptyList(),
)

@Serializable
data class PlacesDto(
    val apiVersion: Int = 1,
    val generatedAt: String = "",
    val country: String = "US",
    val places: List<PlaceDto> = emptyList(),
)

/** Published postal slice: public/locations/{country}-postal.json */
@Serializable
data class PostalEntryDto(
    val postal: String = "",
    val country: String = "US",
    val admin1: String? = null,
    val admin2: String? = null,
    val city: String? = null,
    val metro: String? = null,
)

@Serializable
data class PostalDto(
    val apiVersion: Int = 1,
    val generatedAt: String = "",
    val country: String = "US",
    val postal: List<PostalEntryDto> = emptyList(),
)

@Serializable
data class TaxonomyCategoryDto(
    val id: String = "",
    val label: String = "",
    val description: String = "",
)

@Serializable
data class TaxonomyDto(
    val apiVersion: Int = 1,
    val description: String = "",
    val categories: List<TaxonomyCategoryDto> = emptyList(),
)
