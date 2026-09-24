package com.charleshartman.porchlightpress.ui.article

import android.content.Intent
import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.charleshartman.porchlightpress.BuildConfig
import com.charleshartman.porchlightpress.data.local.StorySource
import com.charleshartman.porchlightpress.ui.components.AiBadge
import com.charleshartman.porchlightpress.ui.components.AiDisclosureBox
import com.charleshartman.porchlightpress.ui.components.ImageWithAttribution
import com.charleshartman.porchlightpress.ui.components.TranslationLabel
import com.charleshartman.porchlightpress.ui.util.TimeFormat

@Composable
fun ArticleScreen(
    viewModel: ArticleViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stateVal by viewModel.state.collectAsState()
    val cur = stateVal
    val context = LocalContext.current

    Column(modifier.fillMaxSize().testTag("article-screen")) {
        // Top bar with Back + Share
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("article-back")) { Text("‹ Back") }
            val story = cur.story
            if (story != null) {
                TextButton(
                    onClick = {
                        val shareText = buildString {
                            append(story.headline)
                            if (!story.dek.isNullOrBlank()) append("\n\n${story.dek}")
                            append("\n\n${sharePageUrl(story.id)}")
                            append("\n\nvia Porchlight Press \u00b7 AI-written brief, sources linked")
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
            cur.story != null -> {
                val story = cur.story
                val tr = cur.translation
                val showOriginal = cur.showOriginal
                val effectiveHeadline = if (!showOriginal && tr != null) tr.headline else story.headline
                val effectiveDek = if (!showOriginal && tr != null) tr.dek else story.dek
                val effectiveBody = if (!showOriginal && tr != null) tr.body else story.body

                Column(
                    Modifier
                        .fillMaxSize()
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
                    if (!effectiveDek.isNullOrBlank()) {
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
                    // Image with caption + attribution
                    ImageWithAttribution(
                        imageJson = story.imageJson,
                        headlineForContentDescription = effectiveHeadline,
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

                    // Body vs source-card variant
                    if (story.aiGenerated && !effectiveBody.isNullOrBlank()) {
                        Text(effectiveBody, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("article-body"))
                    } else {
                        // Source-card: original headline + publisher + permitted excerpt, no generated text
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().testTag("source-card")) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Source card — AI brief withheld or not yet generated.", style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
                                Text(story.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("source-card-headline"))
                                // Excerpt not stored separately; body shown below when present
                                if (!story.body.isNullOrBlank()) {
                                    Text(story.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text("Open the original to read more.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    // REPORTING SOURCES
                    Text(
                        "REPORTING SOURCES",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { heading() }.testTag("sources-header"),
                    )
                    cur.sources.forEachIndexed { index, src ->
                        SourceRow(src = src, modifier = Modifier.testTag("source-$index"))
                    }
                    if (cur.sources.isEmpty()) {
                        Text("Sources unavailable.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("sources-empty"))
                    }
                    // Share footer overflow (spec: share button in article top bar + overflow)
                    TextButton(
                        onClick = {
                            val firstUrl = cur.sources.firstOrNull()?.url
                            if (firstUrl != null) {
                                val intent = CustomTabsIntent.Builder().build()
                                intent.launchUrl(context, android.net.Uri.parse(firstUrl))
                            }
                        },
                        modifier = Modifier.testTag("share-original"),
                    ) { Text("Share original article") }
                }
            }
        }
    }
}

/** Shareable story page, derived from the feed base so staging/custom domains work. */
fun sharePageUrl(storyId: String): String =
    "${BuildConfig.FEED_BASE_URL.trimEnd('/')}/s/$storyId.html"

@Composable
private fun SourceRow(src: StorySource, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .clickable {
                val intent = CustomTabsIntent.Builder().build()
                intent.launchUrl(context, android.net.Uri.parse(src.url))
            }
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(src.publisher, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.testTag("source-publisher"))
        Text(src.headline, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("source-headline"))
        val time = TimeFormat.formatSourceTime(src.publishedAt, context)
        val rights = src.rightsMode?.let { " \u00b7 $it" } ?: ""
        Text(
            "${if (time.isNotBlank()) "$time$rights" else rights.trimStart()}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Read original →", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("source-link"))
    }
}

