package com.vadila.downloader

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        Dub.init(this)
    }
}
