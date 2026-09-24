package com.charleshartman.porchlightpress.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.StoryImageDto
import com.charleshartman.porchlightpress.ui.theme.LocalLayout
import com.charleshartman.porchlightpress.ui.util.TimeFormat
import kotlinx.serialization.decodeFromString

// ---------------------------------------------------------------------------
// Masthead + EditionLabel + Dateline + SectionRule
// ---------------------------------------------------------------------------

@Composable
fun Masthead(
    placeLabel: String?,
    onSwitchLocation: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .semantics { heading() }
            .testTag("masthead"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "PORCHLIGHT PRESS",
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = MaterialTheme.typography.displayLarge.fontSize * (LocalLayout.current.typeScaleFactor),
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .testTag("masthead-title")
                .semantics { heading() },
        )
        val context = LocalContext.current
        val dateline = TimeFormat.formatDateline(context, placeLabel)
        if (dateline.isNotBlank()) {
            Text(
                dateline,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp).testTag("masthead-dateline"),
            )
        }
        if (onSwitchLocation != null && placeLabel != null) {
            Text(
                "Switch location",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onSwitchLocation() }
                    .padding(8.dp)
                    .testTag("masthead-switch"),
            )
        }
        SectionRule(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun EditionLabel(
    generatedAt: String?,
    kind: String?,
    modifier: Modifier = Modifier,
    clockOverride: String = "system",
) {
    val context = LocalContext.current
    val label = TimeFormat.formatEditionLabel(generatedAt, kind, context, clockOverride)
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.testTag("edition-label"),
    )
}

@Composable
fun Dateline(
    generatedAt: String?,
    placeLabel: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val updated = TimeFormat.formatUpdatedLabel(generatedAt, context)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        if (placeLabel != null) {
            Text(placeLabel.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("dateline-place"))
        }
        if (updated.isNotBlank()) {
            Text(updated, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("dateline-updated"))
        }
    }
}

@Composable
fun SectionRule(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .testTag("section-rule"),
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("section-header-${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}")) {
        SectionRule()
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { heading() }
                .testTag("section-title"),
        )
    }
}

// ---------------------------------------------------------------------------
// AiBadge + SourceLine
// ---------------------------------------------------------------------------

@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier.testTag("ai-badge"),
    ) {
        Text(
            "AI NEWSROOM",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun SourceLine(
    publisher: String?,
    publishedAt: String?,
    modifier: Modifier = Modifier,
    clockOverride: String = "system",
) {
    val context = LocalContext.current
    val time = TimeFormat.formatSourceTime(publishedAt, context, clockOverride)
    val text = if (time.isBlank()) publisher ?: "" else "${publisher ?: ""} \u00b7 $time"
    if (text.isNotBlank()) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.testTag("source-line"),
        )
    }
}

// ---------------------------------------------------------------------------
// ImageWithAttribution — Coil 3 with memory+disk cache, crossfade, fixed
// aspect placeholders. Failed/missing image falls back to text-only layout.
// ---------------------------------------------------------------------------

@Composable
fun ImageWithAttribution(
    imageJson: String?,
    headlineForContentDescription: String,
    onOpenSource: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dto = rememberImageDto(imageJson)
    if (dto == null || dto.url.isBlank() || !dto.url.startsWith("https://")) {
        return
    }
    Column(modifier.fillMaxWidth().testTag("image-block")) {
        val context = LocalContext.current
        // 16:9 fixed aspect, rounded, crossfade via Coil builder.
        Card(
            shape = MaterialTheme.shapes.medium,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .testTag("image-card")
                .then(if (onOpenSource != null) Modifier.clickable { onOpenSource() } else Modifier),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(dto.url)
                    .crossfade(true)
                    .build(),
                contentDescription = dto.attribution.ifBlank { headlineForContentDescription },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().testTag("story-image"),
            )
        }
        if (dto.attribution.isNotBlank()) {
            Text(
                dto.attribution,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .testTag("image-attribution")
                    .clickable(enabled = onOpenSource != null) { onOpenSource?.invoke() },
            )
        }
    }
}

