package com.charleshartman.porchlightpress.domain

/**
 * Edition resolution: location -> best feed path via index.json.
 *
 * Fallback ladder (MASTER_PLAN G6): city -> county -> metro -> state ->
 * national. The publisher writes no county-level feed dirs, so a county
 * resolves through the place's metro feed when present, else the state feed.
 * Non-US places resolve to their city feed when listed, else the world feed.
 *
 * Pure function over index entries so it is unit-testable without network.
 */
object EditionResolution {

    data class IndexEntry(
        val path: String,
        val country: String,
        val admin1: String? = null,
        val admin2: String? = null,
        val city: String? = null,
        val metro: String? = null,
    )

    fun candidateFeedPaths(place: Place, kind: String = "latest"): List<String> {
        val cc = place.country.lowercase()
        val k = "$kind.json"
        val out = mutableListOf<String>()
        if (place.country.uppercase() == "US") {
            val a1 = admin1Slug(place.admin1)
            if (!place.city.isNullOrBlank() && !place.admin1.isNullOrBlank()) {
                out += "feeds/$cc/$a1/${slug(place.city)}/$k"
            }
            if (!place.metro.isNullOrBlank() && !place.admin1.isNullOrBlank()) {
                out += "feeds/$cc/$a1/regions/${place.metro.lowercase()}/$k"
            }
            if (!place.admin1.isNullOrBlank()) {
                out += "feeds/$cc/$a1/state/$k"
            }
            out += "feeds/$cc/national/$k"
        } else {
            if (!place.city.isNullOrBlank() && !place.admin1.isNullOrBlank()) {
                out += "feeds/$cc/${admin1Slug(place.admin1)}/${slug(place.city)}/$k"
            }
            out += "feeds/world/$k"
        }
        return out
    }

    /**
     * Pick the best feed path for [place] from parsed index entries.
     * Returns null when the index covers nothing for this place.
     */
    fun resolve(place: Place, entries: List<IndexEntry>, kind: String = "latest"): String? {
        if (entries.isEmpty()) return null
        val byPath = entries.associateBy { it.path }
        // Exact candidate wins in ladder order.
        for (candidate in candidateFeedPaths(place, kind)) {
            if (byPath.containsKey(candidate)) return candidate
        }
        // "latest" always exists next to a slot file; accept a slot sibling.
        if (kind != "latest") {
            for (candidate in candidateFeedPaths(place, "latest")) {
                if (byPath.containsKey(candidate)) return candidate
            }
            val dirPrefixes = candidateFeedPaths(place, kind).map { it.removeSuffix("$kind.json") }
            for (entry in entries) {
                if (dirPrefixes.any { entry.path.startsWith(it) }) return entry.path
            }
        }
        return null
    }
}
