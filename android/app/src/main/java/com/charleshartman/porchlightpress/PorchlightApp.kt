package com.charleshartman.porchlightpress

import android.app.Application
import com.charleshartman.porchlightpress.work.AlertNotifications

class PorchlightApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Severe-weather channel must exist before any worker can notify.
        AlertNotifications.ensureChannels(this)
    }
}
