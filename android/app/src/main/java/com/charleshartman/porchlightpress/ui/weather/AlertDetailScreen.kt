package com.charleshartman.porchlightpress.ui.weather

import androidx.browser.customtabs.CustomTabsIntent
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.data.weather.isWarningLevel
import com.charleshartman.porchlightpress.domain.Place
import kotlinx.coroutines.flow.first

/**
 * Alert detail: the full NWS text as plain text (never HTML) plus a link to
 * weather.gov. Warning-level alerts show any translation above the original
 * English, which is always present inline.
 */
@Composable
fun AlertDetailScreen(
    container: AppContainer,
    alertId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val lang = prefs?.appLanguage ?: "en"
    val loaded by produceState<Loaded?>(initialValue = null, alertId) {
        value = loadAlert(container, alertId)
    }
    Column(modifier.fillMaxSize().testTag("alert-detail-screen")) {
        TextButton(onClick = onBack, modifier = Modifier.testTag("alert-detail-back")) { Text("‹ Back") }
        when (val l = loaded) {
            null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag("alert-detail-loading"))
            }
            else -> {
                val alert = l.alert
                if (alert == null) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("That alert is no longer active.", modifier = Modifier.testTag("alert-detail-gone"))
                    }
                } else {
                    AlertDetailContent(container = container, alert = alert, lang = lang)
                }
            }
        }
    }
}

private data class Loaded(val alert: WeatherAlert?)

private suspend fun loadAlert(container: AppContainer, alertId: String): Loaded {
    return try {
        val prefs = container.prefs.snapshot()
        val loc = prefs.activeLocationId?.let { container.db.savedLocationDao().byId(it) }
            ?: return Loaded(null)
        val place = Place(loc.id, loc.label, loc.country, loc.admin1, loc.admin2, loc.city, loc.metro, loc.lat, loc.lon, loc.tz)
        Loaded(container.weatherRepository.alertById(place, alertId))
    } catch (e: Exception) {
        Loaded(null)
    }
}

@Composable
private fun AlertDetailContent(container: AppContainer, alert: WeatherAlert, lang: String) {
    val context = LocalContext.current
    val warning = alert.isWarningLevel()
    val headTr = translatedWeatherText(container, lang, alert.headline.ifBlank { alert.event })
    val descTr = translatedWeatherText(container, lang, alert.description)
    val instrTr = if (!alert.instruction.isNullOrBlank()) {
        translatedWeatherText(container, lang, alert.instruction)
    } else null
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            (if (lang != "en" && headTr != alert.headline) headTr else alert.headline.ifBlank { alert.event }),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }.testTag("alert-detail-headline"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                alert.severity.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("alert-detail-severity"),
            )
            if (!alert.sender.isBlank()) {
                Text(alert.sender, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        listOfNotNull(
            alert.effective?.let { "Effective $it" },
            alert.expires?.let { "Expires $it" },
            alert.ends?.let { "Ends $it" },
            alert.areaDesc?.let { "Areas: $it" },
        ).forEach { line ->
            Text(line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Body: translation first (non-English), original English always.
        if (lang != "en" && descTr != alert.description) {
            Text(descTr, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("alert-detail-translated"))
        }
        Text(
            alert.description,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.testTag("alert-detail-description"),
        )
        if (!alert.instruction.isNullOrBlank()) {
            Text("What to do", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (instrTr != null && instrTr != alert.instruction) {
                Text(instrTr, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("alert-detail-instruction-translated"))
            }
            Text(alert.instruction, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("alert-detail-instruction"))
        }
        if (warning) {
            Text(
                "Shown in the original English because machine translation of warnings can be unreliable.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("alert-detail-original-note"),
            )
        }
        TextButton(
            onClick = {
                val intent = CustomTabsIntent.Builder().build()
                intent.launchUrl(context, android.net.Uri.parse(alert.link ?: "https://www.weather.gov/"))
            },
            modifier = Modifier.testTag("alert-detail-link"),
        ) { Text("Open on weather.gov →") }
    }
}
