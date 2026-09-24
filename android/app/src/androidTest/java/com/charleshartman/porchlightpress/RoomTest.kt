package com.charleshartman.porchlightpress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.SavedLocation
import com.charleshartman.porchlightpress.data.local.SavedStory
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.local.StoryFts
import com.charleshartman.porchlightpress.data.local.WeatherCache
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun savedLocationCrud() = runTest {
        val dao = db.savedLocationDao()
        dao.upsert(
            SavedLocation(
                id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
                admin1 = "US-NY", city = "Schenectady", tz = "America/New_York",
                isHome = true, sortOrder = 0,
            ),
        )
        assertEquals("Schenectady, NY", dao.byId("place:us:schenectady")?.label)
        assertEquals("place:us:schenectady", dao.home()?.id)
        assertEquals(1, dao.all().size)
    }

    @Test
    fun editionWithContentJoin() = runTest {
        val loc = SavedLocation(id = "loc", label = "Schenectady, NY", country = "US")
        db.savedLocationDao().upsert(loc)
        db.editionDao().upsertEdition(
            com.charleshartman.porchlightpress.data.local.Edition(
                id = "loc:latest", locationId = "loc", kind = "latest",
                generatedAt = "2026-09-24T00:00:00Z", fetchedAt = 1L,
            ),
        )
        db.editionDao().upsertSections(
            listOf(
                com.charleshartman.porchlightpress.data.local.EditionSection("loc:latest", "top", "Top Stories", 0),
            ),
        )
        db.editionDao().upsertLinks(
            listOf(
                com.charleshartman.porchlightpress.data.local.EditionStory("loc:latest", "top", TEST_STORY_ID, 0),
            ),
        )
        db.storyDao().upsertStories(
            listOf(Story(id = TEST_STORY_ID, headline = "Hello", generatedAt = "2026-09-24T00:00:00Z")),
        )
        val content = db.editionDao().editionWithContent("loc:latest")
        assertNotNull(content)
        assertEquals(1, content!!.sections.size)
        assertEquals(TEST_STORY_ID, content.sections.first().links.first().storyId)
    }

    @Test
    fun ftsFindsStoryText() = runTest {
        db.storyDao().upsertStories(
            listOf(
                Story(
                    id = TEST_STORY_ID, headline = "Downtown revitalization project",
                    body = "The council voted on Monday.", generatedAt = "2026-09-24T00:00:00Z",
                ),
            ),
        )
        db.storyDao().upsertFts(
            listOf(
                StoryFts(
                    storyId = TEST_STORY_ID, headline = "Downtown revitalization project",
                    dek = "", body = "The council voted on Monday.",
                    publisher = "Example Gazette", locations = "Schenectady", lang = "en",
                ),
            ),
        )
        val hits = db.storyDao().searchFts("revitalization")
        assertEquals(listOf(TEST_STORY_ID), hits.map { it.id })
        assertTrue(db.storyDao().searchFts("xyznothing").isEmpty())
    }

    @Test
    fun expiryKeepsSavedStories() = runTest {
        db.storyDao().upsertStories(
            listOf(
                Story(id = "old-unsaved", headline = "Old", generatedAt = "2020-01-01T00:00:00Z"),
                Story(id = "old-saved", headline = "Old saved", generatedAt = "2020-01-01T00:00:00Z"),
                Story(id = "fresh", headline = "Fresh", generatedAt = "2026-09-24T00:00:00Z"),
            ),
        )
        db.storyDao().saveStory(SavedStory("old-saved", savedAt = 2L))
        val deleted = db.storyDao().deleteExpiredUnsaved("2021-01-01T00:00:00Z")
        assertEquals(1, deleted)
        assertNull(db.storyDao().storyById("old-unsaved"))
        assertNotNull(db.storyDao().storyById("old-saved"))
        assertEquals(listOf("old-saved"), db.storyDao().savedIds())
    }

    @Test
    fun weatherCacheRoundTrip() = runTest {
        db.weatherDao().upsert(
            WeatherCache(
                bucketKey = "42.8,-73.9", provider = "nws", payloadJson = "{}",
                fetchedAt = 1L, expiresAt = 2L, etag = "abc",
            ),
        )
        assertEquals("abc", db.weatherDao().byBucket("42.8,-73.9")?.etag)
    }
}
