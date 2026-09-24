package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.remote.EditionDto
import com.charleshartman.porchlightpress.data.remote.IndexDto
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.StoryDto
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DTO parsing against the repo fixtures, incl. unknown fields + newer apiVersion. */
class DtoParsingTest {
    private val json = NetworkModule.feedJson
    private val fixtures =
        File(File(System.getProperty("user.dir") ?: "."), "../../tests/fixtures").canonicalFile

    private fun fixture(name: String): String {
        val f = File(fixtures, name)
        assertTrue("missing fixture: ${f.path}", f.exists())
        return f.readText()
    }

    @Test
    fun parsesSampleEdition() {
        val edition = json.decodeFromString<EditionDto>(fixture("sample.edition.json"))
        assertEquals(1, edition.apiVersion)
        assertEquals("us-ny-schenectady-morning-2026-09-23", edition.editionId)
        assertEquals("morning", edition.kind)
        assertEquals("Schenectady, NY", edition.location.label)
        assertEquals(1, edition.sections.size)
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", edition.sections.first().storyIds.first())
    }

    @Test
    fun parsesSampleIndex() {
        val index = json.decodeFromString<IndexDto>(fixture("sample.index.json"))
        assertEquals(1, index.apiVersion)
        assertEquals("feeds/us/ny/schenectady/morning.json", index.editions.first().path)
    }

    @Test
    fun parsesSampleStory() {
        val story = json.decodeFromString<StoryDto>(fixture("sample.story.json"))
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", story.id)
        assertTrue(story.aiGenerated)
        assertEquals("qwen3-4b-q4_k_m", story.aiModel)
        assertEquals(1, story.sources.size)
        assertEquals("RSS_EXCERPT_ALLOWED", story.sources.first().rightsMode)
    }

    @Test
    fun ignoresUnknownFields() {
        val raw = fixture("sample.story.json").replace(
            "\"confidenceTier\": \"medium\"",
            "\"confidenceTier\": \"medium\", \"futureField\": {\"nested\": [1, 2]}, \"anotherNew\": 42",
        )
        val story = json.decodeFromString<StoryDto>(raw)
        assertEquals("a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4", story.id)
    }

    @Test
    fun newerApiVersionSurvivesParsingForRejectionUpstream() {
        val raw = fixture("sample.edition.json").replace("\"apiVersion\": 1", "\"apiVersion\": 2")
        val edition = json.decodeFromString<EditionDto>(raw)
        assertEquals(2, edition.apiVersion)
        assertTrue("repository must reject major > supported (1)", edition.apiVersion > 1)
    }

    @Test
    fun coercesExplicitNullsToDefaults() {
        val raw = fixture("sample.story.json")
            .replace("\"headline\": \"City council", "\"headline\": null, \"unused\": \"City council")
        val story = json.decodeFromString<StoryDto>(raw)
        assertEquals("", story.headline)
    }
}
