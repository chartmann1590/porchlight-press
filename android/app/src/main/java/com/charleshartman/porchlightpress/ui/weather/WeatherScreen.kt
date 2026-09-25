package com.charleshartman.porchlightpress.ui.weather

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.data.weather.WeatherData
import com.charleshartman.porchlightpress.data.weather.WeatherMath
import com.charleshartman.porchlightpress.data.weather.conditionLabel
import com.charleshartman.porchlightpress.data.weather.isWarningLevel
import com.charleshartman.porchlightpress.data.weather.weatherAttribution
import com.charleshartman.porchlightpress.ui.components.AlertBanner
import com.charleshartman.porchlightpress.ui.components.TranslationLabel

/**
 * Weather screen (Screen 10): current, feels-like, hourly (24 h), daily
 * (7 d), alerts list, provider attribution. No ads on this screen — alerts
 * and forecasts stay ad-free. Condition/forecast/alert text goes through
 * ML Kit when appLanguage != en; Warning-level alerts always show the
 * original English inline under the translation.
 */
@Composable
fun WeatherScreen(
    container: AppContainer,
    viewModel: WeatherViewModel,
    onBack: () -> Unit,
    onAlertClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val prefs by container.prefs.prefs.collectAsState(initial = null)
    val clock = prefs?.clockFormat ?: "system"
    Column(modifier.fillMaxSize().testTag("weather-screen")) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("weather-back")) { Text("‹ Back") }
            Text(
                "Weather",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }.testTag("weather-title"),
            )
        }
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag("weather-loading"))
            }
            state.unavailable || state.data == null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Weather unavailable. Check your connection — your news is still below.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("weather-unavailable"),
                )
            }
            else -> {
                val wxData = state.data
                if (wxData == null) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Weather unavailable.", modifier = Modifier.testTag("weather-unavailable"))
                    }
                } else {
                    WeatherContent(
                        container = container,
                        data = wxData,
                        stale = state.stale,
                        lang = state.lang,
                        country = state.place?.country ?: "US",
                        placeLabel = state.place?.label,
                        clockOverride = clock,
                        onAlertClick = onAlertClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun WeatherContent(
    container: AppContainer,
    data: WeatherData,
    stale: Boolean,
    lang: String,
    country: String,
    placeLabel: String?,
    clockOverride: String,
    onAlertClick: (String) -> Unit,
) {
    val context = LocalContext.current
    val use24h = is24h(context, clockOverride)
    val conditionEn = data.current.shortText.ifBlank { conditionLabel(data.current.condition) }
    val condition = translatedWeatherText(container, lang, conditionEn)
    LazyColumn(
        Modifier.fillMaxSize().testTag("weather-list"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        if (placeLabel != null) {
            item(key = "place") {
                Text(
                    placeLabel.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() }.testTag("weather-place"),
                )
            }
        }
        item(key = "current") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("weather-current")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(data.current.condition.icon, style = MaterialTheme.typography.displaySmall)
                    Text(
                        WeatherMath.formatTemp(data.current.tempC, country),
                        style = MaterialTheme.typography.displayMedium,
                        modifier = Modifier.testTag("weather-current-temp"),
                    )
                }
                Text(condition, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("weather-current-condition"))
                Text(
                    "Feels like ${WeatherMath.formatTemp(data.current.feelsLikeC, country)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("weather-feels-like"),
                )
                val today = data.daily.firstOrNull()
                if (today != null) {
                    Text(
                        "H ${WeatherMath.formatTemp(today.hiC, country)} · L ${WeatherMath.formatTemp(today.loC, country)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("weather-hilo"),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    data.current.precipPct?.let {
                        Text("$it% precip", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("weather-precip"))
                    }
                    val wind = WeatherMath.formatWind(data.current.windKph, data.current.windDir, country)
                    if (wind.isNotBlank()) {
                        Text(wind, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("weather-wind"))
                    }
                    data.current.humidityPct?.let {
                        Text("$it% humidity", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("weather-humidity"))
                    }
                }
                if (stale) {
                    Text(
                        "Cached ${asOfLabel(data.fetchedAt, context, use24h)} — provider unreachable.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("weather-asof"),
                    )
                }
            }
        }
        if (data.hourly.isNotEmpty()) {
            item(key = "hourly-h") {
                Text("HOURLY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
            }
            item(key = "hourly") {
                LazyRow(
                    Modifier.fillMaxWidth().testTag("weather-hourly"),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(data.hourly.take(24), key = { it.timeIso }) { h ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(hourlyLabel(h.timeIso, use24h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(h.condition.icon, style = MaterialTheme.typography.titleMedium)
                            Text(WeatherMath.formatTemp(h.tempC, country), style = MaterialTheme.typography.bodyMedium)
                            h.precipPct?.let {
                                Text("$it%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
        if (data.daily.isNotEmpty()) {
            item(key = "daily-h") {
                Text("7-DAY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() }.testTag("weather-daily-header"))
            }
            items(data.daily.take(7), key = { it.dateIso }) { d ->
                val (dow, md) = dailyLabels(d.dateIso)
                val shortEn = d.shortText.ifBlank { conditionLabel(d.condition) }
                val short = translatedWeatherText(container, lang, shortEn)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("weather-day-${d.dateIso}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("$dow $md", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(d.condition.icon, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${WeatherMath.formatTemp(d.hiC, country)} / ${WeatherMath.formatTemp(d.loC, country)}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (short.isNotBlank()) {
                    Text(short, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                d.precipPct?.let {
                    Text("$it% precip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item(key = "alerts-h") {
            Text("ALERTS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() }.testTag("weather-alerts-header"))
        }
        if (data.alerts.isEmpty()) {
            item(key = "alerts-empty") {
                Text(
                    if (data.alertsUnavailable) "Alerts unavailable for this area right now."
                    else "No active alerts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("weather-alerts-empty"),
                )
            }
        } else {
            items(data.alerts, key = { it.id }) { alert ->
                AlertRow(container = container, alert = alert, lang = lang, use24h = use24h, onClick = { onAlertClick(alert.id) })
            }
        }
        item(key = "attr") {
            Text(
                weatherAttribution(data.provider),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp).testTag("weather-attribution"),
            )
        }
    }
}

@Composable
private fun AlertRow(
    container: AppContainer,
    alert: WeatherAlert,
    lang: String,
    use24h: Boolean,
    onClick: () -> Unit,
) {
    val eventTr = translatedWeatherText(container, lang, alert.event)
    val headTr = translatedWeatherText(container, lang, alert.headline.ifBlank { alert.event })
    val warning = alert.isWarningLevel()
    val until = alertUntilText(alert, use24h)
    val desc = listOfNotNull(
        alert.sender.takeIf { it.isNotBlank() },
        until,
    ).joinToString(" · ")
    // Single clickable (on the banner itself): an extra clickable wrapper
    // would merge the banner's semantics away and hide its test tag.
    Column(Modifier.fillMaxWidth()) {
        AlertBanner(
            title = (if (lang != "en" && eventTr != alert.event) eventTr else alert.event).uppercase(),
            description = listOfNotNull(desc.ifBlank { null }, "Tap for details").joinToString(" · "),
            severity = alert.severity,
            onClick = onClick,
        )
        // Translation: warning-level alerts always show the original English
        // inline (a mistranslation there could be dangerous); others get the
        // standard translated label with a Show-original toggle.
        if (lang != "en") {
            if (warning) {
                if (headTr != alert.headline) {
                    Text(headTr, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp).testTag("alert-translated"))
                }
                Text(
                    "Original: ${alert.headline.ifBlank { alert.event }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testTag("alert-original-inline"),
                )
            } else if (headTr != alert.headline) {
                TranslationLabelState(container, lang, alert)
            }
        }
    }
}

@Composable
private fun TranslationLabelState(container: AppContainer, lang: String, alert: WeatherAlert) {
    // Non-warning alert with a translation: show translated headline inline
    // with the original one tap away (same pattern as stories).
    var showOriginal by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val headTr = translatedWeatherText(container, lang, alert.headline.ifBlank { alert.event })
    Column {
        TranslationLabel(isTranslated = true, onToggle = { showOriginal = !showOriginal })
        Text(
            if (showOriginal) alert.headline.ifBlank { alert.event } else headTr,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp).testTag("alert-translated"),
        )
    }
}
