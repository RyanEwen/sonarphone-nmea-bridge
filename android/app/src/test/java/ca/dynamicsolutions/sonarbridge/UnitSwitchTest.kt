package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Changing units in Settings must never change what a calibration value means.
 * Before the fix, the fields kept showing the old unit while save() read them in
 * the new one, so leaving Settings twice turned a 3.0 ft keel offset into 3.0 m.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UnitSwitchTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        TestKeys.install()
        RuntimeEnvironment.setQualifiers("en-rUS")
        prefs.edit().clear().apply()
    }

    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup) {
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) }
    } else {
        emptyList()
    }

    private fun MainActivity.showTab(tab: Int) {
        views(window.decorView).filterIsInstance<NavigationBarView>().single().selectedItemId = tab
    }

    /** Click the Units toggle with this label, as a user would. */
    private fun MainActivity.chooseUnit(label: String) {
        views(window.decorView).filterIsInstance<MaterialButton>().single { it.text.toString() == label }.performClick()
    }

    /** Open Settings and leave it again, which commits the drafts. */
    private fun MainActivity.visitSettings() {
        showTab(MainActivity.TAB_SETTINGS)
        showTab(MainActivity.TAB_STATUS)
    }

    private fun MainActivity.hintOf(field: View): String = generateSequence(field.parent) { it.parent }.filterIsInstance<TextInputLayout>().first().hint.toString()

    private fun withSettings(block: (MainActivity) -> Unit) {
        val controller: ActivityController<MainActivity> = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            block(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun feetToMetresKeepsTheKeelOffsetAcrossRepeatedVisits() {
        withSettings { activity ->
            activity.showTab(MainActivity.TAB_SETTINGS)
            activity.settingsTab.keelEdit.setText("3.0")
            activity.showTab(MainActivity.TAB_STATUS)
            assertEquals(0.9144f, prefs.getFloat("keel_offset_m", -1f), 1e-4f)

            activity.showTab(MainActivity.TAB_SETTINGS)
            activity.chooseUnit("Meters")
            assertEquals("0.9", activity.settingsTab.keelEdit.text.toString())
            assertEquals("Keel offset (m, +/-)", activity.hintOf(activity.settingsTab.keelEdit))
            activity.showTab(MainActivity.TAB_STATUS)

            // The reported repro: the second visit used to save 3.0 m.
            activity.visitSettings()
            activity.visitSettings()
            assertEquals("m", prefs.getString("unit_dist", null))
            assertEquals(0.9144f, prefs.getFloat("keel_offset_m", -1f), 1e-4f)
        }
    }

    @Test
    fun metresToFeetKeepsKeelAlarmAndRange() {
        prefs.edit()
            .putString("unit_dist", "m")
            .putFloat("keel_offset_m", 1f)
            .putFloat("alarm_m", 2.5f)
            .putFloat("dev_range_m", 30f)
            .apply()
        withSettings { activity ->
            activity.showTab(MainActivity.TAB_SETTINGS)
            assertEquals("1.0", activity.settingsTab.keelEdit.text.toString())
            activity.chooseUnit("Feet")
            assertEquals("3.3", activity.settingsTab.keelEdit.text.toString())
            assertEquals("8.2", activity.settingsTab.alarmEdit.text.toString())
            assertEquals("Alarm depth (ft, blank = off)", activity.hintOf(activity.settingsTab.alarmEdit))
            activity.showTab(MainActivity.TAB_STATUS)

            activity.visitSettings()
            activity.visitSettings()
            assertEquals(1f, prefs.getFloat("keel_offset_m", -1f), 1e-4f)
            assertEquals(2.5f, prefs.getFloat("alarm_m", -1f), 1e-4f)
            assertEquals(30f, prefs.getFloat("dev_range_m", -1f), 1e-4f)
        }
    }

    @Test
    fun switchingBackAndForthDoesNotAccumulateRounding() {
        prefs.edit().putString("unit_dist", "m").putFloat("keel_offset_m", 1.234f).apply()
        withSettings { activity ->
            activity.showTab(MainActivity.TAB_SETTINGS)
            repeat(3) {
                activity.chooseUnit("Feet")
                activity.chooseUnit("Meters")
            }
            activity.showTab(MainActivity.TAB_STATUS)
            assertEquals(1.234f, prefs.getFloat("keel_offset_m", -1f), 1e-4f)
        }
    }

    @Test
    fun aValueTypedBeforeSwitchingIsReadInTheUnitItWasTypedIn() {
        withSettings { activity ->
            activity.showTab(MainActivity.TAB_SETTINGS)
            activity.settingsTab.alarmEdit.setText("10")
            activity.chooseUnit("Meters")
            assertEquals("3.0", activity.settingsTab.alarmEdit.text.toString())
            activity.showTab(MainActivity.TAB_STATUS)
            assertEquals(3.048f, prefs.getFloat("alarm_m", -1f), 1e-4f)
        }
    }

    @Test
    fun temperatureOffsetSurvivesCelsiusToFahrenheitAndBack() {
        prefs.edit().putFloat("temp_offset_c", 1.5f).apply()
        withSettings { activity ->
            activity.showTab(MainActivity.TAB_SETTINGS)
            activity.chooseUnit("Fahrenheit")
            activity.showTab(MainActivity.TAB_STATUS)
            activity.visitSettings()
            assertEquals(1.5f, prefs.getFloat("temp_offset_c", -1f), 1e-4f)

            activity.showTab(MainActivity.TAB_SETTINGS)
            activity.chooseUnit("Celsius")
            activity.showTab(MainActivity.TAB_STATUS)
            activity.visitSettings()
            assertEquals(1.5f, prefs.getFloat("temp_offset_c", -1f), 1e-4f)
        }
    }
}
