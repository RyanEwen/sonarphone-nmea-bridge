package com.rewen.sonarbridge

import android.content.SharedPreferences
import java.util.Locale

/**
 * Display units for the app UI (status headline, sonar overlay, notification).
 * NMEA output is unaffected — DPT/DBT/MTW carry protocol-mandated units and
 * Navionics converts on its side.
 */
object Units {
    @Volatile var feet = true
    @Volatile var fahrenheit = false

    /** Sonar-view text/control scale: 1.0 normal, 1.25 large, 1.5 XL. */
    @Volatile var sonarScale = 1.0f

    fun load(prefs: SharedPreferences) {
        feet = prefs.getString("unit_dist", "ft") == "ft"
        fahrenheit = prefs.getString("unit_temp", "C") == "F"
        sonarScale = when (prefs.getString("text_size", "normal")) {
            "large" -> 1.25f
            "xl" -> 1.5f
            else -> 1.0f
        }
    }

    fun depth(m: Double): String =
        if (feet) String.format(Locale.US, "%.1f ft", m * 3.28084)
        else String.format(Locale.US, "%.2f m", m)

    fun temp(c: Double): String =
        if (fahrenheit) String.format(Locale.US, "%.0f °F", c * 9 / 5 + 32)
        else String.format(Locale.US, "%.0f °C", c)
}
