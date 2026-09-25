package com.charleshartman.porchlightpress.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.charleshartman.porchlightpress.data.weather.WeatherAlert
import com.charleshartman.porchlightpress.work.AlertNotifications

/**
 * Debug-only (never in release): posts a canned Tornado Warning through the
 * real [AlertNotifications] path so the on-device pass can verify a
 * severe-alert notification end to end without waiting for live NWS weather.
 *
 * Trigger: adb -s 37220DLJG001ML shell am broadcast -a
 * com.charleshartman.porchlightpress.debug.TEST_ALERT
 */
class TestAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TEST_ALERT) return
        AlertNotifications.showWarning(
            context,
            WeatherAlert(
                id = "test-tornado-warning-schenectady",
                event = "Tornado Warning",
                headline = "Tornado Warning issued for Schenectady County until 9:45 PM (TEST — no action needed)",
                description = "TEST notification from Porchlight Press debug build. " +
                    "This exercises the severe-weather notification path with a fixture alert. " +
                    "No action needed.",
                instruction = "This is a test. No protective action is needed.",
                severity = "Severe",
                sender = "National Weather Service Albany",
                areaDesc = "Schenectady County",
                link = "https://www.weather.gov/",
            ),
        )
    }

    companion object {
        const val ACTION_TEST_ALERT =
            "com.charleshartman.porchlightpress.debug.TEST_ALERT"
    }
}
