package com.charleshartman.porchlightpress.ui.weather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.charleshartman.porchlightpress.AppContainer
import com.charleshartman.porchlightpress.data.weather.CurrentWeather
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.data.weather.WeatherData
import com.charleshartman.porchlightpress.data.weather.WeatherMath
import com.charleshartman.porchlightpress.data.weather.conditionLabel
import com.charleshartman.porchlightpress.ui.components.AlertBanner

/**
 * Sticky top-bar weather button (owner request): current temperature plus a
 * condition icon, always visible without scrolling. Tapping opens the
 * Weather screen. Rendered only once live data exists (never a placeholder
 * that could be mistaken for real weather).
 */
@Composable
fun WeatherTopButton(
    current: CurrentWeather,
    country: String,
    onOpenWeather: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clickable { onOpenWeather() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("weather-top-button"),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            current.condition.icon,
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            WeatherMath.formatTemp(current.tempC, country),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Front-page weather slot (Phase 7): temp + condition teaser row (tappable
 * to the Weather screen), then a compact card with hi/lo, precip, wind, and
 * a 6-hour strip. Graceful states: stale cache shows "as of", nothing cached
 * shows "Weather unavailable" while the rest of the paper still works.
 */
@Composable
fun FrontWeatherSlot(
    container: AppContainer,
    data: WeatherData?,
    stale: Boolean,
    unavailable: Boolean,
    country: String,
    lang: String,
    clockOverride: String,
    onOpenWeather: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val use24h = is24h(context, clockOverride)
    Column(modifier.fillMaxWidth()) {
        when {
            unavailable || data == null -> {
                Text(
                    "Weather unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("weather-teaser"),
                )
            }
            else -> {
                val conditionEn = data.current.shortText.ifBlank { conditionLabel(data.current.condition) }
                val condition = translatedWeatherText(container, lang, conditionEn)
                val temp = WeatherMath.formatTemp(data.current.tempC, country)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("weather-teaser"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        data.current.condition.icon,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.testTag("weather-icon"),
                    )
                    Text(
                        "$temp · $condition",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("weather-temp"),
                    )
                    if (stale) {
                        Text(
                            asOfLabel(data.fetchedAt, context, use24h),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("weather-asof"),
                        )
                    }
                }
                Card(
                    shape = MaterialTheme.shapes.medium,
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .testTag("weather-card"),
                    onClick = onOpenWeather,
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val today = data.daily.firstOrNull()
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                buildString {
                                    if (today != null) {
                                        append("H ${WeatherMath.formatTemp(today.hiC, country)} / L ${WeatherMath.formatTemp(today.loC, country)}")
                                    }
                                    data.current.precipPct?.let { append(" · $it% precip") }
                                }.ifBlank { condition },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("weather-hilo"),
                            )
                            val wind = WeatherMath.formatWind(data.current.windKph, data.current.windDir, country)
                            if (wind.isNotBlank()) {
                                Text(
                                    wind,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("weather-wind"),
                                )
                            }
                        }
                        val strip = data.hourly.take(6)
                        if (strip.isNotEmpty()) {
                            LazyRow(
                                Modifier.fillMaxWidth().testTag("weather-hour-strip"),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                items(strip, key = { it.timeIso }) { h ->
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            hourlyLabel(h.timeIso, use24h),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(h.condition.icon, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            WeatherMath.formatTemp(h.tempC, country),
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Severe-alert banners for the top of the front page, colored by severity:
 * "⚠ FLOOD WARNING · National Weather Service Albany · Until 11:00 PM ·
 * Tap for details". Tapping opens the alert detail.
 */
@Composable
fun FrontAlertBanners(
    alerts: List<WeatherAlert>,
    clockOverride: String,
    onAlertClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val use24h = is24h(context, clockOverride)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        alerts.forEach { alert ->
            val until = alertUntilText(alert, use24h)
            val desc = listOfNotNull(
                alert.sender.takeIf { it.isNotBlank() },
                until,
                "Tap for details",
            ).joinToString(" · ")
            AlertBanner(
                title = alert.event.uppercase(),
                description = desc,
                severity = alert.severity,
                onClick = { onAlertClick(alert.id) },
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}
