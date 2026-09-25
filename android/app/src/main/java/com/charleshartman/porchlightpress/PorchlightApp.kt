package com.charleshartman.porchlightpress

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.charleshartman.porchlightpress.data.remote.NetworkModule
import com.charleshartman.porchlightpress.work.AlertNotifications

class PorchlightApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Severe-weather channel must exist before any worker can notify.
        AlertNotifications.ensureChannels(this)
    }

    /**
     * Coil singleton for the whole app. Image fetches go through
     * [NetworkModule.imageOkHttp] so Wikimedia sees the same descriptive
     * User-Agent as feed requests instead of 403ing.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { NetworkModule.imageOkHttp(this) }
            .crossfade(true)
            .build()
}
