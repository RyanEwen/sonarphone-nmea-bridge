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

    /** Software gain applied to echo intensity before display (0.5–2.0). */
    @Volatile var gain = 1.0f

    /** Surface clarity: fade the top clutter band. 0 off, 1 low, 2 med, 3 high. */
    @Volatile var surfaceClarity = 0

    /** Noise filter: raise the echo noise floor. 0 off, 1 low, 2 med, 3 high. */
    @Volatile var noiseFilter = 0

    /** Keel offset (m): added to raw depth so readings are below the keel. */
    @Volatile var keelOffsetM = 0f

    /** Temperature offset (°C): added to raw temperature. */
    @Volatile var tempOffsetC = 0f

    fun load(prefs: SharedPreferences) {
        feet = prefs.getString("unit_dist", "ft") == "ft"
        fahrenheit = prefs.getString("unit_temp", "C") == "F"
        sonarScale = when (prefs.getString("text_size", "normal")) {
            "large" -> 1.25f
            "xl" -> 1.5f
            else -> 1.0f
        }
        gain = prefs.getInt("gain_pct", 100).coerceIn(50, 200) / 100f
        surfaceClarity = prefs.getInt("surface_clarity", 0).coerceIn(0, 3)
        noiseFilter = prefs.getInt("noise_filter", 0).coerceIn(0, 3)
        keelOffsetM = prefs.getFloat("keel_offset_m", 0f)
        tempOffsetC = prefs.getFloat("temp_offset_c", 0f)
    }

    fun depth(m: Double): String =
        if (feet) String.format(Locale.US, "%.1f ft", m * 3.28084)
        else String.format(Locale.US, "%.2f m", m)

    fun temp(c: Double): String =
        if (fahrenheit) String.format(Locale.US, "%.0f °F", c * 9 / 5 + 32)
        else String.format(Locale.US, "%.0f °C", c)
}
