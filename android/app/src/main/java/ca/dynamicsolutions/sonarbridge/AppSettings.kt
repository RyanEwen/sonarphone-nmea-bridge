package ca.dynamicsolutions.sonarbridge

import android.content.Context
import android.content.SharedPreferences

/**
 * The one typed wrapper over the app's settings file (CONF-8). It owns every key
 * name and default; nothing else reads or writes `cfg` by key. The T-Box passphrase
 * is not here: it lives in its own file through [SonarPassphrase] (SEC-2).
 */
class AppSettings(context: Context) {
    /** The underlying preferences, for change listeners only. */
    val preferences: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Every user-editable setting, read and written as one snapshot. */
    data class Values(
        /** Index into [NETWORK_PATTERNS]: 0 SonarPhone_*, 1 T-BOX-*, 2 a specific network. */
        val networkMode: Int = 0,
        val ssid: String = DEFAULT_SSID,
        val demo: Boolean = false,
        val feet: Boolean = true,
        val fahrenheit: Boolean = false,
        /** "normal", "large" or "xl". */
        val textSize: String = "normal",
        /** T-Box beam code: "20" (200 kHz narrow), "40" (80 kHz wide) or "125" (125 kHz). */
        val beam: String = "20",
        /** Fixed device range in metres; 0 means auto. */
        val deviceRangeM: Float = 0f,
        /** Shallow alarm depth in metres; 0 means off. */
        val alarmM: Float = 0f,
        val gainPercent: Int = 100,
        val surfaceClarity: Int = 0,
        val noiseFilter: Int = 0,
        val displayStyle: Int = 0,
        /** 0 off, 1 fish, 2 fish with depth. On by default. */
        val fishMarkers: Int = 1,
        val keelOffsetM: Float = 0f,
        val tempOffsetC: Float = 0f,
    )

    /** Read every setting, clamping stored values to their valid ranges. */
    fun read(): Values = Values(
        networkMode = preferences.getInt(KEY_MODE, 0).coerceIn(0, NETWORK_PATTERNS.lastIndex),
        ssid = preferences.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID,
        demo = preferences.getBoolean(KEY_DEMO, false),
        feet = preferences.getString(KEY_UNIT_DIST, "ft") == "ft",
        fahrenheit = preferences.getString(KEY_UNIT_TEMP, "C") == "F",
        textSize = preferences.getString(KEY_TEXT_SIZE, "normal") ?: "normal",
        beam = preferences.getString(KEY_BEAM, "20") ?: "20",
        deviceRangeM = preferences.getFloat(KEY_DEVICE_RANGE_M, 0f),
        alarmM = preferences.getFloat(KEY_ALARM_M, 0f),
        gainPercent = preferences.getInt(KEY_GAIN_PCT, 100).coerceIn(50, 200),
        surfaceClarity = preferences.getInt(KEY_SURFACE_CLARITY, 0).coerceIn(0, 3),
        noiseFilter = preferences.getInt(KEY_NOISE_FILTER, 0).coerceIn(0, 3),
        displayStyle = preferences.getInt(KEY_DISPLAY_STYLE, 0).coerceIn(0, 1),
        fishMarkers = preferences.getInt(KEY_FISH_MARKERS, 1).coerceIn(0, 2),
        keelOffsetM = preferences.getFloat(KEY_KEEL_OFFSET_M, 0f),
        tempOffsetC = preferences.getFloat(KEY_TEMP_OFFSET_C, 0f),
    )

    /** Write every setting in one edit, keeping the stored formats older versions used. */
    fun write(values: Values) {
        preferences.edit()
            .putInt(KEY_MODE, values.networkMode)
            .putString(KEY_SSID, values.ssid)
            .putBoolean(KEY_DEMO, values.demo)
            .putString(KEY_UNIT_DIST, if (values.feet) "ft" else "m")
            .putString(KEY_UNIT_TEMP, if (values.fahrenheit) "F" else "C")
            .putString(KEY_TEXT_SIZE, values.textSize)
            .putString(KEY_BEAM, values.beam)
            .putFloat(KEY_DEVICE_RANGE_M, values.deviceRangeM)
            .putFloat(KEY_ALARM_M, values.alarmM)
            .putInt(KEY_GAIN_PCT, values.gainPercent)
            .putInt(KEY_SURFACE_CLARITY, values.surfaceClarity)
            .putInt(KEY_NOISE_FILTER, values.noiseFilter)
            .putInt(KEY_DISPLAY_STYLE, values.displayStyle)
            .putInt(KEY_FISH_MARKERS, values.fishMarkers)
            .putFloat(KEY_KEEL_OFFSET_M, values.keelOffsetM)
            .putFloat(KEY_TEMP_OFFSET_C, values.tempOffsetC)
            .apply()
    }

    /** SSID prefix for a network search started without explicit extras (ADB driving). */
    fun searchPattern(): String = preferences.getString(KEY_PATTERN, NETWORK_PATTERNS[0]) ?: NETWORK_PATTERNS[0]!!

    companion object {
        /** The settings file; backed up (see res/xml/backup_rules.xml). */
        const val FILE = "cfg"

        /** Network choices in radio order; null means a specific SSID. BridgeService matches on these. */
        val NETWORK_PATTERNS = listOf("SonarPhone_", "T-BOX-", null)

        const val DEFAULT_SSID = "SonarPhone_65C0"

        /** The app-language choice on API 32 and lower; owned by [AppLanguage], which validates it. */
        const val KEY_UI_LANGUAGE = "ui_language"

        private const val KEY_MODE = "mode"
        private const val KEY_SSID = "ssid"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_DEMO = "demo"
        private const val KEY_UNIT_DIST = "unit_dist"
        private const val KEY_UNIT_TEMP = "unit_temp"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_BEAM = "beam"
        private const val KEY_DEVICE_RANGE_M = "dev_range_m"
        private const val KEY_ALARM_M = "alarm_m"
        private const val KEY_GAIN_PCT = "gain_pct"
        private const val KEY_SURFACE_CLARITY = "surface_clarity"
        private const val KEY_NOISE_FILTER = "noise_filter"
        private const val KEY_DISPLAY_STYLE = "display_style"
        private const val KEY_FISH_MARKERS = "fish_markers"
        private const val KEY_KEEL_OFFSET_M = "keel_offset_m"
        private const val KEY_TEMP_OFFSET_C = "temp_offset_c"
    }
}
