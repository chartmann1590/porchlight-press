package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.ui.components.bodyPreview
import com.charleshartman.porchlightpress.ui.components.cardSnippet
import com.charleshartman.porchlightpress.ui.components.excerptAllowed
import com.charleshartman.porchlightpress.ui.components.filePhotoMatchesPlace
import com.charleshartman.porchlightpress.ui.components.isFilePhotoAttribution
import com.charleshartman.porchlightpress.ui.components.isNearDuplicate
import com.charleshartman.porchlightpress.ui.components.shouldShowImage
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
    fun nearDuplicateRestatingDekHidden() {
        assertTrue(
            isNearDuplicate(
                "Grand marshal named for Schenectady County 2026 holiday parade",
                "Schenectady County prepares for 2026 holiday parade",
            ),
        )
        assertNull(
            cardSnippet(
                "Grand marshal named for Schenectady County 2026 holiday parade",
                "Schenectady County prepares for 2026 holiday parade",
                null,
                null,
            ),
        )
    }

    @Test
    fun distinctDekStillShown() {
        assertFalse(isNearDuplicate("Headline", "The 5-2 vote funds work."))
        assertEquals(
            "The 5-2 vote funds work.",
            cardSnippet("Headline", "The 5-2 vote funds work.", "Excerpt", "Body text here"),
        )
    }

    @Test
    fun filePhotoNeedsPlaceMatch() {
        assertTrue(isFilePhotoAttribution("File photo: Schenectady City Hall — Jane / Wikimedia Commons (CC BY-SA 4.0)"))
        assertFalse(isFilePhotoAttribution("Photo by the publisher"))
        // Albany stock photo on a Berkshire County story: hidden.
        assertFalse(
            filePhotoMatchesPlace(
                "File photo: Albany bodega storefront — Jane / Wikimedia Commons (CC BY-SA 4.0)",
                listOf("Pittsfield", "Berkshire County", "US-MA"),
            ),
        )
        // Matching place: shown.
        assertTrue(
            filePhotoMatchesPlace(
                "File photo: Schenectady City Hall — Jane / Wikimedia Commons (CC BY-SA 4.0)",
                listOf("Schenectady", "Schenectady County", "us-ny-capital-region"),
            ),
        )
        // Publisher-owned images always show.
        assertTrue(shouldShowImage("Photo by the publisher", null))
        assertFalse(
            shouldShowImage(
                "File photo: Albany bodega storefront — Jane / Wikimedia Commons (CC BY-SA 4.0)",
                """[{"city":"Pittsfield","admin2":"Berkshire County","country":"US"}]""",
            ),
        )
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
