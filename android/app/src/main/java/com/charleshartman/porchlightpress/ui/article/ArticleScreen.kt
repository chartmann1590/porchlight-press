package com.charleshartman.porchlightpress.ui.article

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charleshartman.porchlightpress.BuildConfig
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.ui.components.AiBadge
import com.charleshartman.porchlightpress.ui.components.AiDisclosureBox
import com.charleshartman.porchlightpress.ui.components.AudioPlayerBar
import com.charleshartman.porchlightpress.ui.components.ImageWithAttribution
import com.charleshartman.porchlightpress.ui.components.isNearDuplicate
import com.charleshartman.porchlightpress.ui.components.TranslationLabel
import com.charleshartman.porchlightpress.ui.components.excerptAllowed
import com.charleshartman.porchlightpress.ui.motion.PorchlightMotion
import com.charleshartman.porchlightpress.ui.motion.rememberReduceMotion
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.theme.classicPaperBrush
import com.charleshartman.porchlightpress.ui.theme.modernSurfaceBrush
import com.charleshartman.porchlightpress.ui.util.TimeFormat
import com.charleshartman.porchlightpress.ui.speech.SpeechController
import com.charleshartman.porchlightpress.ui.speech.SpeechService

@Composable
fun ArticleScreen(
    viewModel: ArticleViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateVal by viewModel.state.collectAsState()
    val cur = stateVal
    val context = LocalContext.current
    val speech by SpeechController.state.collectAsState()
    val classic = LocalIsClassic.current
    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())
    val reduce = rememberReduceMotion()

    val story = cur.story
    val tr = cur.translation
    val showOriginal = cur.showOriginal
    val effectiveHeadline = story?.let { if (!showOriginal && tr != null) tr.headline else it.headline }.orEmpty()
    val effectiveDek = story?.let { if (!showOriginal && tr != null) tr.dek else it.dek }
    val effectiveBody = story?.let { if (!showOriginal && tr != null) tr.body else it.body }
    val isCurrentStory = speech.title == effectiveHeadline && speech.count > 0

    Column(modifier.fillMaxSize().then(bg).testTag("article-screen")) {
        speech.error?.let { Text(it, Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.error) }
        // Top bar with Back + Share + Listen
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("article-back")) { Text("‹ Back") }
            if (story != null) {
                TextButton(
                    onClick = {
                        if (isCurrentStory) {
                            if (speech.playing) SpeechController.pause(context) else SpeechController.resume(context)
                        } else {
                            val storyContent = when {
                                !showOriginal && !tr?.body.isNullOrBlank() -> tr!!.body
                                !story.body.isNullOrBlank() -> story.body
                                !story.excerpt.isNullOrBlank() -> story.excerpt
                                else -> null
                            }
                            val spokenText = listOfNotNull(
                                effectiveHeadline,
                                effectiveDek.takeIf { !it.isNullOrBlank() && !isNearDuplicate(effectiveHeadline, it) },
                                storyContent.takeIf { !it.isNullOrBlank() },
                            ).joinToString(". ")
                            SpeechController.play(
                                context,
                                effectiveHeadline,
                                spokenText,
                                if (!showOriginal) tr?.lang ?: "en" else "en",
                                cur.readAloudSpeed,
                            )
                        }
                    },
                    modifier = Modifier.testTag("article-listen"),
                ) {
                    Text(if (isCurrentStory && speech.playing) "Pause" else if (isCurrentStory) "Resume" else "Listen")
                }
                TextButton(
                    onClick = {
                        val shareText = buildString {
                            append(effectiveHeadline)
                            if (!effectiveDek.isNullOrBlank()) append("\n\n$effectiveDek")
                            if (story.aiGenerated) {
                                append("\n\n${sharePageUrl(story.id)}")
                                append("\n\nvia Porchlight Press \u00b7 AI-written brief, sources linked")
                            } else {
                                cur.sources.firstOrNull()?.let { append("\n\n${it.publisher}: ${it.url}") }
                            }
                        }
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "Share story"))
                    },
                    modifier = Modifier.testTag("article-share"),
                ) { Text("Share") }
            }
        }

        when {
            cur.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag("article-loading"))
            }
            cur.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(cur.error!!, modifier = Modifier.testTag("article-error"))
            }
            story != null -> {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Byline / dateline / Updated + version
                    val byline = if (story.aiGenerated) "AI Newsroom" else cur.sources.firstOrNull()?.publisher ?: "Staff"
                    Text(
                        byline.uppercase(),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("article-byline"),
                    )
                    // Headline / dek
                    Text(
                        effectiveHeadline,
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.semantics { heading() }.testTag("article-headline"),
                    )
                    // Hide deks that merely restate the headline (same rule as cards).
                    if (!effectiveDek.isNullOrBlank() && !isNearDuplicate(effectiveHeadline, effectiveDek)) {
                        Text(effectiveDek, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("article-dek"))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (story.aiGenerated) AiBadge()
                        if (cur.isTranslating) Text("translating…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("translating-chip"))
                    }
                    // Dateline + Updated
                    val updated = if (!story.updatedAt.isNullOrBlank() && story.updatedAt != story.publishedAt) story.updatedAt else null
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            TimeFormat.formatSourceTime(story.publishedAt, context),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.testTag("article-published"),
                        )
                        if (updated != null) {
                            Text("Updated ${TimeFormat.formatTimeShort(updated, context)} \u00b7 v${story.version}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("article-updated"))
                        } else if (story.version > 1) {
                            Text("v${story.version}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("article-version"))
                        }
                    }
                    // Image with caption + attribution (stock file photos outside
                    // the story's place collapse to no image).
                    ImageWithAttribution(
                        imageJson = story.imageJson,
                        headlineForContentDescription = effectiveHeadline,
                        locationsJson = story.locationsJson,
                        onOpenSource = cur.sources.firstOrNull()?.url?.let { url ->
                            {
                                val intent = CustomTabsIntent.Builder().build()
                                intent.launchUrl(context, android.net.Uri.parse(url))
                            }
                        },
                    )
                    // AI disclosure ABOVE the fold (spec)
                    if (story.aiGenerated) {
                        AiDisclosureBox()
                    }
                    // Translated label toggle
                    if (tr != null) {
                        TranslationLabel(isTranslated = true, onToggle = viewModel::toggleOriginal)
                    } else if (cur.isTranslating) {
                        Text("translating…", style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("translation-label"))
                    }

                    // Body vs excerpt variant. AI briefs show the full brief text;
                    // everything else shows the permitted source excerpt (when the
                    // license allows it) plus a way into the original reporting.
                    // Full article text is never republished here.
                    val firstSource = cur.sources.firstOrNull()
                    val activeSentence = speech.sentence.takeIf { speech.playing && speech.title == effectiveHeadline && it.isNotBlank() }

                    if (story.aiGenerated && !effectiveBody.isNullOrBlank()) {
                        ArticleBodyParagraphs(body = effectiveBody, activeSentence = activeSentence)
                        ReadFullStoryButton(
                            publisher = firstSource?.publisher,
                            url = firstSource?.url,
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth().testTag("source-card"),
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val showExcerpt = excerptAllowed(cur.sources.map { it.rightsMode }) &&
                                    !story.excerpt.isNullOrBlank()
                                if (showExcerpt && !story.excerpt.isNullOrBlank()) {
                                    HighlightedExcerpt(excerpt = story.excerpt, activeSentence = activeSentence)
                                } else if (!story.body.isNullOrBlank()) {
                                    Text(story.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else {
                                    Text(story.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("source-card-headline"))
                                    Text("Open the original to read more.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                        ReadFullStoryButton(
                            publisher = firstSource?.publisher,
                            url = firstSource?.url,
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))

                    // REPORTING SOURCES
                    Text(
                        "REPORTING SOURCES",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() }.testTag("sources-header"),
                    )
                    cur.sources.forEachIndexed { index, src ->
                        SourceRow(src = src, articleHeadline = effectiveHeadline, modifier = Modifier.testTag("source-$index"))
                    }
                    if (cur.sources.isEmpty()) {
                        Text("Sources unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sources-empty"))
                    }
                    // Source URL remains the original publisher's link.
                    TextButton(
                        onClick = {
                            val firstUrl = cur.sources.firstOrNull()?.url
                            if (firstUrl != null) {
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "${cur.sources.first().publisher}: ${story.headline}\n$firstUrl")
                                }
                                context.startActivity(Intent.createChooser(intent, "Share original article"))
                            }
                        },
                        modifier = Modifier.testTag("share-original"),
                    ) { Text("Share original article") }

                    if (speech.title == effectiveHeadline && speech.count > 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            TextButton(onClick = { SpeechController.command(context, SpeechService.PREVIOUS) }, modifier = Modifier.testTag("article-previous")) { Text("Previous") }
                            TextButton(onClick = { if (speech.playing) SpeechController.pause(context) else SpeechController.resume(context) }, modifier = Modifier.testTag("article-play-pause")) {
                                Text(if (speech.playing) "Pause" else "Play")
                            }
                            TextButton(onClick = { SpeechController.command(context, SpeechService.NEXT) }, modifier = Modifier.testTag("article-next")) { Text("Next") }
                            TextButton(onClick = { SpeechController.stop(context) }, modifier = Modifier.testTag("article-stop")) { Text("Stop") }
                        }
                        Text("Sentence ${speech.index + 1} of ${speech.count}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // Docked Audio Player Bar visible whenever speech is active
        if (speech.count > 0 && (speech.title == effectiveHeadline || speech.title.isNotBlank())) {
            AudioPlayerBar(
                state = speech,
                onPlayPause = {
                    if (speech.playing) SpeechController.pause(context) else SpeechController.resume(context)
                },
                onSeek = { targetIndex ->
                    SpeechController.seekTo(context, targetIndex)
                },
                onPrevious = { SpeechController.command(context, SpeechService.PREVIOUS) },
                onNext = { SpeechController.command(context, SpeechService.NEXT) },
                onStop = { SpeechController.stop(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("article-audio-player"),
            )
        }
    }
}

/** Shareable story page, derived from the feed base so staging/custom domains work. */
fun sharePageUrl(storyId: String): String =
    "${BuildConfig.FEED_BASE_URL.trimEnd('/')}/s/$storyId.html"

/** Highlighted text for excerpt cards. */
@Composable
private fun HighlightedExcerpt(
    excerpt: String,
    activeSentence: String? = null,
    modifier: Modifier = Modifier,
) {
    val match = if (activeSentence.isNullOrBlank()) -1 else excerpt.indexOf(activeSentence, ignoreCase = true)
    if (match >= 0 && activeSentence != null) {
        val annotated = buildAnnotatedString {
            append(excerpt.substring(0, match))
            withStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer)) {
                append(excerpt.substring(match, match + activeSentence.length))
            }
            append(excerpt.substring(match + activeSentence.length))
        }
        Text(annotated, style = MaterialTheme.typography.bodyLarge, modifier = modifier.testTag("source-card-excerpt"))
    } else {
        Text(excerpt, style = MaterialTheme.typography.bodyLarge, modifier = modifier.testTag("source-card-excerpt"))
    }
}

/** Full AI brief, split into paragraphs on blank lines. */
@Composable
private fun ArticleBodyParagraphs(
    body: String,
    activeSentence: String? = null,
    modifier: Modifier = Modifier,
) {
    val paragraphs = body.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
    Column(modifier.testTag("article-body"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        paragraphs.forEach { paragraph ->
            val match = if (activeSentence.isNullOrBlank()) -1 else paragraph.indexOf(activeSentence, ignoreCase = true)
            if (match >= 0 && activeSentence != null) {
                val annotated = buildAnnotatedString {
                    append(paragraph.substring(0, match))
                    withStyle(SpanStyle(background = MaterialTheme.colorScheme.primaryContainer)) {
                        append(paragraph.substring(match, match + activeSentence.length))
                    }
                    append(paragraph.substring(match + activeSentence.length))
                }
                Text(annotated, style = MaterialTheme.typography.bodyLarge)
            } else {
                Text(paragraph, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

/** Prominent full-width button into the original reporting. Hidden with no URL. */
@Composable
private fun ReadFullStoryButton(publisher: String?, url: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    if (url.isNullOrBlank()) return
    androidx.compose.material3.Button(
        onClick = {
            val intent = CustomTabsIntent.Builder().build()
            intent.launchUrl(context, android.net.Uri.parse(url))
        },
        modifier = modifier.fillMaxWidth().testTag("read-full-story"),
    ) {
        Text(
            if (publisher.isNullOrBlank()) "Read the full story"
            else "Read the full story at $publisher",
        )
    }
}

@Composable
private fun SourceRow(src: StorySource, articleHeadline: String? = null, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 1.dp,
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                val intent = CustomTabsIntent.Builder().build()
                intent.launchUrl(context, android.net.Uri.parse(src.url))
            },
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(src.publisher, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.testTag("source-publisher"))
            // Never repeat the article headline in the source list.
            if (articleHeadline == null || !src.headline.trim().equals(articleHeadline.trim(), ignoreCase = true)) {
                Text(src.headline, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("source-headline"))
            }
            val time = TimeFormat.formatSourceTime(src.publishedAt, context)
            if (time.isNotBlank()) {
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("Read original →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("source-link"))
        }
    }
}
