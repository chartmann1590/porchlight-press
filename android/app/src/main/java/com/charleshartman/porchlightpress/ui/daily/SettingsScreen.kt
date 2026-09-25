package com.charleshartman.porchlightpress.ui.daily

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.local.SavedLocation
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onAddLocation: () -> Unit,
    onSources: () -> Unit,
    onAbout: () -> Unit,
    onPrivacyOptions: () -> Unit = {},
) {
    val prefs by container.prefs.prefs.collectAsState(initial = com.charleshartman.porchlightpress.data.local.AppPrefs())
    val locations by remember(container) { container.db.savedLocationDao().observeAll() }
        .collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<SavedLocation?>(null) }
    var rename by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<SavedLocation?>(null) }
    var languageMenu by remember { mutableStateOf(false) }
    var themeMenu by remember { mutableStateOf(false) }
    var layoutMenu by remember { mutableStateOf(false) }
    var clockMenu by remember { mutableStateOf(false) }
    var modelMessage by remember { mutableStateOf<String?>(null) }
    var modelBusy by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp).testTag("settings-list"),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SettingHeader("Locations")
            locations.forEachIndexed { index, loc ->
                Text(loc.label + if (loc.isHome) " · Home" else "",
                    style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { scope.launch { container.prefs.setActiveLocationId(loc.id) } }) {
                        Text(if (prefs.activeLocationId == loc.id) "Reading" else "Switch")
                    }
                    TextButton(onClick = { scope.launch { container.db.savedLocationDao().setHome(loc.id) } }) {
                        Text("Home")
                    }
                    TextButton(onClick = { editing = loc; rename = loc.label }) { Text("Rename") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        if (index > 0) scope.launch {
                            container.db.savedLocationDao().reorder(loc.id, index - 1)
                            container.db.savedLocationDao().reorder(locations[index - 1].id, index)
                        }
                    }, enabled = index > 0) { Text("Up") }
                    TextButton(onClick = {
                        if (index < locations.lastIndex) scope.launch {
                            container.db.savedLocationDao().reorder(loc.id, index + 1)
                            container.db.savedLocationDao().reorder(locations[index + 1].id, index)
                        }
                    }, enabled = index < locations.lastIndex) { Text("Down") }
                    TextButton(onClick = { deleting = loc }) { Text("Remove") }
                }
                HorizontalDivider()
            }
            Button(onClick = onAddLocation, modifier = Modifier.testTag("add-location")) {
                Text("Add location")
            }

            SettingHeader("Your paper")
            Row {
                TextButton(onClick = { languageMenu = true }) {
                    Text("Language: " + prefs.appLanguage.uppercase())
                }
                DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                    container.translationRepository.supportedLanguages().sorted().forEach { lang ->
                        DropdownMenuItem(text = { Text(lang.uppercase()) }, onClick = {
                            languageMenu = false
                            scope.launch {
                                modelBusy = true
                                modelMessage = "Downloading language model…"
                                val ok = runCatching {
                                    container.translationRepository.ensureModel(lang, prefs.dataSaver)
                                }.getOrDefault(false)
                                if (ok) {
                                    container.prefs.setAppLanguage(lang)
                                    modelMessage = null
                                } else modelMessage = "Language model unavailable. Try again online."
                                modelBusy = false
                            }
                        })
                    }
                }
            }
            if (modelBusy || modelMessage != null) Text(modelMessage ?: "Preparing language…")
            Row {
                TextButton(onClick = { themeMenu = true }) { Text("Theme: " + prefs.theme) }
                DropdownMenu(expanded = themeMenu, onDismissRequest = { themeMenu = false }) {
                    listOf("system", "light", "dark").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            themeMenu = false; scope.launch { container.prefs.setTheme(option) }
                        })
                    }
                }
                TextButton(onClick = { layoutMenu = true }) { Text("Layout: " + prefs.layout) }
                DropdownMenu(expanded = layoutMenu, onDismissRequest = { layoutMenu = false }) {
                    listOf("classic", "compact").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            layoutMenu = false; scope.launch { container.prefs.setLayout(option) }
                        })
                    }
                }
            }
            Text("Text size: " + (prefs.textScale * 100).toInt() + "%")
            Slider(value = prefs.textScale.toFloat(), onValueChange = {
                scope.launch { container.prefs.setTextScale(it.toDouble()) }
            }, valueRange = 0.8f..1.5f)
            Row {
                TextButton(onClick = { clockMenu = true }) {
                    Text("Time: " + prefs.clockFormat)
                }
                DropdownMenu(expanded = clockMenu, onDismissRequest = { clockMenu = false }) {
                    listOf("system", "12h", "24h").forEach { option ->
                        DropdownMenuItem(text = { Text(option) }, onClick = {
                            clockMenu = false; scope.launch { container.prefs.setClockFormat(option) }
                        })
                    }
                }
            }
            SettingToggle("Data saver", prefs.dataSaver) {
                scope.launch { container.prefs.setDataSaver(it) }
            }

            SettingHeader("Interests")
            Text("Preferred topics rise within their section. Your choices stay on this device.")
            listOf("politics", "business", "sports", "arts", "science", "technology",
                "health", "education", "transportation").chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { topic ->
                        FilterChip(selected = topic in prefs.interests,
                            onClick = {
                                scope.launch {
                                    container.prefs.setInterests(
                                        if (topic in prefs.interests) prefs.interests - topic
                                        else prefs.interests + topic
                                    )
                                }
                            }, label = { Text(topic) })
                    }
                }
            }
            TextButton(onClick = { scope.launch { container.prefs.setInterests(emptySet()) } }) {
                Text("Reset interests")
            }

            SettingHeader("Notifications")
            Text("Only the alerts you choose. Quiet hours apply to news updates.")
            SettingToggle("Severe weather", prefs.notifySevere) {
                scope.launch { container.prefs.setNotifySevere(it) }
            }
            SettingToggle("Breaking local news", prefs.notifyBreaking) {
                scope.launch { container.prefs.setNotifyBreaking(it) }
            }
            SettingToggle("Morning edition", prefs.notifyMorning) {
                scope.launch { container.prefs.setNotifyMorning(it) }
            }
            SettingToggle("Evening edition", prefs.notifyEvening) {
                scope.launch { container.prefs.setNotifyEvening(it) }
            }
            Text("Quiet hours: " + prefs.quietStartHour + ":00–" + prefs.quietEndHour + ":00")
            Row {
                TextButton(onClick = {
                    scope.launch { container.prefs.setQuietHours((prefs.quietStartHour + 1) % 24, prefs.quietEndHour) }
                }) { Text("Start later") }
                TextButton(onClick = {
                    scope.launch { container.prefs.setQuietHours(prefs.quietStartHour, (prefs.quietEndHour + 1) % 24) }
                }) { Text("End later") }
            }

            SettingHeader("Read aloud")
            Text("Speed: " + "%.1f×".format(prefs.readAloudSpeed))
            Slider(value = prefs.readAloudSpeed.toFloat(), onValueChange = {
                scope.launch { container.prefs.setReadAloudSpeed(it.toDouble()) }
            }, valueRange = 0.5f..2.0f)

            SettingHeader("Privacy and storage")
            SettingToggle("Anonymous analytics", prefs.analyticsConsent) {
                scope.launch { container.prefs.setAnalyticsConsent(it) }
            }
            SettingToggle("Crash reports", prefs.crashConsent) {
                scope.launch { container.prefs.setCrashConsent(it) }
            }
            TextButton(onClick = onPrivacyOptions) { Text("Ad privacy options") }
            TextButton(onClick = {
                scope.launch {
                    val now = System.currentTimeMillis()
                    container.db.weatherDao().deleteExpired(now)
                    container.db.editionDao().deleteOlderThan(now - 3L * 24L * 60L * 60L * 1000L)
                    container.db.storyDao().deleteExpiredUnsaved(
                        java.time.Instant.ofEpochMilli(now - 7L * 24L * 60L * 60L * 1000L).toString())
                    container.db.storyDao().pruneOrphanFts()
                    container.db.storyDao().pruneOrphanSources()
                    container.db.storyDao().pruneOrphanTranslations()
                }
            }) { Text("Clear expired cache") }
            TextButton(onClick = onSources) { Text("Sources") }
            TextButton(onClick = onAbout) { Text("About") }
            TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://chartmann1590.github.io/porchlight-press/privacy/")))
            }) { Text("Privacy policy") }
        }
    }

    editing?.let { loc ->
        AlertDialog(onDismissRequest = { editing = null },
            title = { Text("Rename location") },
            text = { OutlinedTextField(rename, { rename = it }, label = { Text("Name") }) },
            confirmButton = { TextButton(onClick = {
                scope.launch { container.db.savedLocationDao().rename(loc.id, rename.trim()) }
                editing = null
            }, enabled = rename.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    deleting?.let { loc ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Remove " + loc.label + "?") },
            text = { Text("Downloaded editions for this place will also be removed.") },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    container.db.savedLocationDao().delete(loc.id)
                    val remaining = container.db.savedLocationDao().all()
                    if (loc.isHome && remaining.isNotEmpty()) {
                        container.db.savedLocationDao().setHome(remaining.first().id)
                    }
                    if (prefs.activeLocationId == loc.id) {
                        container.prefs.setActiveLocationId(remaining.firstOrNull()?.id)
                    }
                }
                deleting = null
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun SettingHeader(title: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
