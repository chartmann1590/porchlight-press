package com.charleshartman.porchlightpress.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * Minimal post-onboarding home (Phase 5). Shows that the first edition is
 * stored and readable; Phase 6 builds the newspaper front page on top of the
 * same Room data.
 */
@Composable
fun HomeScreen(container: AppContainer) {
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val date = rememberDate()
    Column(
        Modifier.fillMaxSize().padding(20.dp).testTag("home"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PORCHLIGHT PRESS", style = MaterialTheme.typography.headlineSmall)
        Text("$date · ${prefs?.appLanguage ?: "en"}", style = MaterialTheme.typography.bodyMedium)
        val locationId = prefs?.activeLocationId
        if (locationId != null) {
            EditionSummary(container, locationId)
        } else {
            Text("No edition yet.")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                scope.launch {
                    container.prefs.setOnboardingDone(false)
                    container.prefs.setReadingStarted(false)
                }
            },
            modifier = Modifier.testTag("home-rerun"),
        ) { Text("Run setup again") }
    }
}

@Composable
private fun EditionSummary(container: AppContainer, locationId: String) {
    val content = androidx.compose.runtime.produceState<Result<String>?>(initialValue = null, locationId) {
        value = runCatching {
            val c = container.editionRepository.cachedContent(locationId, "latest")
                ?: return@runCatching "Fetching your paper…"
            val stories = c.sections.sumOf { it.second.size }
            buildString {
                append("Latest edition · ${stories} stories")
                if (c.sections.isNotEmpty()) {
                    append("\n")
                    append(c.sections.joinToString(" · ") { it.first.title })
                }
            }
        }
    }.value
    Text(
        content?.getOrDefault("Couldn't load the saved edition.") ?: "Loading…",
        modifier = Modifier.fillMaxWidth().testTag("home-edition"),
    )
}

@Composable
private fun rememberDate(): String {
    return try {
        ZonedDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))
    } catch (e: Exception) {
        ""
    }
}
