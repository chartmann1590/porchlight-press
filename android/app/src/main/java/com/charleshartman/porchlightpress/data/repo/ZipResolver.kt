package com.charleshartman.porchlightpress.data.repo

import com.charleshartman.porchlightpress.data.remote.PostalEntryDto
import com.charleshartman.porchlightpress.domain.Place

/**
 * ZIP / postal-code resolution, on-device, from locations/{country}-postal.json.
 *
 * US: 5 digits; ZIP+4 accepted and trimmed. A code that spans several towns
 * yields [Outcome.Ambiguous] with a short list to choose from. Unknown code ->
 * [Outcome.Invalid] with "pick manually instead". When the postal file is
 * unavailable the caller falls back to Android Geocoder ([GeoLookup]).
 */
object ZipResolver {

    sealed interface Outcome {
        data class Resolved(val place: Place) : Outcome
        data class Ambiguous(val options: List<Place>) : Outcome
        data class Invalid(val reason: String) : Outcome
    }

    /** Normalize a raw US entry: trim, accept ZIP+4, require 5 digits. Null = invalid format. */
    fun normalizeUsZip(raw: String): String? {
        val t = raw.trim()
        // ZIP+4 ("12345-6789", "12345 6789", "123456789"): keep the first 5.
        val five = if (t.length >= 5 && t.substring(0, 5).all { it.isDigit() }) {
            t.substring(0, 5)
        } else {
            t
        }
        return five.takeIf { it.matches(Regex("\\d{5}")) }
    }

    /** Normalize a non-US code: trim/uppercase, collapse inner spaces. Null = blank. */
    fun normalizePostal(raw: String): String? {
        val t = raw.trim().uppercase().replace(Regex("\\s+"), " ")
        return t.ifBlank { null }
    }

    fun resolveUs(code5: String, entries: List<PostalEntryDto>): Outcome {
        val hits = entries.filter { it.postal == code5 }
        if (hits.isEmpty()) {
            return Outcome.Invalid("We don't recognize ZIP $code5. Check it, or pick manually instead.")
        }
        val towns = hits.map { it.toPlace() }.distinctBy { it.id }
        return if (towns.size == 1) Outcome.Resolved(towns.first()) else Outcome.Ambiguous(towns)
    }

    fun resolveGeneric(code: String, entries: List<PostalEntryDto>): Outcome {
        val hits = entries.filter { it.postal.equals(code, ignoreCase = true) }
        if (hits.isEmpty()) {
            return Outcome.Invalid("We don't recognize “$code”. Check it, or pick manually instead.")
        }
        val towns = hits.map { it.toPlace() }.distinctBy { it.id }
        return if (towns.size == 1) Outcome.Resolved(towns.first()) else Outcome.Ambiguous(towns)
    }

    private fun PostalEntryDto.toPlace(): Place {
        val town = city ?: admin2 ?: admin1?.substringAfter("-") ?: postal
        val label = listOfNotNull(city, admin1?.substringAfter("-")?.takeIf { city != null })
            .joinToString(", ").ifBlank { town }
        return Place(
            id = "zip:${country.lowercase()}:$postal:${town.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
            label = label,
            country = country.uppercase(),
            admin1 = admin1,
            admin2 = admin2,
            city = city,
            metro = metro,
        )
    }
}
