package ca.dynamicsolutions.sonarbridge

import java.util.Locale

/**
 * SP200A T-Box UDP protocol (see sp200a-nmea-bridge-spec.md).
 * Offsets verified against SP200AProtocol_20221223.pdf and SonarPhone_V1.00.ino.
 */
object Sp200a {
    const val HOST = "192.168.1.1"
    const val PORT = 5000

    /** REDYFX reports this MAC until the official app establishes a master. */
    val SENTINEL_MAC = ByteArray(6) { 0x11 }

    /** Constant 29-byte FX discovery request; byte 19 = 0xB3 additive checksum. */
    val FX: ByteArray = ByteArray(29).also {
        it[0] = 'F'.code.toByte()
        it[1] = 'X'.code.toByte()
        it[2] = 0x15
        it[19] = 0xB3.toByte()
    }

    /**
     * FC request: settings + master MAC + additive 16-bit LE checksum of bytes 0..18.
     * MAC sits at 21..26, outside the checksummed range.
     */
    // byte 13: transducer frequency/beam selection (SonarPhony findings —
    // the value is echoed back at REDYFC byte 32)
    const val BEAM_200KHZ_20DEG = 0x08 // SP200/SP300 narrow
    const val BEAM_80KHZ_40DEG = 0x02  // SP200/SP300 wide
    const val BEAM_125KHZ_30DEG = 0x04 // SP100/T-POD single beam (experimental on SP200A)

    fun buildFc(
        mac: ByteArray,
        feet: Boolean = false,
        depthMin: Int = 0,
        depthMax: Int = 0, // 0 = auto range; whole units of `feet` flag, ≤240 ft
        beam: Int = BEAM_200KHZ_20DEG,
    ): ByteArray {
        require(mac.size == 6)
        val b = ByteArray(29)
        b[0] = 'F'.code.toByte()
        b[1] = 'C'.code.toByte()
        b[2] = 0x15
        b[4] = 0xF4.toByte()
        b[5] = 0x02
        b[6] = (depthMin and 0xFF).toByte()
        b[7] = ((depthMin ushr 8) and 0xFF).toByte()
        b[8] = (depthMax and 0xFF).toByte()
        b[9] = ((depthMax ushr 8) and 0xFF).toByte()
        b[11] = if (feet) 1 else 0
        b[13] = beam.toByte()
        var sum = 0
        for (i in 0 until 19) sum += b[i].toInt() and 0xFF
        b[19] = (sum and 0xFF).toByte()
        b[20] = ((sum ushr 8) and 0xFF).toByte()
        mac.copyInto(b, 21)
        return b
    }

    sealed class Reply {
        data class RedyFx(val serial: String, val mac: ByteArray) : Reply()
        data class RedyFc(
            val size: Int,
            val unitsFeet: Boolean, // byte 21: units actually in effect — authoritative
            val depth: Double,      // in the units of byte 21
            val tempC: Int,         // always °C regardless of units (per PDF)
            val vBatt: Double,
            val beam: Int,
            val rangeMin: Int,
            val rangeMax: Int,
            val echo: ByteArray,    // 758 intensity samples over 80 m max range
        ) : Reply()
        object Busy : Reply()
    }

    private fun u8(d: ByteArray, o: Int) = d[o].toInt() and 0xFF
    private fun u16le(d: ByteArray, o: Int) = u8(d, o) or (u8(d, o + 1) shl 8)
    private fun tag(d: ByteArray, off: Int, n: Int) = String(d, off, n, Charsets.US_ASCII)

    /** Parse by tag + fixed offsets, never by packet length (sizes vary across models). */
    fun parse(d: ByteArray, len: Int): Reply? {
        if (len >= 10 && tag(d, 6, 4) == "BUSY") return Reply.Busy
        if (len >= 32 && tag(d, 6, 6) == "REDYFX") {
            return Reply.RedyFx(
                serial = String(d, 16, 10, Charsets.US_ASCII),
                mac = d.copyOfRange(26, 32),
            )
        }
        if (len >= 38 && tag(d, 6, 6) == "REDYFC") {
            return Reply.RedyFc(
                size = len,
                unitsFeet = u8(d, 21) == 1,
                depth = u16le(d, 23) + u8(d, 25) / 100.0,
                tempC = u8(d, 26),
                vBatt = u8(d, 30) + u8(d, 31) / 100.0,
                beam = u8(d, 32),
                rangeMin = u16le(d, 16),
                rangeMax = u16le(d, 18),
                echo = d.copyOfRange(38, len),
            )
        }
        return null
    }

    fun macString(mac: ByteArray) =
        mac.joinToString(":") { String.format(Locale.US, "%02x", it) }
}
