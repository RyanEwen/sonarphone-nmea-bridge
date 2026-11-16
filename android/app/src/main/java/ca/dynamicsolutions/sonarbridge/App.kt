package ca.dynamicsolutions.sonarbridge

import android.app.Application
import com.google.android.material.color.DynamicColors

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before anything reads it: older versions kept the passphrase in "cfg".
        SonarPassphrase.migrate(this)
        AppLanguage.initialize(this)
        Units.regionalLocale = AppLanguage.regionalLocale(this)
        // Material You: wallpaper-derived palette on API 31+, M3 baseline below
        DynamicColors.applyToActivitiesIfAvailable(this)
        Units.load(AppSettings(this).read())
    }
}
