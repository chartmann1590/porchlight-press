package com.charleshartman.porchlightpress.ui.frontpage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.ui.components.BannerAdSlot
import com.charleshartman.porchlightpress.ui.components.EditionLabel
import com.charleshartman.porchlightpress.ui.components.HeroStory
import com.charleshartman.porchlightpress.ui.components.Masthead
import com.charleshartman.porchlightpress.ui.components.NativeAdBox
import com.charleshartman.porchlightpress.ui.components.SectionHeader
import com.charleshartman.porchlightpress.ui.components.StoryCard
import com.charleshartman.porchlightpress.ui.components.TranslationLabel
import com.charleshartman.porchlightpress.ui.theme.LocalLayout
import com.charleshartman.porchlightpress.ui.theme.modernSurfaceBrush
import com.charleshartman.porchlightpress.ui.theme.classicPaperBrush
import com.charleshartman.porchlightpress.ui.theme.LocalIsClassic
import com.charleshartman.porchlightpress.ui.components.PorchlightShimmer
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FrontPageScreen(
    container: AppContainer,
    viewModel: FrontPageViewModel,
    gate: AdMobGate,
    onStoryClick: (String) -> Unit,
    onSectionClick: (String) -> Unit,
    onSwitchLocation: () -> Unit,
    onOpenInfo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val lang = prefs?.appLanguage ?: "en"
    val clock = prefs?.clockFormat ?: "system"
    val width = LocalConfiguration.current.screenWidthDp
    val isTwoColumn = width >= 600 && state.sections.isNotEmpty()

    val classic = LocalIsClassic.current
    val bg = if (classic) Modifier.background(classicPaperBrush()) else Modifier.background(modernSurfaceBrush())
    Column(modifier.fillMaxSize().then(bg).testTag("front-page")) {
        // Masthead stays sticky at top outside the scroll (newspaper feel).
        Masthead(
            placeLabel = state.place?.label,
            onSwitchLocation = onSwitchLocation,
        )
        // Edition label + weather teaser row
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            EditionLabel(generatedAt = state.generatedAt, kind = state.editionKind, clockOverride = clock)
            // Compact weather slot (Phase 7 fills this; stub keeps layout honest without blocking).
            Text(
                "— Weather —",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("weather-teaser"),
            )
        }
        if (state.offline) {
            Text(
                "Offline \u00b7 showing edition from ${state.generatedAt ?: ""}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("offline-banner"),
            )
        }
        // Section chips (Local / Regional / State / National / World + categories)
        ChipsRow(state, onSectionClick)

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize().padding(16.dp).testTag("front-loading"), contentAlignment = Alignment.TopCenter) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        PorchlightShimmer(height = 220.dp)
                        PorchlightShimmer(height = 120.dp)
                        PorchlightShimmer(height = 120.dp)
                    }
                }
                state.error != null && state.sections.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.error!!, modifier = Modifier.testTag("front-error"))
                }
                else -> FrontPageList(
                    state = state,
                    lang = lang,
                    isTwoColumn = isTwoColumn,
                    gate = gate,
                    onStoryClick = onStoryClick,
                )
            }
        }
    }
}

@Composable
private fun ChipsRow(
    state: FrontPageUiState,
    onSectionClick: (String) -> Unit,
) {
    val chipTitles = remember(state.place) {
        val p = state.place
        buildList {
            add("Local")
            if (p?.metro != null) add("Regional")
            if (p?.admin1 != null) add("State")
            add("National")
            add("World")
        }
    }
    androidx.compose.foundation.lazy.LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag("section-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(chipTitles.size, key = { chipTitles[it] }) { idx ->
            val title = chipTitles[idx]
            val sectionId = title.lowercase()
            androidx.compose.material3.FilterChip(
                selected = false,
                onClick = { onSectionClick(sectionId) },
                label = { Text(title.uppercase()) },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                modifier = Modifier.testTag("chip-$sectionId"),
            )
        }
    }
}

