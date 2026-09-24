package com.charleshartman.porchlightpress.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database, designed for multiple editions now (Phase 5 spec).
 * Schema JSON is exported (exportSchema = true) with migration tests from v1.
 */
@Database(
    entities = [
        SavedLocation::class,
        Edition::class,
        EditionSection::class,
        EditionStory::class,
        Story::class,
        StorySource::class,
        SavedStory::class,
        WeatherCache::class,
        SourceInfo::class,
        StoryTranslation::class,
        UiTranslation::class,
        StoryFts::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun savedLocationDao(): SavedLocationDao
    abstract fun editionDao(): EditionDao
    abstract fun storyDao(): StoryDao
    abstract fun translationDao(): TranslationDao
    abstract fun weatherDao(): WeatherDao
    abstract fun sourceDao(): SourceDao

    companion object {
        const val NAME = "porchlight.db"
    }
}
