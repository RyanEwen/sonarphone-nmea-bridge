package ca.dynamicsolutions.sonarbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The single screen: navigation between the Status, Sonar and Settings tabs, each
 * built in its own class, plus the screen-wide lifecycle (keep-awake, notification
 * permission, saving Settings drafts when leaving the tab).
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val TAB_STATUS = 1
        const val TAB_SONAR = 2
        const val TAB_SETTINGS = 3

        // remembered across rotation (process survives config change)
        private var lastTab = TAB_STATUS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var content: FrameLayout
    private var currentTab = -1

    private lateinit var statusTab: StatusTab
    private lateinit var sonarView: SonarView
    internal lateinit var settingsTab: SettingsTab

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.context(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // on-the-water use: never dim/lock while the app is in front
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // A recreated screen must not compete with a pending system permission dialog.
        if (Build.VERSION.SDK_INT >= 33 && savedInstanceState == null &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Units.regionalLocale = AppLanguage.regionalLocale(this)

        val settings = AppSettings(this)
        statusTab = StatusTab(this, settings)
        sonarView = SonarView(this)
        settingsTab = SettingsTab(this, settings)
        settingsTab.restoreDraft(savedInstanceState?.getBundle("settings_draft"))

        content = FrameLayout(this)

        // Portrait: tabs across the bottom. Landscape (phone or tablet): a
        // vertical nav rail on the left, so the wide-but-short screen keeps its
        // full height for the sonar view (the rail sits left of the zoom
        // button). Rotation recreates the activity, so this re-picks the layout.
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        fun buildMenu(nav: NavigationBarView) {
            nav.menu.add(Menu.NONE, TAB_STATUS, 0, getString(R.string.nav_status)).setIcon(R.drawable.ic_tab_status)
            nav.menu.add(Menu.NONE, TAB_SONAR, 1, getString(R.string.nav_sonar)).setIcon(R.drawable.ic_tab_sonar)
            nav.menu.add(Menu.NONE, TAB_SETTINGS, 2, getString(R.string.nav_settings)).setIcon(R.drawable.ic_tab_settings)
            nav.setOnItemSelectedListener { item ->
                show(item.itemId)
                true
            }
        }

        val root: LinearLayout
        val navBar: NavigationBarView
        if (landscape) {
            val rail = NavigationRailView(this)
            buildMenu(rail)
            content.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f,
            )
            root = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(
                    rail,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                addView(content)
            }
            navBar = rail
        } else {
            val bottom = BottomNavigationView(this)
            buildMenu(bottom)
            content.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(content)
                addView(bottom)
            }
            navBar = bottom
        }
        // targetSdk 35 enforces edge-to-edge (no automatic fitting), so pad the
        // whole UI in from the system bars + display cutout — otherwise the
        // content draws under the clock/notifications and the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        root.isSaveFromParentEnabled = false
        setContentView(root)
        navBar.selectedItemId = savedInstanceState?.getInt("current_tab", lastTab) ?: lastTab

        scope.launch {
            BridgeState.flow.collect { statusTab.render(it) }
        }
    }

    /** Swap the content to [tab], committing Settings drafts when leaving that tab. */
    private fun show(tab: Int) {
        if (tab == currentTab) return
        if (currentTab == TAB_SETTINGS) settingsTab.save()
        currentTab = tab
        lastTab = tab
        content.removeAllViews()
        content.addView(
            when (tab) {
                TAB_SONAR -> sonarView
                TAB_SETTINGS -> settingsTab.view
                else -> statusTab.view
            },
        )
        if (tab == TAB_STATUS) statusTab.render(BridgeState.flow.value)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("current_tab", currentTab)
        outState.putBundle("settings_draft", settingsTab.saveDraft())
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        statusTab.refreshBatteryButton()
        statusTab.render(BridgeState.flow.value)
    }

    override fun onPause() {
        if (currentTab == TAB_SETTINGS && !settingsTab.changingLanguage && !isChangingConfigurations) settingsTab.save()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
