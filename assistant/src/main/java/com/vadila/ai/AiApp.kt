package com.vadila.ai

import android.app.Application

class AiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        Media.init(this)
        OnDevice.init(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // free the offline model's memory when the app is in the background and the phone needs it
        if (level >= TRIM_MEMORY_BACKGROUND) OnDevice.release()
    }
}
