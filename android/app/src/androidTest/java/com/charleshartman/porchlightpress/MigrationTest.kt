package com.charleshartman.porchlightpress

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.charleshartman.porchlightpress.data.local.AppDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migration tests across v1 → v2 → v3:
 * - v1 creates and retains data (migrated straight to v3);
 * - v1 → v2 adds the notified_alerts table (Phase 7);
 * - v2 → v3 adds the stories excerpt column (redesign).
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @Test
    fun v1CreatesAndRetainsData() = runTest {
        val helper = androidx.room.testing.MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )
        helper.createDatabase(AppDatabase.NAME, 1).apply {
            execSQL(
                "INSERT INTO saved_locations (id, label, country, admin1, admin2, city, metro, lat, lon, tz, isHome, sortOrder) " +
                    "VALUES ('place:us:schenectady', 'Schenectady, NY', 'US', 'US-NY', 'Schenectady County', 'Schenectady', 'us-ny-capital-region', NULL, NULL, 'America/New_York', 1, 0)",
            )
            close()
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Schenectady, NY", db.savedLocationDao().byId("place:us:schenectady")?.label)
        } finally {
            db.close()
            context.deleteDatabase(AppDatabase.NAME)
        }
    }

    @Test
    fun v1ToV2AddsNotifiedAlerts() = runTest {
        val helper = androidx.room.testing.MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )
        helper.createDatabase(AppDatabase.NAME, 1).apply {
            execSQL(
                "INSERT INTO saved_locations (id, label, country, admin1, admin2, city, metro, lat, lon, tz, isHome, sortOrder) " +
                    "VALUES ('place:us:schenectady', 'Schenectady, NY', 'US', 'US-NY', 'Schenectady County', 'Schenectady', 'us-ny-capital-region', NULL, NULL, 'America/New_York', 1, 0)",
            )
            close()
        }
        // Validates the migrated schema against the exported 2.json.
        helper.runMigrationsAndValidate(
            AppDatabase.NAME, 2, true, AppDatabase.MIGRATION_1_2,
        ).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("Schenectady, NY", db.savedLocationDao().byId("place:us:schenectady")?.label)
            db.notifiedAlertDao().insertAll(
                listOf(
                    com.charleshartman.porchlightpress.data.local.NotifiedAlert("urn:test", 1L, "Tornado Warning"),
                ),
            )
            assertEquals(listOf("urn:test"), db.notifiedAlertDao().knownIds(listOf("urn:test")))
        } finally {
            db.close()
            context.deleteDatabase(AppDatabase.NAME)
        }
    }

    @Test
    fun v2ToV3AddsExcerptColumn() = runTest {
        val helper = androidx.room.testing.MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            AppDatabase::class.java,
        )
        helper.createDatabase(AppDatabase.NAME, 2).apply {
            execSQL(
                "INSERT INTO stories (id, headline, dek, body, category, publishedAt, updatedAt, generatedAt, aiGenerated, aiModel, breaking, version, imageJson, locationsJson) " +
                    "VALUES ('s1', 'Headline', NULL, NULL, 'local', '2026-09-23T10:00:00Z', NULL, '2026-09-23T12:00:00Z', 0, NULL, 0, 1, NULL, '[]')",
            )
            close()
        }
        // Validates the migrated schema against the exported 3.json.
        helper.runMigrationsAndValidate(
            AppDatabase.NAME, 3, true, AppDatabase.MIGRATION_2_3,
        ).close()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .allowMainThreadQueries()
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .build()
        try {
            val story = db.storyDao().storyById("s1")
            assertEquals("Headline", story?.headline)
            assertEquals(null, story?.excerpt)
            db.storyDao().upsertStories(
                listOf(
                    com.charleshartman.porchlightpress.data.local.Story(
                        id = "s1",
                        headline = "Headline",
                        excerpt = "Permitted excerpt.",
                    ),
                ),
            )
            assertEquals("Permitted excerpt.", db.storyDao().storyById("s1")?.excerpt)
        } finally {
            db.close()
            context.deleteDatabase(AppDatabase.NAME)
        }
    }
}
