package com.charleshartman.porchlightpress.ui.daily

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.local.SavedStory
import com.charleshartman.porchlightpress.data.local.Story
import com.charleshartman.porchlightpress.data.repo.StorySearchRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onStoryClick: (String) -> Unit,
    downloadedPapers: @Composable () -> Unit = {},
) {
    val stories by remember(container) { container.db.storyDao().observeSavedStories() }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }
    Scaffold(
        topBar = { TopAppBar(title = { Text("Saved") }, navigationIcon = {
            TextButton(onClick = onBack) { Text("Back") }
        }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Stories") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Downloaded papers") })
            }
            if (tab == 1) {
                downloadedPapers()
            } else if (stories.isEmpty()) {
                Text("Stories you save will be here, ready to read offline.",
                    Modifier.padding(24.dp).testTag("saved-empty"))
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("saved-list")) {
                    items(stories, key = { it.id }) { story ->
                        val dismiss = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                if (value != SwipeToDismissBoxValue.Settled) {
                                    scope.launch {
                                        val previous = container.db.storyDao().savedStory(story.id)
                                        container.db.storyDao().unsaveStory(story.id)
                                        val result = snackbar.showSnackbar("Removed from Saved", "Undo")
                                        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed && previous != null) {
                                            container.db.storyDao().saveStory(previous)
                                        }
                                    }
                                    true
                                } else false
                            },
                        )
                        SwipeToDismissBox(state = dismiss, backgroundContent = {
                            Text("Remove", Modifier.padding(20.dp))
                        }) {
                            StoryListRow(story, onStoryClick)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoryListRow(story: Story, onStoryClick: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(story.headline, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { Text(story.dek ?: story.category) },
        modifier = Modifier.fillMaxWidth().clickable { onStoryClick(story.id) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onStoryClick: (String) -> Unit,
) {
    val repo = remember(container) { StorySearchRepository(container.db) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var locationId by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Story>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }
    var locationMenu by remember { mutableStateOf(false) }
    val locations by remember(container) { container.db.savedLocationDao().observeAll() }
        .collectAsState(initial = emptyList())
    LaunchedEffect(query, category, locationId) {
        delay(180)
        searched = query.isNotBlank()
        results = runCatching { repo.search(query, category, locationId) }.getOrDefault(emptyList())
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Search downloaded stories") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Headline, body, publisher, place") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("search-input"),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column {
                    TextButton(onClick = { categoryMenu = true }) {
                        Text(if (category.isEmpty()) "All sections" else category.replaceFirstChar { it.uppercase() })
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        listOf("", "local", "regional", "state", "national", "world", "weather",
                            "politics", "business", "sports", "arts", "science", "technology").forEach { c ->
                            DropdownMenuItem(text = { Text(if (c.isEmpty()) "All sections" else c) },
                                onClick = { category = c; categoryMenu = false })
                        }
                    }
                }
                Column {
                    TextButton(onClick = { locationMenu = true }) {
                        Text(locations.firstOrNull { it.id == locationId }?.label ?: "All locations")
                    }
                    DropdownMenu(expanded = locationMenu, onDismissRequest = { locationMenu = false }) {
                        DropdownMenuItem(text = { Text("All locations") },
                            onClick = { locationId = ""; locationMenu = false })
                        locations.forEach { loc ->
                            DropdownMenuItem(text = { Text(loc.label) },
                                onClick = { locationId = loc.id; locationMenu = false })
                        }
                    }
                }
            }
            if (results.isEmpty()) {
                Text(if (searched) "No downloaded stories match your search." else
                    "Search stories from papers on this device, even offline.",
                    Modifier.padding(24.dp).testTag("search-empty"))
            } else {
                LazyColumn(Modifier.fillMaxSize().testTag("search-results")) {
                    items(results, key = { it.id }) { StoryListRow(it, onStoryClick) }
                }
            }
        }
    }
}
