package com.vadila.ai

import android.app.Application

class AiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Media.init(this)
    }
}
