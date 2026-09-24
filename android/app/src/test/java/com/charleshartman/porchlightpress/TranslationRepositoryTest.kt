package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.local.AppDatabase
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.local.StoryFts
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.StoryLocationDto
import com.charleshartman.porchlightpress.data.repo.FakeTranslatorEngine
import com.charleshartman.porchlightpress.data.repo.TranslationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRepositoryTest {
    private fun repo(engine: FakeTranslatorEngine = FakeTranslatorEngine()): TranslationRepository {
        val db = mockk<AppDatabase>(relaxed = true)
        coEvery { db.translationDao().uiText(any(), any()) } returns null
        coEvery { db.translationDao().storyTranslation(any(), any(), any()) } returns null
        return TranslationRepository(db, engine)
    }

    @Test
    fun protectsPlaceholders() = runTest {
        val engine = FakeTranslatorEngine()
        val out = repo(engine).translateString("Hello %1\$s, you have %d messages", "es")
        assertEquals("[t]Hello %1\$s, you have %d messages", out)
        assertTrue(engine.translateCalls == 1)
    }

    @Test
    fun fallsBackToEnglishWhenPlaceholderMangled() = runTest {
        val engine = FakeTranslatorEngine(translated = { it.replace(Regex("__PPPH\\d__"), "") })
        val original = "Hello %1\$s"
        assertEquals(original, repo(engine).translateString(original, "es"))
    }

    @Test
    fun fallsBackToEnglishOnEngineFailure() = runTest {
        val engine = FakeTranslatorEngine(translated = { throw RuntimeException("no model") })
        assertEquals("Hello", repo(engine).translateString("Hello", "es"))
    }

    @Test
    fun englishIsPassthroughWithoutEngineCall() = runTest {
        val engine = FakeTranslatorEngine()
        assertEquals("Hello %d", repo(engine).translateString("Hello %d", "en"))
        assertEquals(0, engine.translateCalls)
    }

    @Test
    fun cacheHitSkipsEngine() = runTest {
        val engine = FakeTranslatorEngine()
        val db = mockk<AppDatabase>(relaxed = true)
        coEvery { db.translationDao().uiText("es", "k") } returns "cached"
        val out = TranslationRepository(db, engine).translateUiStrings(mapOf("k" to "v"), "es")
        assertEquals("cached", out["k"])
        assertEquals(0, engine.translateCalls)
    }

    @Test
    fun protectUnprotectRoundTrip() {
        val (guarded, table) = TranslationRepository.protect("A %1\$s B %d C")
        assertEquals(listOf("%1\$s", "%d"), table)
        assertEquals("A %1\$s B %d C", guarded.replace("__PPPH0__", "%1\$s").replace("__PPPH1__", "%d"))
        assertEquals("[x]A %1\$s B %d C[y]", TranslationRepository.unprotect("[x]$guarded[y]", table))
        // A mangled token fails closed (caller keeps English).
        assertEquals(null, TranslationRepository.unprotect("[x]A B[y]", table))
    }

    @Test
    fun translateStoryCarriesPublisherAndLocationsIntoFts() = runTest {
        val db = mockk<AppDatabase>(relaxed = true)
        coEvery { db.translationDao().storyTranslation(any(), any(), any()) } returns null
        coEvery { db.translationDao().uiText(any(), any()) } returns null
        val sources = listOf(
            StorySource(storyId = "s1", publisher = "Schenectady Gazette", headline = "H", url = "https://example.com/a"),
            StorySource(storyId = "s1", publisher = "AP", headline = "H2", url = "https://example.com/b"),
        )
        coEvery { db.storyDao().sourcesFor("s1") } returns sources
        val locJson = NetworkModule.feedJson.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(StoryLocationDto.serializer()),
            listOf(StoryLocationDto(city = "Schenectady", admin2 = "Schenectady County", admin1 = "US-NY", country = "US")),
        )
        coEvery { db.storyDao().storyById("s1") } returns Story(id = "s1", headline = "H", locationsJson = locJson)
        val ftsSlot = slot<List<StoryFts>>()
        coEvery { db.storyDao().upsertFts(capture(ftsSlot)) } returns Unit
        coEvery { db.storyDao().deleteFtsFor(any(), any()) } returns Unit
        val repo = TranslationRepository(db, FakeTranslatorEngine())
        repo.translateStory("s1", 1, "Hello", "dek", "body", "es")
        coVerify { db.storyDao().upsertFts(any()) }
        val fts = ftsSlot.captured.first()
        assertTrue(fts.publisher.contains("Schenectady Gazette"))
        assertTrue(fts.publisher.contains("AP"))
        assertTrue(fts.locations.contains("Schenectady"))
        assertTrue(fts.locations.contains("Schenectady County"))
        assertEquals("es", fts.lang)
        assertEquals("s1", fts.storyId)
    }

    @Test
    fun translateStoryFtsFallsBackToEmptyWhenNoSourcesOrStory() = runTest {
        val db = mockk<AppDatabase>(relaxed = true)
        coEvery { db.translationDao().storyTranslation(any(), any(), any()) } returns null
        coEvery { db.storyDao().sourcesFor(any()) } returns emptyList()
        coEvery { db.storyDao().storyById(any()) } returns null
        val ftsSlot = slot<List<StoryFts>>()
        coEvery { db.storyDao().upsertFts(capture(ftsSlot)) } returns Unit
        coEvery { db.storyDao().deleteFtsFor(any(), any()) } returns Unit
        val repo = TranslationRepository(db, FakeTranslatorEngine())
        repo.translateStory("s2", 1, "Hello", null, null, "es")
        val fts = ftsSlot.captured.first()
        assertEquals("", fts.publisher)
        assertEquals("", fts.locations)
    }
}