@Composable
private fun rememberImageDto(imageJson: String?): StoryImageDto? {
    if (imageJson.isNullOrBlank()) return null
    return try {
        NetworkModule.feedJson.decodeFromString<StoryImageDto>(imageJson)
    } catch (e: Exception) {
        android.util.Log.w("Porchlight", "Ignoring malformed story imageJson", e)
        null
    }
}

// ---------------------------------------------------------------------------
// HeroStory + StoryCard (with/without image) — merged semantics for TalkBack:
// "headline, publisher, time, AI-generated"
// ---------------------------------------------------------------------------

@Composable
fun HeroStory(
    story: Story,
    translatedHeadline: String? = null,
    translatedDek: String? = null,
    isTranslating: Boolean = false,
    sourceLabel: String? = null,
    updatedAt: String? = null,
    onClick: () -> Unit,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val headline = translatedHeadline ?: story.headline
    val dek = translatedDek ?: story.dek
    val ai = story.aiGenerated
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hero-story-${story.id}")
            .semantics(mergeDescendants = true) {}
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ai) AiBadge()
                if (isTranslating) Text("translating…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("translating-chip"))
            }
            Text(
                headline,
                style = MaterialTheme.typography.headlineMedium.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize * LocalLayout.current.typeScaleFactor),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() }.testTag("hero-headline"),
            )
            if (LocalLayout.current.showDek && !dek.isNullOrBlank()) {
                Text(
                    dek,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("hero-dek"),
                )
            }
            ImageWithAttribution(imageJson = story.imageJson, headlineForContentDescription = headline)
            SourceLine(publisher = sourceLabel ?: story.publisherLabel(), publishedAt = updatedAt ?: story.publishedAt)
            if (story.version > 1) {
                Text("Updated \u00b7 v${story.version}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("hero-version"))
            }
        }
    }
}

@Composable
fun StoryCard(
    story: Story,
    translatedHeadline: String? = null,
    translatedDek: String? = null,
    isTranslating: Boolean = false,
    sourceLabel: String? = null,
    updatedAt: String? = null,
    onClick: () -> Unit,
    onShare: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val headline = translatedHeadline ?: story.headline
    val dek = translatedDek ?: story.dek
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("story-card-${story.id}")
            .semantics(mergeDescendants = true) {}
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (story.aiGenerated) AiBadge()
                if (isTranslating) Text("translating…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("translating-chip"))
            }
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = MaterialTheme.typography.titleMedium.fontSize * LocalLayout.current.typeScaleFactor),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.testTag("card-headline"),
            )
            if (LocalLayout.current.showDek && !dek.isNullOrBlank()) {
                Text(
                    dek,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("card-dek"),
                )
            }
            // With/without image: ImageWithAttribution collapses to zero when missing.
            if (!story.imageJson.isNullOrBlank()) {
                ImageWithAttribution(imageJson = story.imageJson, headlineForContentDescription = headline, modifier = Modifier.padding(top = 4.dp))
            }
            SourceLine(publisher = sourceLabel ?: story.publisherLabel(), publishedAt = updatedAt ?: story.publishedAt)
            if (story.version > 1) {
                Text("Updated \u00b7 v${story.version}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("card-version"))
            }
        }
    }
}

/**
 * Last-resort publisher label. The Story row stores no publisher, so callers
 * pass sourceLabel from sourcesFor(); this returns null so SourceLine simply
 * omits the line rather than printing a wrong "Porchlight Press" byline.
 */
private fun Story.publisherLabel(): String? = null

// ---------------------------------------------------------------------------
// AI disclosure box (spec wording) + Reporting Sources + AlertBanner stub
// ---------------------------------------------------------------------------

@Composable
fun AiDisclosureBox(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai-disclosure"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI NEWSROOM", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
            Text(
                "This brief was written by the Porchlight Press AI newsroom and reviewed against the linked sources below. It may contain mistakes. The original reporting is always linked — open it for the full story.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun TranslationLabel(
    isTranslated: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!isTranslated) return
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f))
            .padding(8.dp)
            .testTag("translation-label"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Translated on your device by Google ML Kit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.TextButton(onClick = onToggle, modifier = Modifier.testTag("toggle-original")) {
            Text("Show original", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun AlertBanner(
    title: String,
    description: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .testTag("alert-banner"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("⚠ $title", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onErrorContainer)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
