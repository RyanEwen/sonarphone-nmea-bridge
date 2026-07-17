package com.rewen.sonarbridge

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    companion object {
        // radio order; null = specific SSID (must line up with BridgeService)
        val MODE_PATTERNS = listOf("SonarPhone_", "T-BOX-", null)
        const val TAB_STATUS = 0
        const val TAB_SONAR = 1
        const val TAB_SETTINGS = 2
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: SharedPreferences by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }

    private lateinit var content: FrameLayout
    private lateinit var navButtons: List<Button>
    private var currentTab = -1

    // status tab
    private lateinit var statusView: View
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var battery: Button

    // sonar tab
    private lateinit var sonarView: SonarView

    // settings tab
    private lateinit var settingsView: View
    private lateinit var modeGroup: RadioGroup
    private lateinit var ssidEdit: EditText
    private lateinit var passEdit: EditText
    private lateinit var demoCheck: CheckBox

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
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            )
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        navButtons = listOf("Status", "Sonar", "Settings").mapIndexed { i, label ->
            Button(this).apply {
                text = label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { show(i) }
            }.also { nav.addView(it) }
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(content)
                addView(nav)
            }
        )
        show(TAB_STATUS)

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
        navButtons.forEachIndexed { i, b -> b.isEnabled = i != tab }
        if (tab == TAB_STATUS) renderStatus(BridgeState.flow.value)
    }

    // ---------------------------------------------------------------- status

    private fun buildStatusView(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        toggle = Button(this).apply { text = "Start bridge" }
        battery = Button(this).apply { text = "Disable battery optimization" }
        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        root.addView(toggle); root.addView(battery); root.addView(status)

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
                else intent.putExtra("ssid", prefs.getString("ssid", "SonarPhone_65C0"))
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
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
        status.text = buildString {
            appendLine("state:    ${s.phase}")
            appendLine("network:  ${if (s.running) s.ssid ?: "-" else configSummary()}")
            appendLine("serial:   ${s.serial ?: "-"}")
            appendLine("master:   ${s.masterMac ?: "-"}")
            appendLine()
            appendLine("depth:    ${s.depthM?.let { String.format(Locale.US, "%.2f m", it) } ?: "-"}")
            appendLine("temp:     ${s.tempC?.let { String.format(Locale.US, "%.0f °C", it) } ?: "-"}")
            appendLine("battery:  ${s.vBatt?.let { String.format(Locale.US, "%.2f V", it) } ?: "-"}")
            appendLine("frames:   ${s.frameCount} (${s.frameSize} B)")
            appendLine("units:    ${if (s.unitsFeet) "feet (converted)" else "meters"}")
            appendLine("last rx:  ${if (s.lastFrameWallMs > 0) ts.format(Date(s.lastFrameWallMs)) else "-"}")
            appendLine()
            appendLine("NMEA:     ${s.nmeaClients} client(s) on 127.0.0.1:10110 (TCP)")
            appendLine()
            appendLine("Navionics: Menu > Paired devices > +")
            appendLine("  Host 127.0.0.1  Port 10110  TCP")
        }
    }

    // ---------------------------------------------------------------- settings

    private fun buildSettingsView(): View {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun header(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, pad / 2, 0, pad / 4)
        }
        fun note(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            alpha = 0.7f
            setPadding(0, pad / 4, 0, pad / 2)
        }

        root.addView(header("Sonar WiFi network"))
        modeGroup = RadioGroup(this)
        listOf(
            "Any SonarPhone (SSID starts with “SonarPhone_”)",
            "Any T-Box (SSID starts with “T-BOX-”)",
            "Specific SSID:",
        ).forEachIndexed { i, label ->
            modeGroup.addView(RadioButton(this).apply { id = i + 1; text = label })
        }
        root.addView(modeGroup)
        ssidEdit = EditText(this).apply {
            hint = "SSID"
            setText(prefs.getString("ssid", "SonarPhone_65C0"))
        }
        root.addView(ssidEdit)
        root.addView(
            note(
                "When the bridge starts, Android shows the matching networks in " +
                    "range — tap your sonar in that list."
            )
        )

        root.addView(header("WiFi password"))
        passEdit = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", "12345678"))
        }
        root.addView(passEdit)
        root.addView(note("Factory default is 12345678."))

        root.addView(header("Demo mode"))
        demoCheck = CheckBox(this).apply {
            text = "Generate fake sonar data (no hardware needed)"
            isChecked = prefs.getBoolean("demo", false)
        }
        root.addView(demoCheck)
        root.addView(
            note(
                "Use this to test the Navionics pairing and the sonar view " +
                    "without the T-Box powered on."
            )
        )
        root.addView(
            note("Settings apply the next time the bridge starts — if it's " +
                "already running, stop and start it again.")
        )

        val mode = prefs.getInt("mode", 0)
        modeGroup.check(mode + 1)
        ssidEdit.isEnabled = MODE_PATTERNS[mode] == null
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            ssidEdit.isEnabled = MODE_PATTERNS[checkedId - 1] == null
        }
        return ScrollView(this).apply { addView(root) }
    }

    private fun saveSettings() {
        if (!::modeGroup.isInitialized) return
        prefs.edit()
            .putInt("mode", (modeGroup.checkedRadioButtonId - 1).coerceIn(0, 2))
            .putString("ssid", ssidEdit.text.toString())
            .putString("pass", passEdit.text.toString())
            .putBoolean("demo", demoCheck.isChecked)
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
