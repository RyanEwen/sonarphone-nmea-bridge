package ca.dynamicsolutions.sonarbridge

import android.content.Context

/** Presentation of stable service state IDs. Protocol/log/state identities never become translated. */
object StatusText {
    fun phase(context: Context, phase: String): String = when (phase) {
        "IDLE" -> context.getString(R.string.phase_idle)
        "WIFI_WAIT" -> context.getString(R.string.phase_wifi_wait)
        "DISCOVER" -> context.getString(R.string.phase_discover)
        "NEED_MASTER" -> context.getString(R.string.phase_need_master)
        "RUN" -> context.getString(R.string.phase_run)
        "DEMO" -> context.getString(R.string.phase_demo)
        else -> phase
    }
}
