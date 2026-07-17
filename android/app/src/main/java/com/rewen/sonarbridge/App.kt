package com.rewen.sonarbridge

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Material You: wallpaper-derived palette on API 31+, M3 baseline below
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
