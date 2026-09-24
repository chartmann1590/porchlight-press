package com.charleshartman.porchlightpress.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
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
import com.charleshartman.porchlightpress.ui.theme.PorchlightColors
import com.charleshartman.porchlightpress.ui.theme.stitchHeroScrim
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
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PorchlightMark(size = 32.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                "PORCHLIGHT PRESS",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = MaterialTheme.typography.titleLarge.fontSize *
                        LocalLayout.current.typeScaleFactor,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 0.4.sp,
                ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .weight(1f)
                    .testTag("masthead-title")
                    .semantics { heading() },
            )
            IconButton(onClick = { /* TODO: implement search navigation */ }, modifier = Modifier.size(40.dp).testTag("masthead-search")) {
                Icon(Icons.Outlined.Search, contentDescription = "Search")
            }
            IconButton(onClick = { /* TODO: implement audio brief navigation */ }, modifier = Modifier.size(40.dp).testTag("masthead-audio")) {
                Icon(Icons.Outlined.Headphones, contentDescription = "Audio brief")
            }
            if (onSwitchLocation != null) {
                IconButton(onClick = { onSwitchLocation() }, modifier = Modifier.size(40.dp).testTag("masthead-profile")) {
                    Icon(Icons.Outlined.Person, contentDescription = "Profile")
                }
            }
        }
        val context = LocalContext.current
        val dateline = TimeFormat.formatDateline(context, placeLabel)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (dateline.isNotBlank()) {
                Text(
                    dateline.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.6.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("masthead-dateline"),
                )
            }
            if (onSwitchLocation != null && placeLabel != null) {
                Text(
                    "Switch location",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onSwitchLocation() }
                        .padding(6.dp)
                        .testTag("masthead-switch"),
                )
            }
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
            .testTag("section-rule"),
        thickness = 2.dp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
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
        color = PorchlightColors.Blush,
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag("ai-badge"),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = PorchlightColors.Rose,
                modifier = Modifier.size(12.dp),
            )
            Text(
                "AI NEWSROOM",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = PorchlightColors.Rose,
            )
        }
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
            shape = RoundedCornerShape(LocalLayout.current.cardRadius),
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
        shape = RoundedCornerShape(LocalLayout.current.heroRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ai) AiBadge()
                if (isTranslating) Text("translating…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("translating-chip"))
            }
            Text(
                headline,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontSize = MaterialTheme.typography.displaySmall.fontSize * LocalLayout.current.typeScaleFactor,
                    fontFamily = FontFamily.Serif,
                ),
                color = MaterialTheme.colorScheme.primary,
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
        shape = RoundedCornerShape(LocalLayout.current.cardRadius),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (story.aiGenerated) AiBadge()
                if (!sourceLabel.isNullOrBlank()) {
                    VerifiedPill(label = "Source linked", modifier = Modifier.testTag("verified-pill"))
                }
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
        color = PorchlightColors.Blush,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, PorchlightColors.BlushDeep, RoundedCornerShape(20.dp))
            .testTag("ai-disclosure"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = PorchlightColors.Rose,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    "PORCHLIGHT AI NEWSROOM",
                    style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Serif),
                    color = PorchlightColors.Crimson,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                "This brief was written by the Porchlight Press AI newsroom and reviewed against the linked sources below. It may contain mistakes. The original reporting is always linked — open it for the full story.",
                style = MaterialTheme.typography.bodySmall,
                color = PorchlightColors.Ink,
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


@Composable
fun VerifiedPill(
    label: String = "Press Verified",
    modifier: Modifier = Modifier,
) {
    Surface(
        color = PorchlightColors.SageContainer,
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag("verified-pill"),
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = PorchlightColors.Sage, modifier = Modifier.size(12.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = PorchlightColors.Teal)
        }
    }
}

@Composable
fun ListenDispatchButton(
    onClick: () -> Unit,
    label: String = "Listen to Dispatch",
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = PorchlightColors.Crimson,
            contentColor = Color.White,
        ),
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag("listen-dispatch"),
    ) {
        Icon(Icons.Outlined.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
fun AudioBriefCard(
    title: String = "The Morning Dispatch",
    subtitle: String = "A short AI-assisted catch-up from today's edition. Playback coming soon.",
    onPlay: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth().testTag("audio-brief-card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = PorchlightColors.Ivory),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "PORCHLIGHT AUDIO BRIEF",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                    color = PorchlightColors.Crimson,
                )
                Text(title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Serif))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Surface(
                onClick = onPlay,
                shape = CircleShape,
                color = PorchlightColors.Amber,
                modifier = Modifier.size(52.dp).testTag("audio-brief-play"),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play brief", tint = PorchlightColors.Ink)
                }
            }
        }
    }
}

@Composable
fun TopicTile(
    title: String,
    subtitle: String,
    accent: Color = PorchlightColors.Crimson,
    badge: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag("topic-tile-${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"),
        shape = RoundedCornerShape(22.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(title.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = accent)
                }
                if (badge != null) {
                    Surface(color = PorchlightColors.SageContainer, shape = RoundedCornerShape(50)) {
                        Text(badge, style = MaterialTheme.typography.labelSmall, color = PorchlightColors.Teal, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                    }
                }
            }
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), color = accent)
            Text(subtitle, style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Serif), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
