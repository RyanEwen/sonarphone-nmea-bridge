package ca.dynamicsolutions.sonarbridge

import android.app.Activity
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.google.android.material.navigation.NavigationBarView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Settings leads to the About screen, which follows the app's language, links out, and shows the notices. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 33])
class AboutActivityTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().clear().commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    /** Start [type], run [check] on it, and always destroy it so no window outlives the test. */
    private fun <T : Activity> with(type: Class<T>, check: (T) -> Unit) {
        val controller = Robolectric.buildActivity(type).setup()
        try {
            check(controller.get())
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun all(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()

    private fun texts(view: View): List<String> = all(view).filterIsInstance<TextView>().map { it.text.toString() }

    private fun row(activity: Activity, label: String): View = all(activity.window.decorView).filterIsInstance<TextView>().single { it.text.toString() == label && it.isClickable }

    @Test
    fun settingsEndsWithAnAboutRowThatOpensTheAboutScreen() = with(MainActivity::class.java) { activity ->
        all(activity.window.decorView).filterIsInstance<NavigationBarView>().single().selectedItemId = MainActivity.TAB_SETTINGS
        assertTrue(texts(activity.window.decorView).contains("About"))
        row(activity, "Version ${BuildConfig.VERSION_NAME}").performClick()
        assertEquals(AboutActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test
    fun aStoredNonEnglishLanguageIsUsedEvenBeforeApi33() {
        // On API 29 the choice comes from the app's own preference, applied in attachBaseContext.
        app.getSharedPreferences("cfg", Context.MODE_PRIVATE).edit().putString(AppLanguage.PREFERENCE, "fr-FR").commit()
        AppLanguage.initialize(app)
        try {
            with(AboutActivity::class.java) { activity ->
                val shown = texts(activity.window.decorView)
                assertTrue(shown.contains("Liens"))
                assertTrue(shown.contains("Code source"))
                assertTrue(shown.contains("Politique de confidentialité"))
                assertTrue(shown.contains("Version ${BuildConfig.VERSION_NAME}"))
            }
        } finally {
            AppLanguage.select(app, null)
        }
    }

    @Test
    fun linksOpenTheirAddresses() = with(AboutActivity::class.java) { activity ->
        for ((label, url) in listOf(
            "Source code" to AppLinks.REPOSITORY,
            "Privacy policy" to AppLinks.PRIVACY_POLICY,
            "Support" to AppLinks.ISSUES,
            "License" to AppLinks.LICENSE,
        )) {
            row(activity, label).performClick()
            assertEquals(url, shadowOf(activity).nextStartedActivity.dataString)
        }
    }

    @Test
    fun thirdPartyNoticesShowTheGeneratedFile() = with(AboutActivity::class.java) { activity ->
        row(activity, "Third-party notices").performClick()
        val shown = texts(ShadowDialog.getLatestDialog().window!!.decorView).joinToString("\n")
        assertTrue(shown.contains("androidx.appcompat:appcompat:"))
        assertTrue(shown.contains("License: "))
    }
}
