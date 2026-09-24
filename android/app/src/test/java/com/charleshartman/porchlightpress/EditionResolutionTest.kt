package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.domain.EditionResolution
import com.charleshartman.porchlightpress.domain.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditionResolutionTest {
    private val schenectady = Place(
        id = "place:us:schenectady",
        label = "Schenectady, NY",
        country = "US",
        admin1 = "US-NY",
        admin2 = "Schenectady County",
        city = "Schenectady",
        metro = "us-ny-capital-region",
        tz = "America/New_York",
    )

    private fun entry(path: String) = EditionResolution.IndexEntry(
        path = path, country = "US", admin1 = "US-NY", city = "Schenectady",
        metro = "us-ny-capital-region",
    )

    @Test
    fun candidateLadderIsCityMetroStateNational() {
        assertEquals(
            listOf(
                "feeds/us/ny/schenectady/latest.json",
                "feeds/us/ny/regions/us-ny-capital-region/latest.json",
                "feeds/us/ny/state/latest.json",
                "feeds/us/national/latest.json",
            ),
            EditionResolution.candidateFeedPaths(schenectady),
        )
    }

    @Test
    fun countyHasNoFeedDirOfItsOwn() {
        val countyOnly = schenectady.copy(city = null)
        assertEquals(
            listOf(
                "feeds/us/ny/regions/us-ny-capital-region/latest.json",
                "feeds/us/ny/state/latest.json",
                "feeds/us/national/latest.json",
            ),
            EditionResolution.candidateFeedPaths(countyOnly),
        )
    }

    @Test
    fun resolvesExactCity() {
        val entries = listOf(
            entry("feeds/us/ny/schenectady/latest.json"),
            entry("feeds/us/national/latest.json"),
        )
        assertEquals(
            "feeds/us/ny/schenectady/latest.json",
            EditionResolution.resolve(schenectady, entries),
        )
    }

    @Test
    fun fallsBackMetroThenStateThenNational() {
        val metro = entry("feeds/us/ny/regions/us-ny-capital-region/latest.json")
        val state = entry("feeds/us/ny/state/latest.json")
        val national = entry("feeds/us/national/latest.json")
        assertEquals(
            metro.path,
            EditionResolution.resolve(schenectady, listOf(metro, state, national)),
        )
        assertEquals(
            state.path,
            EditionResolution.resolve(schenectady, listOf(state, national)),
        )
        assertEquals(
            national.path,
            EditionResolution.resolve(schenectady, listOf(national)),
        )
    }

    @Test
    fun emptyIndexResolvesNull() {
        assertNull(EditionResolution.resolve(schenectady, emptyList()))
    }

    @Test
    fun slotKindFallsBackToLatestSibling() {
        val latest = entry("feeds/us/ny/schenectady/latest.json")
        assertEquals(
            latest.path,
            EditionResolution.resolve(schenectady, listOf(latest), kind = "morning"),
        )
    }

    @Test
    fun nonUsFallsBackToWorld() {
        val london = Place(
            id = "place:gb:london", label = "London", country = "GB",
            city = "London", tz = "Europe/London",
        )
        assertEquals(
            listOf("feeds/world/latest.json"),
            EditionResolution.candidateFeedPaths(london).takeLast(1),
        )
        val world = EditionResolution.IndexEntry("feeds/world/latest.json", "GB")
        assertEquals("feeds/world/latest.json", EditionResolution.resolve(london, listOf(world)))
        assertNull(EditionResolution.resolve(london, emptyList()))
    }
}
