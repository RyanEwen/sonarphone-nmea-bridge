package ca.dynamicsolutions.sonarbridge

import java.util.Locale

/**
 * Display units for the app UI (status headline, sonar overlay, notification).
 * NMEA output is unaffected — DPT/DBT/MTW carry protocol-mandated units and
 * Navionics converts on its side.
 */
object Units {
    @Volatile var regionalLocale: Locale = Locale.US

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

    /** Apply the display-related settings; [AppSettings] owns the keys, defaults and ranges. */
    fun load(settings: AppSettings.Values) {
        feet = settings.feet
        fahrenheit = settings.fahrenheit
        sonarScale = when (settings.textSize) {
            "large" -> 1.25f
            "xl" -> 1.5f
            else -> 1.0f
        }
        gain = settings.gainPercent / 100f
        surfaceClarity = settings.surfaceClarity
        noiseFilter = settings.noiseFilter
        keelOffsetM = settings.keelOffsetM
        tempOffsetC = settings.tempOffsetC
        displayStyle = settings.displayStyle
        fishMarkers = settings.fishMarkers
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
        if (!feet) return String.format(regionalLocale, "%.2f", m) to " m"
        val totalFeet = m * 3.28084
        var ft = totalFeet.toInt()
        var inches = Math.round((totalFeet - ft) * 12).toInt()
        if (inches == 12) {
            ft++
            inches = 0
        }
        return String.format(regionalLocale, "%d' %d\"", ft, inches) to ""
    }

    fun temp(c: Double): String = if (fahrenheit) {
        String.format(regionalLocale, "%.0f °F", c * 9 / 5 + 32)
    } else {
        String.format(regionalLocale, "%.0f °C", c)
    }
}
