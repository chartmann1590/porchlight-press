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
 * Migration test from v1: create the v1 schema, write a row with raw SQL,
 * reopen with Room, and verify the data survives.
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
            .addMigrations(AppDatabase.MIGRATION_1_2)
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
            .addMigrations(AppDatabase.MIGRATION_1_2)
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
}
