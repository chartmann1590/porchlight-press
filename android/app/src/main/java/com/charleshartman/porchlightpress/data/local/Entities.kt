package com.charleshartman.porchlightpress.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** A followed place. Lives only on this device (Room + DataStore). */
@Entity(tableName = "saved_locations")
data class SavedLocation(
    @PrimaryKey val id: String,
    val label: String,
    val country: String,
    val admin1: String? = null,
    val admin2: String? = null,
    val city: String? = null,
    val metro: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val tz: String? = null,
    val isHome: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(
    tableName = "editions",
    foreignKeys = [
        ForeignKey(
            entity = SavedLocation::class,
            parentColumns = ["id"],
            childColumns = ["locationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("locationId")],
)
data class Edition(
    @PrimaryKey val id: String,
    val locationId: String,
    val kind: String,
    val generatedAt: String,
    val fetchedAt: Long,
)

@Entity(
    tableName = "edition_sections",
    primaryKeys = ["editionId", "sectionId"],
    indices = [Index("editionId")],
)
data class EditionSection(
    val editionId: String,
    val sectionId: String,
    val title: String,
    @ColumnInfo(name = "section_order") val order: Int,
)

@Entity(
    tableName = "edition_stories",
    primaryKeys = ["editionId", "sectionId", "storyId"],
    indices = [Index("editionId"), Index("storyId")],
)
data class EditionStory(
    val editionId: String,
    val sectionId: String,
    val storyId: String,
    val rank: Int,
)

@Entity(tableName = "stories")
data class Story(
    @PrimaryKey val id: String,
    val headline: String,
    val dek: String? = null,
    val body: String? = null,
    /** Permitted RSS excerpt from the feed (rights-filtered upstream, ≤300 chars). */
    val excerpt: String? = null,
    val category: String = "local",
    val publishedAt: String = "",
    val updatedAt: String? = null,
    val generatedAt: String = "",
    val aiGenerated: Boolean = false,
    val aiModel: String? = null,
    val breaking: Boolean = false,
    val version: Int = 1,
    val imageJson: String? = null,
    val locationsJson: String = "[]",
)

@Entity(
    tableName = "story_sources",
    primaryKeys = ["storyId", "url"],
    indices = [Index("storyId")],
)
data class StorySource(
    val storyId: String,
    val publisher: String,
    val headline: String,
    val url: String,
    val publishedAt: String? = null,
    val rightsMode: String? = null,
)

/** Saved stories are exempt from cache expiry. */
@Entity(tableName = "saved_stories")
data class SavedStory(
    @PrimaryKey val storyId: String,
    val savedAt: Long,
)

@Entity(tableName = "weather_cache")
data class WeatherCache(
    @PrimaryKey val bucketKey: String,
    val provider: String,
    val payloadJson: String,
    val fetchedAt: Long,
    val expiresAt: Long,
    val etag: String? = null,
    val lastModified: String? = null,
)

@Entity(tableName = "source_info")
data class SourceInfo(
    @PrimaryKey val id: String,
    val name: String,
    val homepage: String,
    val rightsMode: String,
)

/**
 * Severe-weather notify dedupe (Phase 7): one row per NWS alert already
 * notified. The worker notifies only Warning-level events whose ID is not
 * in this table.
 */
@Entity(tableName = "notified_alerts")
data class NotifiedAlert(
    @PrimaryKey val alertId: String,
    val notifiedAt: Long,
    val event: String? = null,
)

@Entity(
    tableName = "story_translations",
    primaryKeys = ["storyId", "version", "lang"],
    indices = [Index("storyId")],
)
data class StoryTranslation(
    val storyId: String,
    val version: Int,
    val lang: String,
    val headline: String,
    val dek: String? = null,
    val body: String? = null,
    val captionsJson: String? = null,
    val sourceHeadlinesJson: String? = null,
)

@Entity(
    tableName = "ui_translations",
    primaryKeys = ["lang", "stringKey"],
    indices = [Index("lang")],
)
data class UiTranslation(
    val lang: String,
    @ColumnInfo(name = "stringKey") val key: String,
    val text: String,
)

/**
 * FTS4 table over headline/dek/body/publisher/locations, including translated
 * text. Populated by the repositories on story insert/translate (Phase 8
 * search reads it).
 */
@Fts4
@Entity(tableName = "story_fts")
data class StoryFts(
    @ColumnInfo(name = "storyId") val storyId: String,
    @ColumnInfo(name = "headline") val headline: String,
    @ColumnInfo(name = "dek") val dek: String,
    @ColumnInfo(name = "body") val body: String,
    @ColumnInfo(name = "publisher") val publisher: String,
    @ColumnInfo(name = "locations") val locations: String,
    @ColumnInfo(name = "lang") val lang: String,
)
