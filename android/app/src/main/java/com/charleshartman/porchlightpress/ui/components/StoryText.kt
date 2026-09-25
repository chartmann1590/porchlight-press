package com.charleshartman.porchlightpress.ui.components

import com.charleshartman.porchlightpress.data.remote.StoryLocationDto
import kotlinx.serialization.json.Json

/**
 * Reader-facing display rules for cards and articles.
 *
 * The feed carries three text slots per story: `dek` (AI sub-headline),
 * `excerpt` (permitted RSS excerpt, rights-filtered upstream to <=300 chars),
 * and `body` (full AI brief). None of them may ever repeat the headline, and
 * raw rights-mode enum names / pipeline terms must never reach readers.
 *
 * Stock "File photo" images are only shown when the photo's place matches
 * the story's place; anything else collapses to the text-only layout.
 */

/** Rights modes whose text the app may display. Mirrors pipeline/rights.py. */
private val EXCERPT_DISPLAY_OK = setOf("PUBLIC_DOMAIN", "OPEN_LICENSE", "RSS_EXCERPT_ALLOWED")

/**
 * Whether the feed's excerpt may be shown for a story with these source
 * rights modes. The pipeline already filters excerpts, so an empty source
 * list (offline/legacy rows) defaults to allowed.
 */
fun excerptAllowed(rightsModes: List<String?>): Boolean =
    rightsModes.isEmpty() || rightsModes.any { it in EXCERPT_DISPLAY_OK }

/** First [maxChars] chars of a body, cut at a word boundary with an ellipsis. */
fun bodyPreview(body: String, maxChars: Int = 140): String {
    val flat = body.replace(Regex("\\s+"), " ").trim()
    if (flat.length <= maxChars) return flat
    val cut = flat.take(maxChars)
    val lastSpace = cut.lastIndexOf(' ')
    val trimmed = if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut
    return trimmed.trimEnd() + "…"
}

/** Stopwords ignored when comparing headline/dek overlap. */
private val NEAR_DUP_STOPWORDS = setOf(
    "the", "and", "for", "with", "from", "that", "this",
    "are", "was", "were", "has", "have", "had",
)

/** Significant tokens: lowercase alphanumerics, len>=3, no stopwords. */
private fun significantTokens(text: String): List<String> =
    Regex("[a-z0-9]+").findAll(text.lowercase())
        .map { it.value }
        .filter { it.length >= 3 && it !in NEAR_DUP_STOPWORDS }
        .toList()

/**
 * True when [candidate] (dek/snippet) merely restates [headline]: exact
 * match (ignoring case/whitespace), or >=75% of the candidate's significant
 * tokens already appear in the headline. E.g. dek "Schenectady County
 * prepares for 2026 holiday parade" under a "Grand marshal named ..."
 * holiday-parade headline adds nothing, so the article screen hides it.
 */
fun isNearDuplicate(headline: String, candidate: String?): Boolean {
    if (candidate.isNullOrBlank() || headline.isBlank()) return false
    if (candidate.trim().equals(headline.trim(), ignoreCase = true)) return true
    val headTokens = significantTokens(headline).toSet()
    val candTokens = significantTokens(candidate)
    if (candTokens.isEmpty() || headTokens.isEmpty()) return false
    val overlap = candTokens.count { it in headTokens }
    return overlap.toDouble() / candTokens.size >= 0.75
}

/**
 * One-line snippet for front-page/section cards: dek, then the permitted
 * excerpt, then a body preview. Returns null when there is nothing worth
 * showing — in particular when the candidate merely repeats the headline.
 */
fun cardSnippet(
    headline: String,
    dek: String?,
    excerpt: String?,
    body: String?,
    maxChars: Int = 140,
): String? {
    val candidate = dek?.takeIf { it.isNotBlank() }
        ?: excerpt?.takeIf { it.isNotBlank() }
        ?: body?.takeIf { it.isNotBlank() }?.let { bodyPreview(it, maxChars) }
        ?: return null
    if (isNearDuplicate(headline, candidate)) return null
    return candidate
}

// ---------------------------------------------------------------------------
// Stock photo place-match gate.
// ---------------------------------------------------------------------------

/** Generic geographic words that never count as a place match on their own. */
private val PLACE_GENERIC = setOf(
    "county", "city", "town", "village", "metro", "region", "state",
    "united", "states", "file", "photo", "photos", "image", "images",
    "wikimedia", "commons", "public", "domain",
)

private val imagePolicyJson = Json { ignoreUnknownKeys = true }

/** Stock Commons photos carry a "File photo: ..." attribution prefix. */
fun isFilePhotoAttribution(attribution: String): Boolean =
    attribution.trimStart().startsWith("File photo", ignoreCase = true)

/** Significant place tokens from raw place names (city/county/metro/...). */
private fun placeTokens(names: List<String>): Set<String> =
    names.flatMap { name ->
        Regex("[a-z0-9]+").findAll(name.lowercase()).map { it.value }
    }.filter { it.length >= 4 && it !in PLACE_GENERIC }.toSet()

/**
 * True when a stock "File photo" attribution names the story's place: any
 * significant token of the photo title (the text after "File photo:" up to
 * the em-dash creator credit) matches a story place token. Publisher-owned
 * images (no "File photo" prefix) always return true — they are preferred.
 */
fun filePhotoMatchesPlace(attribution: String, places: List<String>): Boolean {
    if (!isFilePhotoAttribution(attribution)) return true
    val title = attribution.substringAfter("File photo", "")
        .substringAfter(":", attribution)
        .split("—", "-", "|", "/").firstOrNull().orEmpty()
    val titleTokens = Regex("[a-z0-9]+").findAll(title.lowercase())
        .map { it.value }
        .filter { it.length >= 4 && it !in PLACE_GENERIC }
        .toList()
    if (titleTokens.isEmpty()) return false
    val placesSet = placeTokens(places)
    if (placesSet.isEmpty()) return false
    return titleTokens.any { tok ->
        placesSet.any { place ->
            tok == place ||
                (tok.length >= 6 && place.length >= 6 &&
                    (tok.startsWith(place) || place.startsWith(tok)))
        }
    }
}

/** Story places from the feed's locations JSON (city/county/metro/...). */
fun storyPlaces(locationsJson: String?): List<String> =
    runCatching {
        if (locationsJson.isNullOrBlank()) return emptyList()
        imagePolicyJson.decodeFromString<List<StoryLocationDto>>(locationsJson)
            .flatMap { listOfNotNull(it.city, it.admin2, it.metro, it.admin1, it.country) }
    }.getOrDefault(emptyList())

/** JSON overload: parse the story's locations, then match the photo title. */
fun filePhotoMatchesPlace(attribution: String, locationsJson: String?): Boolean =
    filePhotoMatchesPlace(attribution, storyPlaces(locationsJson))

/**
 * Whether to render a story image at all. Publisher-owned images always
 * show; stock file photos only when their place matches the story's place.
 * Anything else collapses (callers return early — never a grey box).
 */
fun shouldShowImage(attribution: String, locationsJson: String?): Boolean =
    filePhotoMatchesPlace(attribution, locationsJson)
