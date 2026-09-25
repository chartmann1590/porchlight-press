package com.charleshartman.porchlightpress.ui.components

/**
 * Reader-facing text rules for cards and articles.
 *
 * The feed carries three text slots per story: `dek` (AI sub-headline),
 * `excerpt` (permitted RSS excerpt, rights-filtered upstream to <=300 chars),
 * and `body` (full AI brief). None of them may ever repeat the headline, and
 * raw rights-mode enum names / pipeline terms must never reach readers.
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
    if (candidate.trim().equals(headline.trim(), ignoreCase = true)) return null
    return candidate
}
