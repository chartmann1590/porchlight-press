package com.charleshartman.porchlightpress

import com.charleshartman.porchlightpress.data.remote.PlaceDto
import com.charleshartman.porchlightpress.data.repo.LocationRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationPickersTest {
    private val places = listOf(
        PlaceDto(name = "United States", type = "country", country = "US"),
        PlaceDto(name = "New York", type = "admin1", country = "US", admin1 = "US-NY", admin1Name = "New York", timezone = "America/New_York"),
        PlaceDto(name = "Schenectady County", type = "admin2", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", metro = "us-ny-capital-region", timezone = "America/New_York"),
        PlaceDto(name = "Albany County", type = "admin2", country = "US", admin1 = "US-NY", admin2 = "Albany County", metro = "us-ny-capital-region", timezone = "America/New_York"),
        PlaceDto(name = "Schenectady", type = "city", country = "US", admin1 = "US-NY", admin2 = "Schenectady County", city = "Schenectady", metro = "us-ny-capital-region", timezone = "America/New_York"),
        PlaceDto(name = "Saratoga Springs", type = "city", country = "US", admin1 = "US-NY", admin2 = "Saratoga County", city = "Saratoga Springs", metro = "us-ny-capital-region", timezone = "America/New_York", aliases = listOf("Saratoga")),
    )

    @Test
    fun cascadeFilters() {
        assertEquals(listOf("United States"), LocationRepository.countries(places).map { it.name })
        assertEquals(listOf("New York"), LocationRepository.admin1s(places, "US").map { it.name })
        assertEquals(
            listOf("Albany County", "Schenectady County"),
            LocationRepository.counties(places, "US-NY").map { it.name },
        )
        assertEquals(
            listOf("Schenectady"),
            LocationRepository.cities(places, "Schenectady County").map { it.name },
        )
    }

    @Test
    fun citySearchMatchesNameAndAlias() {
        assertEquals(
            listOf("Schenectady"),
            LocationRepository.cities(places, null, "schen").map { it.name },
        )
        assertEquals(
            listOf("Saratoga Springs"),
            LocationRepository.cities(places, null, "saratoga").map { it.name },
        )
    }

    @Test
    fun placeToFollowedBuildsLabelAndIds() {
        val city = places.first { it.city == "Schenectady" }
        val place = LocationRepository.placeToFollowed(city)
        assertEquals("Schenectady, NY", place.label)
        assertEquals("US", place.country)
        assertEquals("US-NY", place.admin1)
        assertEquals("America/New_York", place.tz)
    }
}
