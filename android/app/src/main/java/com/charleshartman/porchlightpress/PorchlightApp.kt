package com.charleshartman.porchlightpress

import android.app.Application

class PorchlightApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
