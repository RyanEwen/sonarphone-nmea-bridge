package com.rewen.sonarbridge

import android.content.Intent
import android.content.SharedPreferences
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
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        statusView = buildStatusView()
        sonarView = SonarView(this)
        settingsView = buildSettingsView()

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val nav = BottomNavigationView(this).apply {
            menu.add(Menu.NONE, TAB_STATUS, 0, "Status").setIcon(R.drawable.ic_tab_status)
            menu.add(Menu.NONE, TAB_SONAR, 1, "Sonar").setIcon(R.drawable.ic_tab_sonar)
            menu.add(Menu.NONE, TAB_SETTINGS, 2, "Settings").setIcon(R.drawable.ic_tab_settings)
            setOnItemSelectedListener { item -> show(item.itemId); true }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(content)
                addView(nav)
            }
        )
        nav.selectedItemId = TAB_STATUS

        scope.launch {
            BridgeState.flow.collect { renderStatus(it) }
        }
    }

    private fun show(tab: Int) {
        if (tab == currentTab) return
        if (currentTab == TAB_SETTINGS) saveSettings()
        currentTab = tab
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
            text = "Start bridge"
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
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
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
        toggle.text = if (s.running) "Stop bridge" else "Start bridge"
        depthBig.text = s.depthM?.let { String.format(Locale.US, "%.2f m", it) } ?: "—"
        subLine.text = if (s.depthM != null) {
            String.format(
                Locale.US, "%.0f °C  ·  %.1f V  ·  %s",
                s.tempC ?: 0.0, s.vBatt ?: 0.0, s.phase,
            )
        } else {
            s.phase
        }
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
        details.text = buildString {
            appendLine("network:  ${if (s.running) s.ssid ?: "-" else configSummary()}")
            appendLine("serial:   ${s.serial ?: "-"}")
            appendLine("master:   ${s.masterMac ?: "-"}")
            appendLine("frames:   ${s.frameCount} (${s.frameSize} B)")
            appendLine("units:    ${if (s.unitsFeet) "feet (converted)" else "meters"}")
            appendLine("last rx:  ${if (s.lastFrameWallMs > 0) ts.format(Date(s.lastFrameWallMs)) else "-"}")
            appendLine("NMEA:     ${s.nmeaClients} client(s) on 127.0.0.1:10110")
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
                "When the bridge starts, Android shows the matching networks in " +
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
                "Settings apply the next time the bridge starts — if it's " +
                    "already running, stop and start it again."
            )
        )

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
        prefs.edit()
            .putInt("mode", (modeGroup.checkedRadioButtonId - 1).coerceIn(0, 2))
            .putString("ssid", ssidEdit.text?.toString() ?: "")
            .putString("pass", passEdit.text?.toString() ?: "")
            .putBoolean("demo", demoSwitch.isChecked)
            .apply()
    }

    // ---------------------------------------------------------------- misc

    override fun onResume() {
        super.onResume()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        renderStatus(BridgeState.flow.value)
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
