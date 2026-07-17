package com.rewen.sonarbridge

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

/** Network + password config, kept off the main screen. Saves on leave. */
class SettingsActivity : Activity() {

    companion object {
        // radio ids by mode index; patterns must line up with BridgeService
        val MODE_PATTERNS = listOf("SonarPhone_", "T-BOX-", null)
    }

    private lateinit var group: RadioGroup
    private lateinit var ssid: EditText
    private lateinit var pass: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Bridge settings"
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
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
        group = RadioGroup(this)
        val labels = listOf(
            "Any SonarPhone (SSID starts with “SonarPhone_”)",
            "Any T-Box (SSID starts with “T-BOX-”)",
            "Specific SSID:",
        )
        labels.forEachIndexed { i, label ->
            group.addView(RadioButton(this).apply { id = i + 1; text = label })
        }
        root.addView(group)

        ssid = EditText(this).apply {
            hint = "SSID"
            setText(prefs.getString("ssid", "SonarPhone_65C0"))
        }
        root.addView(ssid)
        root.addView(
            note(
                "When the bridge starts, Android shows the matching networks " +
                    "in range — tap your sonar in that list. Nothing to choose here " +
                    "unless your unit's name doesn't match either prefix."
            )
        )

        root.addView(header("WiFi password"))
        pass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("pass", "12345678"))
        }
        root.addView(pass)
        root.addView(note("Factory default is 12345678."))

        setContentView(ScrollView(this).apply { addView(root) })

        val mode = prefs.getInt("mode", 0)
        group.check(mode + 1)
        ssid.isEnabled = MODE_PATTERNS[mode] == null
        group.setOnCheckedChangeListener { _, checkedId ->
            ssid.isEnabled = MODE_PATTERNS[checkedId - 1] == null
        }
    }

    override fun onPause() {
        super.onPause()
        getSharedPreferences("cfg", MODE_PRIVATE).edit()
            .putInt("mode", (group.checkedRadioButtonId - 1).coerceIn(0, 2))
            .putString("ssid", ssid.text.toString())
            .putString("pass", pass.text.toString())
            .apply()
    }
}
