package com.charleshartman.porchlightpress.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.data.remote.StoryImageDto
import com.charleshartman.porchlightpress.ui.motion.PorchlightMotion
import com.charleshartman.porchlightpress.ui.motion.rememberReduceMotion
import com.charleshartman.porchlightpress.ui.theme.GoldLamp
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.theme.LocalIsDarkTheme
import com.charleshartman.porchlightpress.ui.theme.LocalLayout
import com.charleshartman.porchlightpress.ui.theme.heroScrimBrush
import com.charleshartman.porchlightpress.ui.util.TimeFormat
import kotlinx.coroutines.delay
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
    val reduce = rememberReduceMotion()
    var ruleVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!reduce) delay(80)
        ruleVisible = true
    }
    val ruleProgress by animateFloatAsState(
        targetValue = if (ruleVisible) 1f else 0f,
        animationSpec = PorchlightMotion.ruleTween(reduce),
        label = "masthead-rule",
    )
    val classic = LocalIsClassic.current
    Column(
        modifier
            .fillMaxWidth()
            .semantics { heading() }
            .testTag("masthead")
            .padding(top = 8.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            PorchlightMark(size = 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(
                    "PORCHLIGHT",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 3.sp,
                    ),
                    color = if (classic) GoldLamp else MaterialTheme.colorScheme.primary,
                )
                Text(
                    "PRESS",
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontSize = MaterialTheme.typography.displayLarge.fontSize *
                            LocalLayout.current.typeScaleFactor,
                        letterSpacing = (-0.8).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .testTag("masthead-title")
                        .semantics { heading() },
                )
            }
        }
        val context = LocalContext.current
        val dateline = TimeFormat.formatDateline(context, placeLabel)
        if (dateline.isNotBlank()) {
            Text(
                dateline,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp).testTag("masthead-dateline"),
            )
        }
        if (onSwitchLocation != null && placeLabel != null) {
            Text(
                "Switch location",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { onSwitchLocation() }
                    .padding(8.dp)
                    .testTag("masthead-switch"),
            )
        }
        // Soft double rule that grows in
        Box(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth(0.92f)
                .height(3.dp)
                .graphicsLayer { scaleX = ruleProgress }
                .testTag("section-rule"),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.TopCenter)
                    .background(MaterialTheme.colorScheme.outline),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        if (classic) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                    ),
            )
        }
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
    val reduce = rememberReduceMotion()
    var shown by remember { mutableStateOf(reduce) }
    LaunchedEffect(title) {
        shown = false
        if (!reduce) delay(40)
        shown = true
    }
    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(PorchlightMotion.fadeMs(reduce))) +
            slideInVertically(tween(PorchlightMotion.slideMs(reduce))) { it / 3 },
        exit = fadeOut(),
        modifier = modifier.fillMaxWidth().testTag("section-header-${title.lowercase().replace(Regex("[^a-z0-9]+"), "-")}"),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .semantics { heading() }
                        .testTag("section-title"),
                )
                Spacer(Modifier.width(12.dp))
                HorizontalDivider(
                    Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// AiBadge + SourceLine
// ---------------------------------------------------------------------------

@Composable
fun AiBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(50),
        modifier = modifier.testTag("ai-badge"),
        shadowElevation = 2.dp,
    ) {
        Text(
            "AI NEWSROOM",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
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
// ImageWithAttribution
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
        Card(
            shape = MaterialTheme.shapes.large,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .testTag("image-card")
                .then(if (onOpenSource != null) Modifier.clickable { onOpenSource() } else Modifier),
        ) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(dto.url)
                    .crossfade(true)
                    .build(),
                contentDescription = dto.attribution.ifBlank { headlineForContentDescription },
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().testTag("story-image"),
                loading = { PorchlightShimmer(height = 200.dp) },
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
// HeroStory — cinematic scrim + staggered text
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
    val reduce = rememberReduceMotion()
    val dark = LocalIsDarkTheme.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = PorchlightMotion.pressSpring,
        label = "hero-press",
    )
    var textShown by remember { mutableStateOf(reduce) }
    LaunchedEffect(story.id) {
        textShown = false
        if (!reduce) delay(60)
        textShown = true
    }
    val dto = rememberImageDto(story.imageJson)
    val hasImage = dto != null && dto.url.startsWith("https://")

    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .testTag("hero-story-${story.id}")
            .semantics(mergeDescendants = true) {}
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp, pressedElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth()) {
            if (hasImage) {
                val context = LocalContext.current
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context).data(dto!!.url).crossfade(true).build(),
                    contentDescription = headline,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(LocalLayout.current.heroImageHeight + 40.dp)
                        .testTag("story-image"),
                    loading = { PorchlightShimmer(height = LocalLayout.current.heroImageHeight + 40.dp) },
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .background(heroScrimBrush(dark)),
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .then(if (hasImage) Modifier.align(Alignment.BottomStart) else Modifier)
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (ai) AiBadge()
                    if (isTranslating) Text("translating…", style = MaterialTheme.typography.labelSmall, color = if (hasImage) Color.White.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("translating-chip"))
                }
                AnimatedVisibility(
                    visible = textShown,
                    enter = fadeIn(tween(PorchlightMotion.fadeMs(reduce))) +
                        slideInVertically(tween(PorchlightMotion.slideMs(reduce))) { it / 2 },
                ) {
                    Text(
                        headline,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontSize = MaterialTheme.typography.headlineMedium.fontSize * LocalLayout.current.typeScaleFactor,
                            fontWeight = FontWeight.Bold,
                        ),
                        color = if (hasImage) Color.White else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { heading() }.testTag("hero-headline"),
                    )
                }
                if (LocalLayout.current.showDek && !dek.isNullOrBlank()) {
                    AnimatedVisibility(
                        visible = textShown,
                        enter = fadeIn(tween(PorchlightMotion.fadeMs(reduce) + 80)) +
                            slideInVertically(tween(PorchlightMotion.slideMs(reduce) + 40)) { it / 3 },
                    ) {
                        Text(
                            dek,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hasImage) Color.White.copy(alpha = 0.88f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("hero-dek"),
                        )
                    }
                }
                if (!hasImage) {
                    // Keep image attribution path for no-image stories via ImageWithAttribution (no-op)
                    ImageWithAttribution(imageJson = story.imageJson, headlineForContentDescription = headline)
                }
                SourceLine(
                    publisher = sourceLabel ?: story.publisherLabel(),
                    publishedAt = updatedAt ?: story.publishedAt,
                    modifier = if (hasImage) Modifier.graphicsLayer { /* keep tag */ } else Modifier,
                )
                if (story.version > 1) {
                    Text(
                        "Updated \u00b7 v${story.version}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (hasImage) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("hero-version"),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// StoryCard — tonal elevation + press polish
// ---------------------------------------------------------------------------

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
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.98f else 1f,
        animationSpec = PorchlightMotion.pressSpring,
        label = "card-press",
    )
    val classic = LocalIsClassic.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .testTag("story-card-${story.id}")
            .semantics(mergeDescendants = true) {}
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        elevation = CardDefaults.cardElevation(defaultElevation = if (classic) 1.dp else 3.dp, pressedElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (story.aiGenerated) AiBadge()
                if (isTranslating) Text("translating…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("translating-chip"))
            }
            Text(
                headline,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = MaterialTheme.typography.titleMedium.fontSize * LocalLayout.current.typeScaleFactor,
                    fontWeight = FontWeight.SemiBold,
                ),
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

private fun Story.publisherLabel(): String? = null

@Composable
fun AiDisclosureBox(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("ai-disclosure"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("AI NEWSROOM", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.sp), color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
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
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f))
            .padding(10.dp)
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
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .testTag("alert-banner"),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("⚠ $title", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.onErrorContainer)
            if (!description.isNullOrBlank()) {
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
