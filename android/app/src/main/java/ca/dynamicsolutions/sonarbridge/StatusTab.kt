package ca.dynamicsolutions.sonarbridge

import android.content.Intent
import android.graphics.Typeface
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Date
import com.google.android.material.R as MR

/**
 * The Status tab: the large depth readout, Connect/Disconnect, the battery-optimization
 * shortcut, and the connection details card. [render] redraws it from a bridge snapshot.
 */
class StatusTab(private val activity: AppCompatActivity, private val settings: AppSettings) {
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    private lateinit var depthBig: TextView
    private lateinit var subLine: TextView
    private lateinit var details: TextView
    private lateinit var toggle: MaterialButton
    private lateinit var battery: MaterialButton

    /** The tab's root view, built once per activity. */
    val view: View = build()

    private fun build(): View {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }

        depthBig = TextView(activity).apply {
            setTextAppearance(MR.style.TextAppearance_Material3_DisplayLarge)
            text = "—"
        }
        subLine = TextView(activity).apply {
            setTextAppearance(MR.style.TextAppearance_Material3_TitleMedium)
            alpha = 0.75f
        }

        toggle = MaterialButton(activity).apply {
            text = activity.getString(R.string.connect)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
        }
        battery = MaterialButton(activity, null, MR.attr.materialButtonOutlinedStyle).apply {
            text = activity.getString(R.string.battery_optimization)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }

        details = TextView(activity).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val card = MaterialCardView(activity).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) }
            addView(details)
        }

        root.addView(depthBig)
        root.addView(subLine)
        root.addView(toggle)
        root.addView(battery)
        root.addView(card)

        toggle.setOnClickListener { toggleBridge() }
        battery.setOnClickListener { openBatterySettings() }
        return ScrollView(activity).apply { addView(root) }
    }

    /** Stop a running bridge, or start one from the saved network choice. */
    private fun toggleBridge() {
        if (BridgeState.flow.value.running) {
            activity.startService(
                Intent(activity, BridgeService::class.java).setAction(BridgeService.ACTION_STOP),
            )
            return
        }

        val saved = settings.read()
        val pattern = AppSettings.NETWORK_PATTERNS[saved.networkMode]
        val intent = Intent(activity, BridgeService::class.java)
            .setAction(BridgeService.ACTION_START)
            .putExtra("pass", SonarPassphrase.get(activity))
            .putExtra("demo", saved.demo.toString())
        if (pattern != null) {
            intent.putExtra("pattern", pattern)
        } else {
            intent.putExtra("ssid", saved.ssid)
        }
        activity.startForegroundService(intent)
    }

    /**
     * Open Android's battery-optimization list, where the user sets SonarBridge to unrestricted.
     * Play policy rules out the permission that would let the app ask directly.
     */
    private fun openBatterySettings() {
        activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /** Hide the battery shortcut once the app is already exempt. */
    fun refreshBatteryButton() {
        val power = activity.getSystemService(AppCompatActivity.POWER_SERVICE) as PowerManager
        battery.visibility = if (power.isIgnoringBatteryOptimizations(activity.packageName)) View.GONE else View.VISIBLE
    }

    /** The configured network, for the details card while the bridge is stopped. */
    private fun configSummary(): String {
        val saved = settings.read()
        if (saved.demo) return activity.getString(R.string.phase_demo)
        return when (AppSettings.NETWORK_PATTERNS.getOrNull(saved.networkMode)) {
            "SonarPhone_" -> activity.getString(R.string.summary_sonarphone)
            "T-BOX-" -> activity.getString(R.string.summary_tbox)
            else -> saved.ssid
        }
    }

    /** Redraw the readout, button label and details card from [s]. */
    fun render(s: BridgeState.Snapshot) {
        toggle.text = if (s.running) activity.getString(R.string.disconnect) else activity.getString(R.string.connect)
        depthBig.text = s.depthM?.let { Units.depth(it) } ?: "—"
        subLine.text = if (s.depthM != null) {
            "${Units.temp(s.tempC ?: 0.0)}  ·  ${
                String.format(AppLanguage.regionalLocale(activity), "%.1f V", s.vBatt ?: 0.0)
            }  ·  ${StatusText.phase(activity, s.phase)}"
        } else {
            StatusText.phase(activity, s.phase)
        }
        val ts = RegionalFormat.timeFormatter(activity)
        val numbers = java.text.NumberFormat.getIntegerInstance(AppLanguage.regionalLocale(activity))
        details.text = listOf(
            activity.getString(
                R.string.status_network,
                if (s.phase == "DEMO") {
                    activity.getString(R.string.phase_demo)
                } else if (s.running) {
                    s.ssid ?: "-"
                } else {
                    configSummary()
                },
            ),
            activity.getString(R.string.status_serial, s.serial ?: "-"),
            activity.getString(R.string.status_master, s.masterMac ?: "-"),
            activity.getString(R.string.status_frames, numbers.format(s.frameCount), numbers.format(s.frameSize)),
            activity.getString(R.string.status_units, activity.getString(if (s.unitsFeet) R.string.feet_converted else R.string.meters)),
            activity.getString(R.string.status_received, if (s.lastFrameWallMs > 0) ts.format(Date(s.lastFrameWallMs)) else "-"),
            activity.getString(R.string.status_nmea, numbers.format(s.nmeaClients)),
            activity.getString(R.string.status_app, BuildConfig.VERSION_NAME),
            "",
            activity.getString(R.string.navionics_pairing),
        ).joinToString("\n")
    }
}
