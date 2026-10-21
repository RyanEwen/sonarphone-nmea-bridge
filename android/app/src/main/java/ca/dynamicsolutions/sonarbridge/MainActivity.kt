package ca.dynamicsolutions.sonarbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale
import com.google.android.material.R as MR

class MainActivity : AppCompatActivity() {

    companion object {
        // radio order; null = specific SSID (must line up with BridgeService)
        val MODE_PATTERNS = listOf("SonarPhone_", "T-BOX-", null)
        const val TAB_STATUS = 1
        const val TAB_SONAR = 2
        const val TAB_SETTINGS = 3
        // remembered across rotation (process survives config change)
        private var lastTab = TAB_STATUS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: SharedPreferences by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private lateinit var content: FrameLayout
    private var currentTab = -1
    private var changingLanguage = false

    // status tab
    private lateinit var statusView: View
    private lateinit var depthBig: TextView
    private lateinit var subLine: TextView
    private lateinit var details: TextView
    private lateinit var toggle: MaterialButton
    private lateinit var battery: MaterialButton

    // sonar tab
    private lateinit var sonarView: SonarView

    // settings tab
    private lateinit var settingsView: View
    private lateinit var languageButton: MaterialButton
    private lateinit var modeGroup: RadioGroup
    private lateinit var ssidLayout: TextInputLayout
    private lateinit var ssidEdit: TextInputEditText
    private lateinit var passEdit: TextInputEditText
    private lateinit var demoSwitch: MaterialSwitch
    private lateinit var distGroup: MaterialButtonToggleGroup
    private lateinit var tempGroup: MaterialButtonToggleGroup
    private lateinit var textGroup: MaterialButtonToggleGroup
    private lateinit var beamGroup: MaterialButtonToggleGroup
    private lateinit var alarmEdit: TextInputEditText
    private lateinit var rangeEdit: TextInputEditText
    private lateinit var gainSlider: Slider
    private lateinit var clarityGroup: MaterialButtonToggleGroup
    private lateinit var noiseGroup: MaterialButtonToggleGroup
    private lateinit var styleGroup: MaterialButtonToggleGroup
    private lateinit var fishGroup: MaterialButtonToggleGroup
    private lateinit var keelEdit: TextInputEditText
    private lateinit var tempOffEdit: TextInputEditText

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

        statusView = buildStatusView()
        sonarView = SonarView(this)
        settingsView = buildSettingsView()
        restoreDraft(savedInstanceState?.getBundle("settings_draft"))

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
            nav.setOnItemSelectedListener { item -> show(item.itemId); true }
        }

        val root: LinearLayout
        val navBar: NavigationBarView
        if (landscape) {
            val rail = NavigationRailView(this)
            buildMenu(rail)
            content.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f,
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
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
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
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        root.isSaveFromParentEnabled = false
        setContentView(root)
        navBar.selectedItemId = savedInstanceState?.getInt("current_tab", lastTab) ?: lastTab

        scope.launch {
            BridgeState.flow.collect { renderStatus(it) }
        }
    }

    private fun show(tab: Int) {
        if (tab == currentTab) return
        if (currentTab == TAB_SETTINGS) saveSettings()
        currentTab = tab
        lastTab = tab
        content.removeAllViews()
        content.addView(
            when (tab) {
                TAB_SONAR -> sonarView
                TAB_SETTINGS -> settingsView
                else -> statusView
            }
        )
        if (tab == TAB_STATUS) renderStatus(BridgeState.flow.value)
    }

    // ---------------------------------------------------------------- status

    private fun buildStatusView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        depthBig = TextView(this).apply {
            setTextAppearance(MR.style.TextAppearance_Material3_DisplayLarge)
            text = "—"
        }
        subLine = TextView(this).apply {
            setTextAppearance(MR.style.TextAppearance_Material3_TitleMedium)
            alpha = 0.75f
        }

