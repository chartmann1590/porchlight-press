package com.charleshartman.porchlightpress.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 2,
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

        /** v1 → v2: persist the feed's permitted RSS excerpt on stories. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stories ADD COLUMN excerpt TEXT")
            }
        }
    }
}
