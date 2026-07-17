package com.rewen.sonarbridge

import java.util.Locale

object Nmea {
    private fun sentence(body: String): String {
        var cs = 0
        for (c in body) cs = cs xor c.code
        return "\$$body*${String.format(Locale.US, "%02X", cs)}\r\n"
    }

    /** DPT + DBT + MTW; depth in meters, temp in °C. Locale pinned — NMEA wants '.' decimals. */
    fun forDepthTemp(depthM: Double, tempC: Double): String {
        val ft = depthM * 3.28084
        val fa = depthM * 0.546807
        return sentence(String.format(Locale.US, "SDDPT,%.2f,0.0", depthM)) +
            sentence(String.format(Locale.US, "SDDBT,%.1f,f,%.2f,M,%.1f,F", ft, depthM, fa)) +
            sentence(String.format(Locale.US, "YXMTW,%.1f,C", tempC))
    }
}
