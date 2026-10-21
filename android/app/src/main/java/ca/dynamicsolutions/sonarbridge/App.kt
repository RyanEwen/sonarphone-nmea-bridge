package ca.dynamicsolutions.sonarbridge

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.initialize(this)
        Units.regionalLocale = AppLanguage.regionalLocale(this)
        // Material You: wallpaper-derived palette on API 31+, M3 baseline below
        DynamicColors.applyToActivitiesIfAvailable(this)
        Units.load(getSharedPreferences("cfg", MODE_PRIVATE))
    }
}