@Composable
private fun FrontPageList(
    state: FrontPageUiState,
    lang: String,
    isTwoColumn: Boolean,
    gate: AdMobGate,
    onStoryClick: (String) -> Unit,
) {
    // Flatten sections → interleaved with ad slots every N sections.
    val adsEveryN = gate.config.adsEveryNSections.coerceAtLeast(1)
    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("front-list"),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        var sectionIndex = 0
        state.sections.forEach { sec ->
            val stories = sec.stories
            if (stories.isEmpty()) return@forEach
            item(key = "header-${sec.id}") {
                SectionHeader(title = displaySectionTitle(sec, state), modifier = Modifier.padding(horizontal = 16.dp))
            }
            // Language translation banner per section when lang != en (spec: label on translated stories)
            // Show once at top of section
            if (lang != "en") {
                item(key = "tr-${sec.id}") {
                    TranslationChipRow(isTranslating = stories.any { it.isTranslating }, lang = lang)
                }
            }
            // Hero is first story of first section
            val isFirstSection = sectionIndex == 0
            if (isFirstSection && stories.isNotEmpty()) {
                val hero = stories.first()
                item(key = "hero-${hero.story.id}") {
                    HeroStory(
                        story = hero.story,
                        translatedHeadline = hero.translation?.headline,
                        translatedDek = hero.translation?.dek,
                        isTranslating = hero.isTranslating,
                        sourceLabel = hero.sourceLabel,
                        updatedAt = hero.story.updatedAt ?: hero.story.publishedAt,
                        onClick = { onStoryClick(hero.story.id) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).animateItem(),
                    )
                }
                // Remaining stories in grid/list after hero
                val rest = stories.drop(1)
                if (isTwoColumn) {
                    // 2-column grid where hero spans; rest in two columns
                    itemsIndexedInGrid(rest, columns = 2, gate = gate, onStoryClick = onStoryClick, heroSpans = false)
                } else {
                    items(rest.size) { i ->
                        val item = rest[i]
                        StoryCard(
                            story = item.story,
                            translatedHeadline = item.translation?.headline,
                            translatedDek = item.translation?.dek,
                            isTranslating = item.isTranslating,
                            sourceLabel = item.sourceLabel,
                            updatedAt = item.story.updatedAt ?: item.story.publishedAt,
                            onClick = { onStoryClick(item.story.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).animateItem(),
                        )
                    }
                }
            } else {
                // Non-hero sections: simple list or 2-col grid
                if (isTwoColumn) {
                    itemsIndexedInGrid(stories, columns = 2, gate = gate, onStoryClick = onStoryClick, heroSpans = false)
                } else {
                    items(stories.size) { i ->
                        val it = stories[i]
                        StoryCard(
                            story = it.story,
                            translatedHeadline = it.translation?.headline,
                            translatedDek = it.translation?.dek,
                            isTranslating = it.isTranslating,
                            sourceLabel = it.sourceLabel,
                            updatedAt = it.story.updatedAt ?: it.story.publishedAt,
                            onClick = { onStoryClick(it.story.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).animateItem(),
                        )
                    }
                }
            }
            // Native ADVERTISEMENT between sections every N
            if ((sectionIndex + 1) % adsEveryN == 0) {
                item(key = "ad-${sec.id}") {
                    NativeAdBox(gate = gate, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            sectionIndex++
        }
        // Bottom banner slot (Phase 9) – collapse when disabled.
        item(key = "banner-bottom") {
            BannerAdSlot(gate = gate, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

// Helper to emit grid items inside a LazyColumn via explicit rows (simpler than LazyVerticalGrid)
private fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedInGrid(
    items: List<FrontStoryUi>,
    columns: Int,
    gate: AdMobGate,
    onStoryClick: (String) -> Unit,
    heroSpans: Boolean,
) {
    val rows = items.chunked(columns)
    rows.forEachIndexed { rowIdx, row ->
        item(key = "grid-r-$rowIdx-${row.firstOrNull()?.story?.id}") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { it ->
                    StoryCard(
                        story = it.story,
                        translatedHeadline = it.translation?.headline,
                        translatedDek = it.translation?.dek,
                        isTranslating = it.isTranslating,
                        sourceLabel = it.sourceLabel,
                        updatedAt = it.story.updatedAt ?: it.story.publishedAt,
                        onClick = { onStoryClick(it.story.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill remaining weight when odd count
                if (row.size < columns) {
                    repeat(columns - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun TranslationChipRow(isTranslating: Boolean, lang: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            if (isTranslating) "translating…" else "Translated on your device by Google ML Kit",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("translation-label-section"),
        )
    }
}

/**
 * Metro IDs look like "us-ny-capital-region": strip one leading
 * "{country}-{admin1}-" segment when present, then prettify. Unknown
 * formats fall through to the generic label instead of garbage.
 */
private fun humanizeMetro(metro: String?): String {
    if (metro.isNullOrBlank()) return "REGIONAL"
    val stripped = metro.replace(Regex("^[a-zA-Z]{2}-[a-zA-Z0-9]+-"), "")
    return stripped.replace("-", " ").uppercase().ifBlank { "REGIONAL" }
}

/** "US-NY" -> "NY"; anything without a subdivision code -> generic label. */
private fun stateName(admin1: String?): String {
    if (admin1.isNullOrBlank() || "-" !in admin1) return "STATE"
    return admin1.substringAfter("-").uppercase()
}

private fun displaySectionTitle(sec: com.charleshartman.porchlightpress.ui.frontpage.SectionUi, state: FrontPageUiState): String {
    // Map generic Top Stories → location-aware titles per Phase 6 spec:
    // "LOCAL — SCHENECTADY", "CAPITAL REGION", "NEW YORK", "UNITED STATES"
    val p = state.place
    return when (sec.id.lowercase()) {
        "top" -> when {
            p?.city != null -> "LOCAL \u2014 ${p.city.uppercase()}"
            p?.admin2 != null -> p.admin2.uppercase()
            p?.admin1 != null -> stateName(p.admin1)
            p?.country == "US" -> "UNITED STATES"
            else -> sec.title.uppercase()
        }
        "local" -> "LOCAL \u2014 ${(p?.city ?: p?.label ?: "LOCAL").uppercase()}"
        "regional" -> humanizeMetro(p?.metro)
        "state" -> stateName(p?.admin1)
        "national" -> "UNITED STATES"
        "world" -> "WORLD"
        else -> sec.title.uppercase()
    }
}
