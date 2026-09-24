package com.charleshartman.porchlightpress.domain

import java.io.Serializable

/**
 * Serializable so onboarding state survives rotation + process death
 * via SavedStateHandle.
 */
data class PlaceSerializable(
    val id: String,
    val label: String,
    val country: String,
    val admin1: String? = null,
    val admin2: String? = null,
    val city: String? = null,
    val metro: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val tz: String? = null,
) : Serializable {
    fun toPlace(): Place = Place(id, label, country, admin1, admin2, city, metro, lat, lon, tz)

    companion object {
        fun from(p: Place): PlaceSerializable =
            PlaceSerializable(p.id, p.label, p.country, p.admin1, p.admin2, p.city, p.metro, p.lat, p.lon, p.tz)
    }
}
