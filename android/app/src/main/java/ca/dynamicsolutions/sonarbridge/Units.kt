package ca.dynamicsolutions.sonarbridge

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

    /** Display style: 0 = modern (Deeper-like), 1 = classic (original navy). */
    @Volatile var displayStyle = 0

    /** Fish markers: 0 off, 1 symbols, 2 symbols + depth. */
    @Volatile var fishMarkers = 0

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
        displayStyle = prefs.getInt("display_style", 0).coerceIn(0, 1)
        fishMarkers = prefs.getInt("fish_markers", 1).coerceIn(0, 2) // on (Fish) by default
    }

    /** Full depth readout: feet-and-inches (12' 4") or metres (12.34 m). */
    fun depth(m: Double): String {
        val (main, suffix) = depthParts(m)
        return main + suffix
    }

    /**
     * Split for readouts that draw the value big and the unit small: feet mode
     * returns ("12' 4\"", "") — the ' and " ARE the unit; metres returns
     * ("12.34", " m").
     */
    fun depthParts(m: Double): Pair<String, String> {
        if (!feet) return String.format(Locale.US, "%.2f", m) to " m"
        val totalFeet = m * 3.28084
        var ft = totalFeet.toInt()
        var inches = Math.round((totalFeet - ft) * 12).toInt()
        if (inches == 12) { ft++; inches = 0 }
        return "$ft' $inches\"" to ""
    }

    fun temp(c: Double): String =
        if (fahrenheit) String.format(Locale.US, "%.0f °F", c * 9 / 5 + 32)
        else String.format(Locale.US, "%.0f °C", c)
}
