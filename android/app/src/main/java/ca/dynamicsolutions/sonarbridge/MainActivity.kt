package ca.dynamicsolutions.sonarbridge

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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // on-the-water use: never dim/lock while the app is in front
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        statusView = buildStatusView()
        sonarView = SonarView(this)
        settingsView = buildSettingsView()

        content = FrameLayout(this)

        // Portrait: tabs across the bottom. Landscape (phone or tablet): a
        // vertical nav rail on the left, so the wide-but-short screen keeps its
        // full height for the sonar view (the rail sits left of the zoom
        // button). Rotation recreates the activity, so this re-picks the layout.
        val landscape =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        fun buildMenu(nav: NavigationBarView) {
            nav.menu.add(Menu.NONE, TAB_STATUS, 0, "Status").setIcon(R.drawable.ic_tab_status)
            nav.menu.add(Menu.NONE, TAB_SONAR, 1, "Sonar").setIcon(R.drawable.ic_tab_sonar)
            nav.menu.add(Menu.NONE, TAB_SETTINGS, 2, "Settings").setIcon(R.drawable.ic_tab_settings)
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
        setContentView(root)
        navBar.selectedItemId = lastTab

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
            text = "Connect"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
        }
        battery = MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = "Disable battery optimization"
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
        if (prefs.getBoolean("demo", false)) return "demo data (no sonar)"
        return when (MODE_PATTERNS.getOrNull(prefs.getInt("mode", 0))) {
            "SonarPhone_" -> "any SonarPhone_*"
            "T-BOX-" -> "any T-BOX-*"
            else -> prefs.getString("ssid", "?")!!
        }
    }

    private fun renderStatus(s: BridgeState.Snapshot) {
        toggle.text = if (s.running) "Disconnect" else "Connect"
        depthBig.text = s.depthM?.let { Units.depth(it) } ?: "—"
        subLine.text = if (s.depthM != null) {
            "${Units.temp(s.tempC ?: 0.0)}  ·  ${
                String.format(Locale.US, "%.1f V", s.vBatt ?: 0.0)
            }  ·  ${s.phase}"
        } else {
            s.phase
        }
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
        details.text = buildString {
            appendLine("network:  ${if (s.running) s.ssid ?: "-" else configSummary()}")
            appendLine("serial:   ${s.serial ?: "-"}")
            appendLine("master:   ${s.masterMac ?: "-"}")
            appendLine("frames:   ${s.frameCount} (${s.frameSize} B)")
            appendLine("rx units: ${if (s.unitsFeet) "feet (converted)" else "meters"}")
            appendLine("last rx:  ${if (s.lastFrameWallMs > 0) ts.format(Date(s.lastFrameWallMs)) else "-"}")
            appendLine("NMEA:     ${s.nmeaClients} client(s) on 127.0.0.1:10110")
            appendLine("app:      v${BuildConfig.VERSION_NAME}")
            appendLine()
            append("Navionics: Paired devices > +\n  Host 127.0.0.1  Port 10110  TCP")
        }
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

        root.addView(header("Sonar WiFi network", 0))
        modeGroup = RadioGroup(this)
        listOf(
            "Any SonarPhone (SSID starts with “SonarPhone_”)",
            "Any T-Box (SSID starts with “T-BOX-”)",
            "Specific SSID:",
        ).forEachIndexed { i, label ->
            modeGroup.addView(RadioButton(this).apply { id = i + 1; text = label })
        }
        root.addView(modeGroup)

        ssidEdit = TextInputEditText(this).apply {
            setText(prefs.getString("ssid", "SonarPhone_65C0"))
        }
        ssidLayout = TextInputLayout(this).apply {
            hint = "SSID"
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(ssidEdit)
        }
        root.addView(ssidLayout)
        root.addView(
            note(
                "When connecting, Android shows the matching networks in " +
                    "range — tap your sonar in that list."
            )
        )

        root.addView(header("WiFi password"))
        passEdit = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", "12345678"))
        }
        root.addView(
            TextInputLayout(this).apply {
                hint = "Password"
                endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                addView(passEdit)
            }
        )
        root.addView(note("Factory default is 12345678."))

        root.addView(header("Units"))
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
            addView(segBtn(1, "Feet"))
            addView(segBtn(2, "Meters"))
            check(if (prefs.getString("unit_dist", "ft") == "ft") 1 else 2)
        }
        addGroup(distGroup)
        tempGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(8) }
            addView(segBtn(1, "Celsius"))
            addView(segBtn(2, "Fahrenheit"))
            check(if (prefs.getString("unit_temp", "C") == "C") 1 else 2)
        }
        addGroup(tempGroup)
        root.addView(note("Display only — the NMEA feed to Navionics always uses standard units."))

        root.addView(header("Text size (sonar view)"))
        textGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, "Normal"))
            addView(segBtn(2, "Large"))
            addView(segBtn(3, "XL"))
            check(
                when (prefs.getString("text_size", "normal")) {
                    "large" -> 2; "xl" -> 3; else -> 1
                }
            )
        }
        addGroup(textGroup)

        root.addView(header("Sonar display"))
        gainSlider = Slider(this).apply {
            valueFrom = 50f
            valueTo = 200f
            stepSize = 10f
            value = prefs.getInt("gain_pct", 100).coerceIn(50, 200).toFloat()
            setLabelFormatter { v -> "${v.toInt()}%" }
        }
        root.addView(TextView(this).apply { text = "Gain (sensitivity)" })
        root.addView(gainSlider)
        root.addView(TextView(this).apply { text = "Display style" })
        styleGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, "Modern"))
            addView(segBtn(2, "Classic"))
            check(prefs.getInt("display_style", 0).coerceIn(0, 1) + 1)
        }
        addGroup(styleGroup)
        root.addView(TextView(this).apply { text = "Fish markers" })
        fishGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, "Off"))
            addView(segBtn(2, "Fish"))
            addView(segBtn(3, "Fish + depth"))
            check(prefs.getInt("fish_markers", 1).coerceIn(0, 2) + 1)
        }
        addGroup(fishGroup)
        root.addView(
            note(
                "Classic style emulates the original SonarPhone look (navy " +
                    "background, rainbow returns). Fish markers flag strong " +
                    "isolated mid-water echoes."
            )
        )

        root.addView(TextView(this).apply { text = "Surface clarity" })
        clarityGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, "Off"))
            addView(segBtn(2, "Low"))
            addView(segBtn(3, "Med"))
            addView(segBtn(4, "High"))
            check(prefs.getInt("surface_clarity", 0).coerceIn(0, 3) + 1)
        }
        addGroup(clarityGroup)
        root.addView(TextView(this).apply { text = "Noise filter" })
        noiseGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, "Off"))
            addView(segBtn(2, "Low"))
            addView(segBtn(3, "Med"))
            addView(segBtn(4, "High"))
            check(prefs.getInt("noise_filter", 0).coerceIn(0, 3) + 1)
        }
        addGroup(noiseGroup)
        root.addView(
            note(
                "Gain brightens or quiets all echoes; noise filter hides weak " +
                    "speckle; surface clarity fades the clutter band at the top. " +
                    "These are the SonarPhone app's own display controls, applied " +
                    "here to the raw echo — they change the picture, not the sonar. " +
                    "All apply immediately."
            )
        )

        root.addView(header("Device (T-Box)"))
        beamGroup = MaterialButtonToggleGroup(this).apply {
            isSingleSelection = true
            addView(segBtn(1, "200 kHz · 20°"))
            addView(segBtn(2, "83 kHz · 40°"))
            addView(segBtn(3, "125 kHz"))
            check(
                when (prefs.getString("beam", "20")) {
                    "40" -> 2; "125" -> 3; else -> 1
                }
            )
        }
        addGroup(beamGroup)
        root.addView(
            note(
                "Transducer beam: narrow (200 kHz) focuses deeper with more " +
                    "detail, wide (83 kHz) covers more area. 125 kHz is the " +
                    "single-beam models' frequency — experimental on an SP200A. " +
                    "Applies on next connect."
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
                hint = "Device depth range (${if (Units.feet) "ft" else "m"}, blank = auto)"
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(8) }
                addView(rangeEdit)
            }
        )
        root.addView(
            note(
                "Locks the sonar's own range instead of auto (max 240 ft / 73 m). " +
                    "May sharpen shallow-water detail if the unit re-spans its " +
                    "samples — experimental. Applies on next connect."
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
                hint = "Keel offset ($du, +/-)"
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
                hint = "Temperature offset (°${if (Units.fahrenheit) "F" else "C"}, +/-)"
                addView(tempOffEdit)
            }
        )
        root.addView(
            note(
                "Keel offset shifts all depth readings (positive = deeper), so " +
                    "they reflect depth below your keel. Temperature offset " +
                    "calibrates the reading against a known thermometer. Both " +
                    "apply to displayed values and the Navionics feed."
            )
        )

        root.addView(header("Shallow water alarm"))
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
                hint = "Alarm depth (${if (Units.feet) "ft" else "m"}, blank = off)"
                addView(alarmEdit)
            }
        )
        root.addView(note("Repeating tone when the depth reads shallower than this. Applies on next connect."))

        root.addView(header("Demo mode"))
        demoSwitch = MaterialSwitch(this).apply {
            text = "Generate fake sonar data (no hardware needed)"
            isChecked = prefs.getBoolean("demo", false)
        }
        root.addView(demoSwitch)
        root.addView(
            note(
                "Use this to test the Navionics pairing and the sonar view " +
                    "without the T-Box powered on."
            )
        )
        root.addView(
            note(
                "Settings apply the next time you connect — if already " +
                    "connected, disconnect and reconnect."
            )
        )

        root.addView(header("Updates"))
        if (BuildConfig.IS_PLAY) {
            root.addView(note("Version ${BuildConfig.VERSION_NAME}. Updates are delivered through Google Play."))
        } else {
            root.addView(
                MaterialButton(this, null, MR.attr.materialButtonOutlinedStyle).apply {
                    text = "Check for updates"
                    setOnClickListener { UpdateCheck.manualCheck(this@MainActivity, scope) }
                }
            )
            root.addView(note("Version ${BuildConfig.VERSION_NAME}. Updates are also offered automatically when the app opens."))
        }

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

    // ---------------------------------------------------------------- misc

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        renderStatus(BridgeState.flow.value)
        UpdateCheck.maybeCheck(this, scope)
    }

    override fun onPause() {
        if (currentTab == TAB_SETTINGS) saveSettings()
        super.onPause()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
