package com.charleshartman.porchlightpress.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.lifecycleScope
import com.charleshartman.porchlightpress.PorchlightApp
import kotlinx.coroutines.launch

/**
 * Debug-only (never in release): sets notification toggles deterministically
 * for the on-device pass and shows the result on screen, so the outcome is
 * verifiable by screenshot (no logcat dependence).
 *
 * Launch: adb -s 37220DLJG001ML shell am start -n
 * com.charleshartman.porchlightpress/.debug.PrefsDebugActivity --ez severe true --ez breaking true --ez editions true
 */
class PrefsDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val severe = intent.getBooleanExtra("severe", true)
        val breaking = intent.getBooleanExtra("breaking", false)
        val editions = intent.getBooleanExtra("editions", false)
        val onboard = intent.getBooleanExtra("onboard", false)
        val testAlert = intent.getBooleanExtra("testAlert", false)
        val done = mutableStateOf("working…")
        lifecycleScope.launch {
            try {
                val container = (application as PorchlightApp).container
                container.prefs.setNotifySevere(severe)
                container.prefs.setNotifyBreaking(breaking)
                container.prefs.setNotifyEditions(editions)
                if (onboard) {
                    // Deterministic onboarding seed for the on-device pass:
                    // Schenectady home place (US → NWS weather) + done flags.
                    // Coordinates included (GPS-path shape) so weather does
                    // not depend on the Geocoder backend during the pass.
                    // Same shape the UI tests seed; the edition itself syncs
                    // live from the feed on next launch.
                    container.db.savedLocationDao().upsert(
                        com.charleshartman.porchlightpress.data.local.SavedLocation(
                            id = "place:us:schenectady",
                            label = "Schenectady, NY",
                            country = "US",
                            admin1 = "US-NY",
                            admin2 = "Schenectady County",
                            city = "Schenectady",
                            metro = "us-ny-capital-region",
                            lat = 42.81,
                            lon = -73.93,
                            tz = "America/New_York",
                            isHome = true,
                            sortOrder = 0,
                        ),
                    )
                    container.prefs.setActiveLocationId("place:us:schenectady")
                    container.prefs.setAppLanguage("en")
                    container.prefs.setOnboardingDone(true)
                    container.prefs.setReadingStarted(true)
                }
                val snap = container.prefs.snapshot()
                if (testAlert) {
                    com.charleshartman.porchlightpress.work.AlertNotifications.showWarning(
                        this@PrefsDebugActivity,
                        com.charleshartman.porchlightpress.data.weather.WeatherAlert(
                            id = "test-tornado-warning-schenectady",
                            event = "Tornado Warning",
                            headline = "Tornado Warning issued for Schenectady County until 9:45 PM (TEST — no action needed)",
                            description = "TEST notification from the Porchlight Press debug build.",
                            severity = "Severe",
                            sender = "National Weather Service Albany",
                            areaDesc = "Schenectady County",
                            link = "https://www.weather.gov/",
                        ),
                    )
                }
                done.value =
                    "severe=${snap.notifySevere} breaking=${snap.notifyBreaking} editions=${snap.notifyEditions}"
                android.util.Log.i("Porchlight", "PrefsDebugActivity applied ${done.value}")
            } catch (e: Exception) {
                done.value = "FAILED: ${e.message}"
            }
        }
        setContent {
            val text = remember { done }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("PREFS: ${text.value}", modifier = Modifier.testTag("prefs-debug-result"))
            }
        }
    }
}
