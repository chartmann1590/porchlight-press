package com.charleshartman.porchlightpress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.repo.EditionRepository
import com.charleshartman.porchlightpress.data.repo.FeedResult
import com.charleshartman.porchlightpress.domain.Place
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditionRepositoryTest {
    private lateinit var db: AppDatabase

    private val schenectady = Place(
        id = "place:us:schenectady", label = "Schenectady, NY", country = "US",
        admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady",
        metro = "us-ny-capital-region", tz = "America/New_York",
    )

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
    fun syncCityExactPersistsAndExposesFlow() = runTest {
        val api = FakeFeedApi()
        val repo = EditionRepository(db)
        val result = repo.sync(schenectady, "latest", api)
        assertTrue(result is FeedResult.Ok)
        val summary = (result as FeedResult.Ok).value
        assertEquals("feeds/us/ny/schenectady/latest.json", summary.feedPath)
        assertFalse(summary.fellBack)
        assertEquals(1, summary.storyCount)
        // Room is the single source of truth.
        val content = repo.cachedContent(schenectady.id, "latest")
        assertNotNull(content)
        assertEquals(TEST_STORY_ID, content!!.sections.first().second.first().id)
        val sources = db.storyDao().sourcesFor(TEST_STORY_ID)
        assertEquals("Example Gazette", sources.first().publisher)
    }

    @Test
    fun syncFallsBackToMetro() = runTest {
        val api = FakeFeedApi(
            index = testIndexDto(listOf("feeds/us/ny/regions/us-ny-capital-region/latest.json")),
            editions = mapOf(
                "feeds/us/ny/regions/us-ny-capital-region/latest.json" to testEditionDto(),
            ),
        )
        val result = EditionRepository(db).sync(schenectady, "latest", api)
        assertTrue(result is FeedResult.Ok)
        val summary = (result as FeedResult.Ok).value
        assertEquals("feeds/us/ny/regions/us-ny-capital-region/latest.json", summary.feedPath)
        assertTrue(summary.fellBack)
    }

    @Test
    fun newerIndexApiVersionNeedsUpdate() = runTest {
        val api = FakeFeedApi(index = testIndexDto().copy(apiVersion = 2))
        val result = EditionRepository(db).sync(schenectady, "latest", api)
        assertTrue(result is FeedResult.Error || result is FeedResult.UpdateRequired)
        assertTrue(result is FeedResult.UpdateRequired)
        assertTrue((result as FeedResult.UpdateRequired).message.contains("update"))
    }

    @Test
    fun newerEditionApiVersionNeedsUpdate() = runTest {
        val api = FakeFeedApi(
            editions = mapOf(
                "feeds/us/ny/schenectady/latest.json" to testEditionDto().copy(apiVersion = 2),
            ),
        )
        val result = EditionRepository(db).sync(schenectady, "latest", api)
        assertTrue(result is FeedResult.UpdateRequired)
    }

    @Test
    fun offlineWithCacheReturnsOfflineCached() = runTest {
        val repo = EditionRepository(db)
        repo.sync(schenectady, "latest", FakeFeedApi())
        val result = repo.sync(schenectady, "latest", FakeFeedApi(offline = true))
        assertTrue(result is FeedResult.Offline)
        assertNotNull((result as FeedResult.Offline).cached)
    }

    @Test
    fun offlineWithoutCacheReturnsOfflineEmpty() = runTest {
        val result = EditionRepository(db).sync(schenectady, "latest", FakeFeedApi(offline = true))
        assertTrue(result is FeedResult.Offline)
    }

    @Test
    fun missingEditionIsError() = runTest {
        val api = FakeFeedApi(editions = emptyMap())
        val result = EditionRepository(db).sync(schenectady, "latest", api)
        assertTrue(result is FeedResult.Error)
    }

    @Test
    fun probeFeed() = runTest {
        val repo = EditionRepository(db)
        val api = FakeFeedApi()
        assertTrue(repo.probeFeed("feeds/us/ny/schenectady/latest.json", api))
        assertFalse(repo.probeFeed("feeds/us/ny/nowhere/latest.json", api))
        assertFalse(repo.probeFeed("feeds/us/ny/schenectady/latest.json", FakeFeedApi(offline = true)))
    }
}
