package ca.dynamicsolutions.sonarbridge

import android.app.LocaleManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Check compiled Android resources and actual Service contexts on every supported API generation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32, 33])
class AppLanguageTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        prefs.edit().clear().apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        BridgeState.update { BridgeState.Snapshot() }
        RuntimeEnvironment.setQualifiers("en-rUS")
    }

    @Test
    fun aliasAndScriptResolutionPreservePreferenceOrder() {
        val cases = mapOf(
            "EN_ca" to "en-CA", "fr-Latn-CA" to "fr-CA", "es-MX" to "es-ES",
            "pt-PT" to "pt-BR", "zh-Hans-TW" to "zh-CN", "zh-SG" to "zh-CN",
            "zh-Hant" to null, "zh-TW" to null, "zh-HK" to null, "zh-MO" to null,
            "zh-Latn-CN" to null, "de-Cyrl" to null, "???" to null, "[fr]" to null,
            "" to null, "xx-YY" to null,
        )
        cases.forEach { (input, expected) -> assertEquals(input, expected, AppLanguage.canonicalTag(input)) }
        assertEquals("es-ES", AppLanguage.resolve(listOf("zh-Hant", "es-MX", "fr-CA")))
        assertEquals("en-US", AppLanguage.resolve(listOf("xx-YY", "zh-Hant")))
    }

    @Test
    fun nativeResourcesRespectOrderedLanguagesAndChineseScript() {
        // Bypass our resolver: exercise Android's resource matcher with an actual LocaleList.
        fun wording(tags: String): String {
            val config = Configuration(app.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tags))
            return app.createConfigurationContext(config).getString(R.string.nav_settings)
        }
        assertEquals("Settings", wording("xx-YY,en-US,es-ES"))
        assertEquals("Paramètres", wording("xx-YY,fr-CA,en-US"))
        assertEquals("Ajustes", wording("zh-Hant-TW,es-ES,en-US"))
        assertEquals("Settings", wording("zh-Hant-HK,en-US"))
        assertEquals("设置", wording("zh-Hans-TW,en-US"))
        assertEquals("设置", wording("zh-SG,en-US"))
        assertEquals("Settings", wording("xx-YY"))
    }

    @Test
    fun malformedStoredValuesDoNotRejectSiblingSettings() {
        for (bad in listOf<Any>("???", "zh-Hant", "xx-YY", 123, true, setOf("fr"))) {
            val edit = prefs.edit().putString("pass", "retained")
            when (bad) {
                is String -> edit.putString(AppLanguage.PREFERENCE, bad)
                is Int -> edit.putInt(AppLanguage.PREFERENCE, bad)
                is Boolean -> edit.putBoolean(AppLanguage.PREFERENCE, bad)
                else -> edit.putStringSet(AppLanguage.PREFERENCE, setOf("fr"))
            }
            edit.apply()
            AppLanguage.initialize(app)
            assertNull(AppLanguage.selectedTag(app))
            assertFalse(prefs.contains(AppLanguage.PREFERENCE))
            assertEquals("retained", prefs.getString("pass", null))
        }
    }

    @Test
    fun restoreThenClearStaysAutomaticOnNextInitialization() {
        prefs.edit().putString(AppLanguage.PREFERENCE, "fr_CA").apply()
        AppLanguage.initialize(app)
        assertEquals("fr-CA", AppLanguage.selectedTag(app))
        assertEquals("Paramètres", AppLanguage.context(app).getString(R.string.nav_settings))
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        } else {
            AppLanguage.select(app, null)
        }
        AppLanguage.initialize(app)
        assertNull(AppLanguage.selectedTag(app))
        assertFalse(prefs.contains(AppLanguage.PREFERENCE))
        assertEquals("Settings", AppLanguage.context(app).getString(R.string.nav_settings))
    }

    @Test
    @Config(sdk = [33])
    fun migrationKeepsExistingPlatformChoiceAndNeverReappliesAfterClear() {
        val manager = app.getSystemService(LocaleManager::class.java)
        manager.applicationLocales = LocaleList.forLanguageTags("de-DE")
        prefs.edit().putString(AppLanguage.PREFERENCE, "fr-FR").apply()
        AppLanguage.initialize(app)
        assertEquals("de-DE", manager.applicationLocales.toLanguageTags())
        assertFalse(prefs.contains(AppLanguage.PREFERENCE))
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLanguage.initialize(app)
        assertTrue(manager.applicationLocales.isEmpty)
    }

    @Test
    fun coldServiceUsesSavedLanguageAndRefreshPreservesChannelAndRunningIdentity() {
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("fr-FR")
        } else {
            prefs.edit().putString(AppLanguage.PREFERENCE, "fr-FR").apply()
        }
        // No Activity has been constructed, and no sockets or onStartCommand are involved.
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("bridge", "old", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "user description"
            setShowBadge(false)
        })
        val controller = Robolectric.buildService(BridgeService::class.java).create()
        try {
            assertEquals("Pont", manager.getNotificationChannel("bridge").name.toString())
            val running = BridgeState.Snapshot(running = true, phase = "RUN", depthM = 3.0, frameCount = 17)
            BridgeState.update { running }
            if (Build.VERSION.SDK_INT >= 33) {
                app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags("es-ES")
                controller.get().onConfigurationChanged(Configuration(app.resources.configuration))
            } else {
                prefs.edit().putString(AppLanguage.PREFERENCE, "es-ES").commit()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            val channel = manager.getNotificationChannel("bridge")
            assertEquals("Puente", channel.name.toString())
            assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
            assertEquals("user description", channel.description)
            assertFalse(channel.canShowBadge())
            assertSame(running, BridgeState.flow.value)
            val notification = manager.activeNotifications.single()
            assertEquals(1, notification.id)
            assertEquals("Puente SP200A", notification.notification.extras.getString(Notification.EXTRA_TITLE))
            assertTrue(notification.notification.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("Conectado"))
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun regionalDisplayDoesNotChangeNmeaOrNumericEntryContract() {
        Units.regionalLocale = java.util.Locale.GERMANY
        Units.feet = false
        assertEquals("1,50 m", Units.depth(1.5))
        val nmea = Nmea.forDepthTemp(1.5, 20.5)
        assertTrue(nmea.contains("1.5"))
        assertFalse(nmea.contains("1,50"))
        assertEquals(1.5, "1.5".toDoubleOrNull()!!, 0.0)
    }
}