        toggle = MaterialButton(this).apply {
            text = getString(R.string.connect)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
        }
        battery = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.battery_optimization)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }

        details = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
            addView(details)
        }

        root.addView(depthBig)
        root.addView(subLine)
        root.addView(toggle)
        root.addView(battery)
        root.addView(card)

        toggle.setOnClickListener {
            if (BridgeState.flow.value.running) {
                startService(
                    Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
                )
            } else {
                val pattern = MODE_PATTERNS[prefs.getInt("mode", 0)]
                val intent = Intent(this, BridgeService::class.java)
                    .setAction(BridgeService.ACTION_START)
                    .putExtra("pass", prefs.getString("pass", "12345678"))
                    .putExtra("demo", prefs.getBoolean("demo", false).toString())
                if (pattern != null) intent.putExtra("pattern", pattern)
                else intent.putExtra("ssid", prefs.getString("ssid", "SonarPhone_65C0")!!.toString())
                startForegroundService(intent)
            }
        }
        battery.setOnClickListener {
            // github build has REQUEST_IGNORE_BATTERY_OPTIMIZATIONS and can ask
            // directly; the Play build lacks it (policy) so it opens the list.
            val intent = if (BuildConfig.IS_PLAY) {
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            } else {
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            }
            startActivity(intent)
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun configSummary(): String {
        if (prefs.getBoolean("demo", false)) return getString(R.string.phase_demo)
        return when (MODE_PATTERNS.getOrNull(prefs.getInt("mode", 0))) {
            "SonarPhone_" -> getString(R.string.summary_sonarphone)
            "T-BOX-" -> getString(R.string.summary_tbox)
            else -> prefs.getString("ssid", "?")!!
        }
    }

    private fun renderStatus(s: BridgeState.Snapshot) {
        toggle.text = if (s.running) getString(R.string.disconnect) else getString(R.string.connect)
        depthBig.text = s.depthM?.let { Units.depth(it) } ?: "—"
        subLine.text = if (s.depthM != null) {
            "${Units.temp(s.tempC ?: 0.0)}  ·  ${
                String.format(AppLanguage.regionalLocale(this), "%.1f V", s.vBatt ?: 0.0)
            }  ·  ${StatusText.phase(this, s.phase)}"
        } else {
            StatusText.phase(this, s.phase)
        }
        val ts = RegionalFormat.timeFormatter(this)
        val numbers = java.text.NumberFormat.getIntegerInstance(AppLanguage.regionalLocale(this))
        details.text = listOf(
            getString(R.string.status_network, if (s.phase == "DEMO") getString(R.string.phase_demo) else if (s.running) s.ssid ?: "-" else configSummary()),
            getString(R.string.status_serial, s.serial ?: "-"),
            getString(R.string.status_master, s.masterMac ?: "-"),
            getString(R.string.status_frames, numbers.format(s.frameCount), numbers.format(s.frameSize)),
            getString(R.string.status_units, getString(if (s.unitsFeet) R.string.feet_converted else R.string.meters)),
            getString(R.string.status_received, if (s.lastFrameWallMs > 0) ts.format(Date(s.lastFrameWallMs)) else "-"),
            getString(R.string.status_nmea, numbers.format(s.nmeaClients)),
            getString(R.string.status_app, BuildConfig.VERSION_NAME),
            "",
            getString(R.string.navionics_pairing),
        ).joinToString("\n")
    }

    // ---------------------------------------------------------------- settings

    private fun buildSettingsView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        fun header(text: String, topPad: Int = 16) = TextView(this).apply {
            this.text = text
            setTextAppearance(MR.style.TextAppearance_Material3_TitleMedium)
            setPadding(0, dp(topPad), 0, dp(8))
        }
        fun note(text: String) = TextView(this).apply {
            this.text = text
            setTextAppearance(MR.style.TextAppearance_Material3_BodySmall)
            alpha = 0.7f
            setPadding(0, dp(4), 0, dp(8))
        }

        root.addView(header(getString(R.string.language_heading), 0))
        languageButton = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = AppLanguage.choices[AppLanguage.selectedTag(this@MainActivity)]
                ?: getString(R.string.follow_device)
            setOnClickListener { showLanguagePicker() }
        }
        root.addView(languageButton)
        root.addView(note(getString(R.string.language_note)))
        root.addView(header(getString(R.string.network_heading)))
        modeGroup = RadioGroup(this)
        listOf(
            getString(R.string.network_sonarphone),
            getString(R.string.network_tbox),
            getString(R.string.network_specific),
        ).forEachIndexed { i, label ->
            modeGroup.addView(RadioButton(this).apply { id = i + 1; text = label })
        }
        root.addView(modeGroup)

        ssidEdit = TextInputEditText(this).apply {
            setText(prefs.getString("ssid", "SonarPhone_65C0"))
        }
        ssidLayout = TextInputLayout(this).apply {
            hint = getString(R.string.ssid_label)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(ssidEdit)
        }
        root.addView(ssidLayout)
        root.addView(
            note(
                getString(R.string.network_note)
            )
        )

        root.addView(header(getString(R.string.password_heading)))
        passEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", "12345678"))
        }
        root.addView(
            TextInputLayout(this).apply {
                hint = getString(R.string.password_label)
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                addView(passEdit)
            }
        )
        root.addView(note(getString(R.string.factory_password)))

        root.addView(header(getString(R.string.units_heading)))
        fun segBtn(id: Int, label: String) =
            MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
                this.id = id
                text = label
            }
        // add a toggle group full-width, its buttons sharing the width equally
        fun addGroup(g: MaterialButtonToggleGroup, topPad: Int = 8) {
            g.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(topPad) }
            for (i in 0 until g.childCount) {
                g.getChildAt(i).layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            root.addView(g)
        }
        distGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.feet)))
            addView(segBtn(2, getString(R.string.meters)))
            check(if (prefs.getString("unit_dist", "ft") == "ft") 1 else 2)
        }
        addGroup(distGroup)
        tempGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(segBtn(1, getString(R.string.celsius)))
            addView(segBtn(2, getString(R.string.fahrenheit)))
            check(if (prefs.getString("unit_temp", "C") == "C") 1 else 2)
        }
        addGroup(tempGroup)
        root.addView(note(getString(R.string.units_note)))

        root.addView(header(getString(R.string.text_size_heading)))
        textGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.text_normal)))
            addView(segBtn(2, getString(R.string.text_large)))
            addView(segBtn(3, getString(R.string.text_xl)))
            check(
                when (prefs.getString("text_size", "normal")) {
                    "large" -> 2; "xl" -> 3; else -> 1
                }
            )
        }
        addGroup(textGroup)

        root.addView(header(getString(R.string.sonar_heading)))
        gainSlider = Slider(this).apply {
            valueFrom = 50f
            valueTo = 200f
            stepSize = 10f
            value = prefs.getInt("gain_pct", 100).coerceIn(50, 200).toFloat()
            setLabelFormatter { v ->
                getString(R.string.gain_percent, java.text.NumberFormat.getIntegerInstance(AppLanguage.regionalLocale(this@MainActivity)).format(v.toInt()))
            }
        }
        root.addView(TextView(this).apply { text = getString(R.string.gain_label) })
        root.addView(gainSlider)
        root.addView(TextView(this).apply { text = getString(R.string.display_style) })
        styleGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.style_modern)))
            addView(segBtn(2, getString(R.string.style_classic)))
            check(prefs.getInt("display_style", 0).coerceIn(0, 1) + 1)
        }
        addGroup(styleGroup)
        root.addView(TextView(this).apply { text = getString(R.string.fish_markers) })
        fishGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.off)))
            addView(segBtn(2, getString(R.string.fish)))
            addView(segBtn(3, getString(R.string.fish_depth)))
            check(prefs.getInt("fish_markers", 1).coerceIn(0, 2) + 1)
        }
        addGroup(fishGroup)
        root.addView(
            note(
                getString(R.string.style_note)
            )
        )

        root.addView(TextView(this).apply { text = getString(R.string.surface_clarity) })
        clarityGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.off)))
            addView(segBtn(2, getString(R.string.low)))
            addView(segBtn(3, getString(R.string.medium)))
            addView(segBtn(4, getString(R.string.high)))
            check(prefs.getInt("surface_clarity", 0).coerceIn(0, 3) + 1)
        }
        addGroup(clarityGroup)
        root.addView(TextView(this).apply { text = getString(R.string.noise_filter) })
        noiseGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.off)))
            addView(segBtn(2, getString(R.string.low)))
            addView(segBtn(3, getString(R.string.medium)))
            addView(segBtn(4, getString(R.string.high)))
            check(prefs.getInt("noise_filter", 0).coerceIn(0, 3) + 1)
        }
        addGroup(noiseGroup)
        root.addView(
            note(
                getString(R.string.filters_note)
            )
        )

        root.addView(note(getString(R.string.numeric_input_note)))
        root.addView(header(getString(R.string.device_heading)))
        beamGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, getString(R.string.beam_narrow)))
            addView(segBtn(2, getString(R.string.beam_wide)))
            addView(segBtn(3, getString(R.string.beam_single)))
            check(
                when (prefs.getString("beam", "20")) {
                    "40" -> 2; "125" -> 3; else -> 1
                }
            )
        }
        addGroup(beamGroup)
        root.addView(
            note(
                getString(R.string.beam_note)
            )
        )
        rangeEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            val m = prefs.getFloat("dev_range_m", 0f)
            if (m > 0f) {
                val v = if (Units.feet) m * 3.28084 else m.toDouble()
                setText(String.format(Locale.US, "%.0f", v))
            }
        }
        root.addView(
            TextInputLayout(this).apply {
                hint = getString(R.string.range_hint, if (Units.feet) "ft" else "m")
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                addView(rangeEdit)
            }
        )
        root.addView(
            note(
                getString(R.string.range_note)
            )
        )

        val du = if (Units.feet) "ft" else "m"
        keelEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            val m = prefs.getFloat("keel_offset_m", 0f)
            if (m != 0f) {
                setText(String.format(Locale.US, "%.1f", if (Units.feet) m * 3.28084 else m))
            }
        }
        root.addView(
            TextInputLayout(this).apply {
                hint = getString(R.string.keel_hint, du)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                addView(keelEdit)
            }
        )
        tempOffEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            val c = prefs.getFloat("temp_offset_c", 0f)
            if (c != 0f) {
                val v = if (Units.fahrenheit) c * 9 / 5 else c
                setText(String.format(Locale.US, "%.1f", v))
            }
        }
        root.addView(
            TextInputLayout(this).apply {
                hint = getString(R.string.temperature_hint, if (Units.fahrenheit) "F" else "C")
                addView(tempOffEdit)
            }
        )
        root.addView(
            note(
                getString(R.string.offset_note)
            )
        )

        root.addView(header(getString(R.string.alarm_heading)))
        alarmEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            val m = prefs.getFloat("alarm_m", 0f)
            if (m > 0f) {
                val v = if (Units.feet) m * 3.28084 else m.toDouble()
                setText(String.format(Locale.US, "%.1f", v))
            }
        }
        root.addView(
            TextInputLayout(this).apply {
                hint = getString(R.string.alarm_hint, if (Units.feet) "ft" else "m")
                addView(alarmEdit)
            }
        )
        root.addView(note(getString(R.string.alarm_note)))

        root.addView(header(getString(R.string.demo_heading)))
        demoSwitch = MaterialSwitch(this).apply {
            text = getString(R.string.demo_switch)
            isChecked = prefs.getBoolean("demo", false)
        }
        root.addView(demoSwitch)
        root.addView(
            note(
                getString(R.string.demo_note)
            )
        )
        root.addView(
            note(
                getString(R.string.settings_note)
            )
        )

        root.addView(header(getString(R.string.updates_heading)))
        root.addView(AndroidDownloads.buildGuidanceView(this, migration = !BuildConfig.IS_PLAY))

        val mode = prefs.getInt("mode", 0)
        modeGroup.check(mode + 1)
        ssidLayout.isEnabled = MODE_PATTERNS[mode] == null
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            ssidLayout.isEnabled = MODE_PATTERNS[checkedId - 1] == null
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun saveSettings() {
        if (!::modeGroup.isInitialized) return
        val alarmDisp = alarmEdit.text?.toString()?.toDoubleOrNull() ?: 0.0
        val alarmM = if (Units.feet) alarmDisp * 0.3048 else alarmDisp
        val rangeDisp = rangeEdit.text?.toString()?.toDoubleOrNull() ?: 0.0
        val rangeM = (if (Units.feet) rangeDisp * 0.3048 else rangeDisp).coerceIn(0.0, 73.0)
        prefs.edit()
            .putInt("mode", (modeGroup.checkedRadioButtonId - 1).coerceIn(0, 2))
            .putString("ssid", ssidEdit.text?.toString() ?: "")
            .putString("pass", passEdit.text?.toString() ?: "")
            .putBoolean("demo", demoSwitch.isChecked)
            .putString("unit_dist", if (distGroup.checkedButtonId == 2) "m" else "ft")
            .putString("unit_temp", if (tempGroup.checkedButtonId == 2) "F" else "C")
            .putString(
                "text_size",
                when (textGroup.checkedButtonId) { 2 -> "large"; 3 -> "xl"; else -> "normal" }
            )
            .putString(
                "beam",
                when (beamGroup.checkedButtonId) { 2 -> "40"; 3 -> "125"; else -> "20" }
            )
            .putFloat("dev_range_m", rangeM.toFloat())
            .putFloat("alarm_m", alarmM.toFloat())
            .putInt("gain_pct", gainSlider.value.toInt())
            .putInt("surface_clarity", (clarityGroup.checkedButtonId - 1).coerceIn(0, 3))
            .putInt("noise_filter", (noiseGroup.checkedButtonId - 1).coerceIn(0, 3))
            .putInt("display_style", (styleGroup.checkedButtonId - 1).coerceIn(0, 1))
            .putInt("fish_markers", (fishGroup.checkedButtonId - 1).coerceIn(0, 2))
            .putFloat("keel_offset_m", run {
                val v = keelEdit.text?.toString()?.toDoubleOrNull() ?: 0.0
                (if (Units.feet) v * 0.3048 else v).toFloat()
            })
            .putFloat("temp_offset_c", run {
                val v = tempOffEdit.text?.toString()?.toDoubleOrNull() ?: 0.0
                (if (Units.fahrenheit) v * 5 / 9 else v).toFloat()
            })
            .apply()
        Units.load(prefs)
    }

    /** Use platform-backed app preferences, with a recognizable native label for every option. */
    private fun showLanguagePicker() {
        val tags = listOf<String?>(null) + AppLanguage.choices.keys
        val labels = listOf(getString(R.string.follow_device)) + AppLanguage.choices.values
        val selected = tags.indexOf(AppLanguage.selectedTag(this)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_heading)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { dialog, index ->
                dialog.dismiss()
                // A locale-only refresh must not commit unfinished calibration or password drafts.
                changingLanguage = AppLanguage.changesConfiguration(this, tags[index])
                AppLanguage.select(this, tags[index])
                // A matching explicit language can persist without triggering recreation.
                languageButton.text = AppLanguage.choices[AppLanguage.selectedTag(this)]
                    ?: getString(R.string.follow_device)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Explicit state is needed because programmatic controls reuse local IDs in separate groups. */
    private fun saveDraft(): Bundle = Bundle().apply {
        val fields = listOf(ssidEdit, passEdit, rangeEdit, keelEdit, tempOffEdit, alarmEdit)
        fields.forEachIndexed { index, field -> putString("field_$index", field.text?.toString()) }
        val groups = listOf(distGroup, tempGroup, textGroup, beamGroup, clarityGroup, noiseGroup, styleGroup, fishGroup)
        groups.forEachIndexed { index, group -> putInt("group_$index", group.checkedButtonId) }
        putInt("mode", modeGroup.checkedRadioButtonId)
        putBoolean("demo", demoSwitch.isChecked)
        putFloat("gain", gainSlider.value)
    }

    /** Restore exact draft text, including blank or unfinished decimal entries, after recreation. */
    private fun restoreDraft(draft: Bundle?) {
        if (draft == null) return
        val fields = listOf(ssidEdit, passEdit, rangeEdit, keelEdit, tempOffEdit, alarmEdit)
        fields.forEachIndexed { index, field -> field.setText(draft.getString("field_$index")) }
        val groups = listOf(distGroup, tempGroup, textGroup, beamGroup, clarityGroup, noiseGroup, styleGroup, fishGroup)
        groups.forEachIndexed { index, group -> group.check(draft.getInt("group_$index")) }
        modeGroup.check(draft.getInt("mode"))
        demoSwitch.isChecked = draft.getBoolean("demo")
        gainSlider.value = draft.getFloat("gain", 100f)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("current_tab", currentTab)
        outState.putBundle("settings_draft", saveDraft())
        super.onSaveInstanceState(outState)
    }

    // ---------------------------------------------------------------- misc

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        renderStatus(BridgeState.flow.value)
        AndroidDownloads.maybeShowMigrationNotice(this)
    }

    override fun onPause() {
        if (currentTab == TAB_SETTINGS && !changingLanguage && !isChangingConfigurations) saveSettings()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
