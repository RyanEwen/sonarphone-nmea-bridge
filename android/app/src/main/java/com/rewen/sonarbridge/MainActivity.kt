package com.rewen.sonarbridge

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var status: TextView
    private lateinit var networkMode: Spinner
    private lateinit var customSsid: EditText
    private lateinit var pass: EditText
    private lateinit var toggle: Button
    private lateinit var battery: Button

    // spinner order; first two are prefix patterns, last shows the SSID box
    private val modePatterns = listOf("SonarPhone_", "T-BOX-", null)
    private val modeLabels = listOf(
        "SonarPhone_* — pick from list",
        "T-BOX-* — pick from list",
        "Exact SSID…",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        networkMode = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, modeLabels
            )
            setSelection(prefs.getInt("mode", 0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    customSsid.visibility =
                        if (modePatterns[pos] == null) View.VISIBLE else View.GONE
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        customSsid = EditText(this).apply {
            hint = "SSID"
            setText(prefs.getString("ssid", "SonarPhone_65C0"))
            visibility = if (modePatterns[prefs.getInt("mode", 0)] == null)
                View.VISIBLE else View.GONE
        }
        pass = EditText(this).apply {
            hint = "WiFi password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", "12345678"))
        }

        toggle = Button(this).apply { text = "Start bridge" }
        battery = Button(this).apply { text = "Disable battery optimization" }

        status = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        root.addView(networkMode); root.addView(customSsid); root.addView(pass)
        root.addView(toggle); root.addView(battery); root.addView(status)
        setContentView(ScrollView(this).apply { addView(root) })

        toggle.setOnClickListener {
            if (BridgeState.flow.value.running) {
                startService(
                    Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_STOP)
                )
            } else {
                val mode = networkMode.selectedItemPosition
                val pattern = modePatterns[mode]
                prefs.edit()
                    .putInt("mode", mode)
                    .putString("ssid", customSsid.text.toString())
                    .putString("pass", pass.text.toString())
                    .apply()
                val intent = Intent(this, BridgeService::class.java)
                    .setAction(BridgeService.ACTION_START)
                    .putExtra("pass", pass.text.toString())
                if (pattern != null) intent.putExtra("pattern", pattern)
                else intent.putExtra("ssid", customSsid.text.toString())
                startForegroundService(intent)
            }
        }
        battery.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }

        scope.launch {
            BridgeState.flow.collect { render(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // hide the exemption button once granted (also on return from Settings)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        battery.visibility =
            if (pm.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun render(s: BridgeState.Snapshot) {
        toggle.text = if (s.running) "Stop bridge" else "Start bridge"
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US)
        status.text = buildString {
            appendLine("state:    ${s.phase}")
            appendLine("ssid:     ${s.ssid ?: "-"}")
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
