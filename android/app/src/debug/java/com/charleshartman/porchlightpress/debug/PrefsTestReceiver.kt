package com.charleshartman.porchlightpress.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.charleshartman.porchlightpress.PorchlightApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only (never in release): flips notification toggles on so the
 * on-device pass can verify worker scheduling without depending on
 * pixel-perfect onboarding taps. Mirrors what Settings → Notifications will
 * do in Phase 8 (same prefs keys, same scheduler path via MainActivity).
 *
 * Trigger: adb -s 37220DLJG001ML shell am broadcast -a
 * com.charleshartman.porchlightpress.debug.SET_NOTIFY -e severe true -e breaking true -e editions true
 */
class PrefsTestReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SET_NOTIFY) return
        val app = context.applicationContext as? PorchlightApp ?: return
        // Accept both boolean extras (-ez) and string extras (-e "true").
        fun flag(name: String, default: Boolean): Boolean {
            return when (val raw = intent.extras?.get(name)) {
                null -> default
                is Boolean -> raw
                is String -> raw.equals("true", ignoreCase = true)
                else -> default
            }
        }
        val severe = flag("severe", true)
        val breaking = flag("breaking", false)
        val editions = flag("editions", false)
        // goAsync keeps the broadcast alive until the DataStore write lands.
        val pending = goAsync()
        scope.launch {
            try {
                app.container.prefs.setNotifySevere(severe)
                app.container.prefs.setNotifyBreaking(breaking)
                app.container.prefs.setNotifyEditions(editions)
                android.util.Log.i(
                    "Porchlight",
                    "PrefsTestReceiver set severe=$severe breaking=$breaking editions=$editions",
                )
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SET_NOTIFY =
            "com.charleshartman.porchlightpress.debug.SET_NOTIFY"
    }
}
