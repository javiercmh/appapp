package com.wynh.appapp.ai

import android.app.Application

class AppAiApplication : Application() {
    lateinit var ai: AppAiManager
        private set

    override fun onCreate() {
        super.onCreate()
        ai = AppAiManager(this)
    }
}
