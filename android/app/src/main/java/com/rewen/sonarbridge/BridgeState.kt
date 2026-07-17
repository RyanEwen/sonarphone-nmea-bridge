package com.rewen.sonarbridge

import kotlinx.coroutines.flow.MutableStateFlow

/** In-process observable state shared between BridgeService and MainActivity. */
object BridgeState {
    data class Snapshot(
        val running: Boolean = false, // service alive (any phase)
        val phase: String = "IDLE", // IDLE, WIFI_WAIT, DISCOVER, NEED_MASTER, RUN
        val ssid: String? = null,
        val serial: String? = null,
        val masterMac: String? = null,
        val depthM: Double? = null,
        val tempC: Double? = null,
        val vBatt: Double? = null,
        val frameCount: Long = 0,
        val frameSize: Int = 0,
        val unitsFeet: Boolean = false,
        val nmeaClients: Int = 0,
        val lastFrameWallMs: Long = 0,
    )

    val flow = MutableStateFlow(Snapshot())

    fun update(f: (Snapshot) -> Snapshot) {
        flow.value = f(flow.value)
    }
}
