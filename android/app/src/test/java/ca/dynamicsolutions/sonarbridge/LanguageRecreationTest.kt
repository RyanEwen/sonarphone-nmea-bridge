package ca.dynamicsolutions.sonarbridge

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.textfield.TextInputEditText
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.Date

/** Drive the real Material language picker, activity recreation, view drafts and persisted config. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 32, 33])
class LanguageRecreationTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        RuntimeEnvironment.setQualifiers("en-rUS")
        prefs.edit().clear().putFloat("keel_offset_m", 2f).putFloat("alarm_m", 4f)
            .putString("pass", "saved-password").apply()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
        if (Build.VERSION.SDK_INT >= 33) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        }
        BridgeState.update { BridgeState.Snapshot(running = true, phase = "RUN", frameCount = 24) }
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else emptyList()

    /** The selected radio item invokes the actual language-change listener. */
    private fun select(activity: MainActivity, index: Int) {
        val root = activity.window.decorView
        val label = AppLanguage.choices[AppLanguage.selectedTag(activity)] ?: activity.getString(R.string.follow_device)
        views(root).filterIsInstance<TextView>().first { it.text.toString() == label }.performClick()
        val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val list = dialog.listView
        list.performItemClick(list.adapter.getView(index, null, list), index, index.toLong())
    }

    private fun field(activity: MainActivity, name: String): TextInputEditText =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as TextInputEditText

    @Test
    fun actualLanguageChangeAndClearPreserveDraftsTabAndSavedConfiguration() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            var activity = controller.get()
            val navigation = views(activity.window.decorView).filterIsInstance<NavigationBarView>().single()
            navigation.selectedItemId = MainActivity.TAB_SETTINGS
            field(activity, "keelEdit").setText("-")
            field(activity, "alarmEdit").setText("1.")
            field(activity, "passEdit").setText("unfinished-password")
            val snapshot = prefs.all.toMap()
            val running = BridgeState.flow.value

            select(activity, 4) // fr-FR, following the documented native-label ordering
            // AppCompat asks the framework to recreate; Robolectric needs an explicit lifecycle drive.
            controller.recreate()
            activity = controller.get()
            assertEquals("fr-FR", AppLanguage.selectedTag(activity))
            assertEquals("Paramètres", activity.getString(R.string.nav_settings))
            assertEquals(MainActivity.TAB_SETTINGS, views(activity.window.decorView).filterIsInstance<NavigationBarView>().single().selectedItemId)
            assertEquals("-", field(activity, "keelEdit").text.toString())
            assertEquals("1.", field(activity, "alarmEdit").text.toString())
            assertEquals("unfinished-password", field(activity, "passEdit").text.toString())
            assertEquals(snapshot, prefs.all.filterKeys { it != AppLanguage.PREFERENCE })
            assertSame(running, BridgeState.flow.value)
            assertNull(shadowOf(app).nextStartedService)

            select(activity, 0) // Follow device
            controller.recreate()
            activity = controller.get()
            assertNull(AppLanguage.selectedTag(activity))
            assertEquals("Settings", activity.getString(R.string.nav_settings))
            assertEquals("-", field(activity, "keelEdit").text.toString())
            assertEquals(snapshot, prefs.all)
            assertSame(running, BridgeState.flow.value)

            // Choosing the same setting must not suppress the next ordinary save.
            select(activity, 0)
            field(activity, "passEdit").setText("ordinary-save")
            controller.pause()
            assertEquals("ordinary-save", prefs.getString("pass", null))
        } finally {
            controller.stop().destroy()
        }
    }

    @Test
    fun matchingDeviceLanguageUpdatesChoiceAndStillSavesWithoutRecreation() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val navigation = views(activity.window.decorView).filterIsInstance<NavigationBarView>().single()
            navigation.selectedItemId = MainActivity.TAB_SETTINGS

            // The effective locale stays en-US in both directions. Do not force recreation:
            // this is the path the previous unconditional controller.recreate() tests missed.
            for ((index, expectedTag, label) in listOf(
                Triple(1, "en-US", AppLanguage.choices.getValue("en-US")),
                Triple(0, null, activity.getString(R.string.follow_device)),
            )) {
                assertFalse(AppLanguage.changesConfiguration(activity, expectedTag))
                select(activity, index)
                // ShadowActivity posts recreation onto the main looper. Drain it and verify
                // that the controller still owns this exact Activity instance.
                shadowOf(Looper.getMainLooper()).idle()
                assertSame(activity, controller.get())
                assertEquals(expectedTag, AppLanguage.selectedTag(activity))
                assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any {
                    it.text.toString() == label
                })

                field(activity, "passEdit").setText("saved-with-$index")
                controller.pause()
                assertEquals("saved-with-$index", prefs.getString("pass", null))
                controller.resume()
            }
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun regionalTimeAndReadOnlyNumbersIgnoreUiLanguage() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "12")
            val before = RegionalFormat.timeFormatter(activity).format(Date(0))
            val region = AppLanguage.regionalLocale(activity)
            AppLanguage.select(activity, "fr-FR")
            controller.recreate()
            assertEquals(region, AppLanguage.regionalLocale(controller.get()))
            assertEquals(before, RegionalFormat.timeFormatter(controller.get()).format(Date(0)))
            assertNotEquals(
                RegionalFormat.timeFormatter(controller.get()).format(Date(0)),
                RegionalFormat.timeFormatter(controller.get()).format(Date(1000)),
            )
            Settings.System.putString(app.contentResolver, Settings.System.TIME_12_24, "24")
            val format = RegionalFormat.timeFormatter(controller.get()) as java.text.SimpleDateFormat
            assertFalse(format.toPattern().contains("a"))
            assertNotEquals(format.format(Date(0)), format.format(Date(1000)))
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
