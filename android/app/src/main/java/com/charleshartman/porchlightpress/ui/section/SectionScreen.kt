package com.charleshartman.porchlightpress.ui.section

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.ads.AdMobGate
import com.charleshartman.porchlightpress.ui.components.BannerAdSlot
import com.charleshartman.porchlightpress.ui.components.SectionHeader
import com.charleshartman.porchlightpress.ui.components.StoryCard
import com.charleshartman.porchlightpress.ui.components.TopicTile
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.height
import com.charleshartman.porchlightpress.ui.theme.PorchlightColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionScreen(
    sectionId: String,
    viewModel: SectionViewModel,
    gate: AdMobGate,
    onStoryClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    ColumnFull(modifier) {
        // Simple header with back + title (newspaper section screen)
        androidx.compose.material3.TopAppBar(
            title = { Text(state.title, modifier = Modifier.testTag("section-title-$sectionId")) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.testTag("section-back")) {
                    Text("‹", style = MaterialTheme.typography.headlineMedium)
                }
            },
        )
        PullToRefreshBox(isRefreshing = false, onRefresh = {}, modifier = Modifier.fillMaxSize()) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.testTag("section-loading"))
                }
                state.error != null && state.stories.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(state.error!!, modifier = Modifier.testTag("section-error"))
                }
                state.stories.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No stories in ${state.title}.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("section-empty"))
                }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("section-list-$sectionId"),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    item { SectionHeader(title = state.title, modifier = Modifier.padding(horizontal = 16.dp)) }
                    item {
                        val desks = remember(state.stories) {
                            state.stories.map { it.story.category }.distinct().filter { it.isNotBlank() }.take(4)
                        }
                        if (desks.isNotEmpty()) {
                            Text(
                                "Topical Desks",
                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("topical-desks-header"),
                            )
                            desks.chunked(2).forEach { row ->
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                ) {
                                    row.forEach { cat ->
                                        TopicTile(
                                            title = cat,
                                            subtitle = "Coverage from this edition",
                                            badge = "${state.stories.count { it.story.category == cat }} stories",
                                            onClick = { },
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    items(state.stories, key = { it.story.id }) { item ->
                        StoryCard(
                            story = item.story,
                            translatedHeadline = item.translation?.headline,
                            translatedDek = item.translation?.dek,
                            isTranslating = item.isTranslating,
                            sourceLabel = item.sourceLabel,
                            updatedAt = item.story.updatedAt ?: item.story.publishedAt,
                            onClick = { onStoryClick(item.story.id) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                    item { BannerAdSlot(gate = gate, modifier = Modifier.padding(top = 12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ColumnFull(modifier: Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.Column(modifier.fillMaxSize(), content = { content() })
}
