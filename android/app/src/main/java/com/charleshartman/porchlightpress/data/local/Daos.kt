package com.charleshartman.porchlightpress.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction

@Dao
interface SavedLocationDao {
    @Query("SELECT * FROM saved_locations ORDER BY sortOrder, label")
    suspend fun all(): List<SavedLocation>

    @Query("SELECT * FROM saved_locations WHERE id = :id")
    suspend fun byId(id: String): SavedLocation?

    @Query("SELECT * FROM saved_locations WHERE isHome = 1 LIMIT 1")
    suspend fun home(): SavedLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(location: SavedLocation)

    @Query("DELETE FROM saved_locations WHERE id = :id")
    suspend fun delete(id: String)
}

data class SectionWithStories(
    @Embedded val section: EditionSection,
    @Relation(
        parentColumn = "sectionId",
        entityColumn = "storyId",
        entity = EditionStory::class,
    )
    val links: List<EditionStory>,
)

data class EditionWithContent(
    @Embedded val edition: Edition,
    @Relation(parentColumn = "id", entityColumn = "editionId", entity = EditionSection::class)
    val sections: List<SectionWithStories>,
)

@Dao
interface EditionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEdition(edition: Edition)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSections(sections: List<EditionSection>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLinks(links: List<EditionStory>)

    @Query("SELECT * FROM editions WHERE locationId = :locationId AND kind = :kind")
    suspend fun editionFor(locationId: String, kind: String): Edition?

    @Transaction
    @Query("SELECT * FROM editions WHERE id = :editionId")
    suspend fun editionWithContent(editionId: String): EditionWithContent?

    @Query("DELETE FROM editions WHERE fetchedAt < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long): Int
}

@Dao
interface StoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStories(stories: List<Story>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSources(sources: List<StorySource>)

    @Query("SELECT * FROM stories WHERE id = :id")
    suspend fun storyById(id: String): Story?

    @Query("SELECT * FROM story_sources WHERE storyId = :storyId")
    suspend fun sourcesFor(storyId: String): List<StorySource>

    @Query("SELECT * FROM stories WHERE id IN (:ids)")
    suspend fun storiesByIds(ids: List<String>): List<Story>

    /** Expire unsaved stories older than [olderThan] (generatedAt ISO string). */
    @Query(
        "DELETE FROM stories WHERE generatedAt < :olderThan AND id NOT IN " +
            "(SELECT storyId FROM saved_stories)",
    )
    suspend fun deleteExpiredUnsaved(olderThan: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStory(saved: SavedStory)

    @Query("DELETE FROM saved_stories WHERE storyId = :storyId")
    suspend fun unsaveStory(storyId: String)

    @Query("SELECT storyId FROM saved_stories")
    suspend fun savedIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFts(rows: List<StoryFts>)

    @Query("DELETE FROM story_fts WHERE storyId = :storyId AND lang = :lang")
    suspend fun deleteFtsFor(storyId: String, lang: String)

    @Query("SELECT s.* FROM stories s JOIN story_fts f ON f.storyId = s.id WHERE story_fts MATCH :query")
    suspend fun searchFts(query: String): List<Story>
}

@Dao
interface TranslationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStoryTranslations(rows: List<StoryTranslation>)

    @Query("SELECT * FROM story_translations WHERE storyId = :storyId AND version = :version AND lang = :lang")
    suspend fun storyTranslation(storyId: String, version: Int, lang: String): StoryTranslation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUiTranslations(rows: List<UiTranslation>)

    @Query("SELECT text FROM ui_translations WHERE lang = :lang AND stringKey = :key")
    suspend fun uiText(lang: String, key: String): String?
}

@Dao
interface WeatherDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: WeatherCache)

    @Query("SELECT * FROM weather_cache WHERE bucketKey = :bucketKey")
    suspend fun byBucket(bucketKey: String): WeatherCache?
}

@Dao
interface SourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sources: List<SourceInfo>)

    @Query("SELECT * FROM source_info")
    suspend fun all(): List<SourceInfo>
}
