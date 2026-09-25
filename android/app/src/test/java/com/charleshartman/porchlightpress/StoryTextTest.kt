package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.ui.components.bodyPreview
import com.charleshartman.porchlightpress.ui.components.cardSnippet
import com.charleshartman.porchlightpress.ui.components.excerptAllowed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader-facing text rules: snippet choice, dedup, rights gating. */
class StoryTextTest {
    @Test
    fun snippetPrefersDek() {
        assertEquals(
            "The 5-2 vote funds work.",
            cardSnippet("Headline", "The 5-2 vote funds work.", "Excerpt", "Body text here"),
        )
    }

    @Test
    fun snippetFallsBackToExcerptThenBody() {
        assertEquals("Excerpt", cardSnippet("Headline", null, "Excerpt", "Body text here"))
        assertEquals("Body text here", cardSnippet("Headline", null, null, "Body text here"))
    }

    @Test
    fun snippetNeverRepeatsHeadline() {
        assertNull(cardSnippet("Same text", "Same text", "Same text", "Same text"))
        assertNull(cardSnippet("Same text", "  same TEXT  ", null, null))
    }

    @Test
    fun snippetNullWhenEmpty() {
        assertNull(cardSnippet("Headline", null, null, null))
        assertNull(cardSnippet("Headline", "  ", "", "  "))
    }

    @Test
    fun bodyPreviewShortUnchanged() {
        assertEquals("Short body.", bodyPreview("Short body."))
    }

    @Test
    fun bodyPreviewCutsAtWordBoundary() {
        val body = "Word ".repeat(60).trim()
        val preview = bodyPreview(body, 140)
        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length < 145)
        assertFalse(preview.dropLast(1).endsWith(" "))
    }

    @Test
    fun excerptAllowedForDisplayModes() {
        assertTrue(excerptAllowed(emptyList()))
        assertTrue(excerptAllowed(listOf("RSS_EXCERPT_ALLOWED")))
        assertTrue(excerptAllowed(listOf("PUBLIC_DOMAIN")))
        assertTrue(excerptAllowed(listOf("OPEN_LICENSE")))
        assertTrue(excerptAllowed(listOf("METADATA_ONLY", "RSS_EXCERPT_ALLOWED")))
    }

    @Test
    fun excerptBlockedForMetadataOrLinkOnly() {
        assertFalse(excerptAllowed(listOf("METADATA_ONLY")))
        assertFalse(excerptAllowed(listOf("LINK_ONLY")))
        assertFalse(excerptAllowed(listOf(null)))
    }
}
