package com.charleshartman.porchlightpress.domain

/**
 * A place the user follows, stored in Room/DataStore on this device only.
 * Raw GPS coordinates are never stored (rounded to 2 decimals at capture).
 */
data class Place(
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
)

/** Mirrors pipeline/publish.py slug() so the app builds the same feed dirs. */
fun slug(text: String?): String {
    val base = (text ?: "").lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    return base.replace(Regex("-{2,}"), "-").ifEmpty { "unknown" }
}

/** Mirrors publish.py admin1_slug(): "US-NY" -> "ny". */
fun admin1Slug(admin1: String?): String {
    if (admin1.isNullOrBlank()) return "unknown"
    val code = if ("-" in admin1) admin1.substringAfter("-") else admin1
    return slug(code)
}
