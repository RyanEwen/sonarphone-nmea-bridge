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

    fun load(prefs: SharedPreferences) {
        feet = prefs.getString("unit_dist", "ft") == "ft"
        fahrenheit = prefs.getString("unit_temp", "C") == "F"
    }

    fun depth(m: Double): String =
        if (feet) String.format(Locale.US, "%.1f ft", m * 3.28084)
        else String.format(Locale.US, "%.2f m", m)

    fun temp(c: Double): String =
        if (fahrenheit) String.format(Locale.US, "%.0f °F", c * 9 / 5 + 32)
        else String.format(Locale.US, "%.0f °C", c)
}
