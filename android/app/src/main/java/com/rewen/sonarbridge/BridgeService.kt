package com.rewen.sonarbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class BridgeService : Service() {

    companion object {
        const val TAG = "SonarBridge"
        const val ACTION_START = "com.rewen.sonarbridge.START"
        const val ACTION_STOP = "com.rewen.sonarbridge.STOP"
        const val NMEA_PORT = 10110
        private const val CHANNEL = "bridge"
        private const val NOTIF_ID = 1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cm: ConnectivityManager
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var nmea: NmeaServer? = null
    private var sonarJob: Job? = null
    private var demoJob: Job? = null
    private var emitJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var rawLog: FileOutputStream? = null
    private var udpFallback: DatagramSocket? = null
    private var udpFallbackPort = 0

    /** elapsedRealtime of the last parsed REDYFC; drives NMEA freshness/staleness. */
    @Volatile private var lastDataAt = 0L

    // shallow-water alarm (meters; 0 = off), tone throttled to one per 4 s
    private var alarmM = 0.0
    private var lastAlarmAt = 0L
    private var toneGen: ToneGenerator? = null

    private fun checkShallowAlarm(depthM: Double) {
        if (alarmM <= 0.0 || depthM <= 0.1 || depthM >= alarmM) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastAlarmAt < 4_000) return
        lastAlarmAt = now
        runCatching {
            (toneGen ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also { toneGen = it })
                .startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 700)
        }
        log(String.format(Locale.US, "SHALLOW ALARM: %.2f m < %.2f m", depthM, alarmM))
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bridge", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log("STOP requested")
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        // exact SSID wins if given; otherwise a prefix pattern — the system
        // connect dialog lists all matching APs in range (the "picker")
        val ssid = intent?.getStringExtra("ssid")
        val pattern = if (ssid != null) null
            else intent?.getStringExtra("pattern") ?: prefs.getString("pattern", "SonarPhone_")!!
        val pass = intent?.getStringExtra("pass") ?: prefs.getString("pass", "12345678")!!
        val logRaw = intent?.getStringExtra("lograw") == "true"
        val demo = (intent?.getStringExtra("demo")
            ?: prefs.getBoolean("demo", false).toString()) == "true"
        udpFallbackPort = intent?.getStringExtra("udp")?.toIntOrNull() ?: 0

        log("START ssid=$ssid pattern=$pattern demo=$demo lograw=$logRaw udpFallback=$udpFallbackPort")
        startForeground(
            NOTIF_ID,
            buildNotification("starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        stopBridge() // restart cleanly if already running
        startBridge(ssid, pattern, pass, logRaw, demo)
        return START_STICKY
    }

    // ---------------------------------------------------------------- bridge

    private fun startBridge(ssid: String?, pattern: String?, pass: String, logRaw: Boolean, demo: Boolean) {
        val label = if (demo) "demo data" else ssid ?: "$pattern*"
        BridgeState.update {
            BridgeState.Snapshot(
                running = true,
                phase = if (demo) "DEMO" else "WIFI_WAIT",
                ssid = label,
            )
        }

        alarmM = getSharedPreferences("cfg", MODE_PRIVATE).getFloat("alarm_m", 0f).toDouble()
        if (alarmM > 0) log(String.format(Locale.US, "shallow alarm armed at %.2f m", alarmM))

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:bridge")
            .apply { acquire() }

        nmea = NmeaServer(NMEA_PORT, ::log) { n ->
            BridgeState.update { it.copy(nmeaClients = n) }
        }.also { it.start() }

        if (udpFallbackPort > 0) {
            udpFallback = DatagramSocket()
            log("UDP fallback enabled -> 127.0.0.1:$udpFallbackPort")
        }

        if (logRaw) {
            val dir = getExternalFilesDir(null) ?: filesDir
            val f = File(dir, "frames-${System.currentTimeMillis()}.bin")
            rawLog = FileOutputStream(f)
            log("raw frame log: ${f.absolutePath}")
        }

        emitJob = scope.launch { nmeaEmitLoop() }
        if (demo) {
            demoJob = scope.launch { demoLoop() }
        } else {
            requestWifi(ssid, pattern, pass)
        }
    }

    private fun stopBridge() {
        sonarJob?.cancel(); sonarJob = null
        demoJob?.cancel(); demoJob = null
        emitJob?.cancel(); emitJob = null
        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        netCallback = null
        nmea?.stop(); nmea = null
        udpFallback?.close(); udpFallback = null
        rawLog?.let { runCatching { it.close() } }; rawLog = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
    }

    // ---------------------------------------------------------------- wifi

    private fun requestWifi(ssid: String?, pattern: String?, pass: String) {
        val specBuilder = WifiNetworkSpecifier.Builder()
        if (ssid != null) {
            specBuilder.setSsid(ssid)
        } else {
            // system dialog lists every AP whose SSID starts with the prefix
            specBuilder.setSsidPattern(
                PatternMatcher(pattern ?: "SonarPhone_", PatternMatcher.PATTERN_PREFIX)
            )
        }
        if (pass.isNotBlank()) specBuilder.setWpa2Passphrase(pass)
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                log("WIFI onAvailable: $network (local-only; default route untouched)")
                sonarJob?.cancel()
                sonarJob = scope.launch { sonarLoop(network) }
            }

            override fun onLost(network: Network) {
                log("WIFI onLost — waiting for AP to return")
                sonarJob?.cancel(); sonarJob = null
                setPhase("WIFI_WAIT")
            }

            override fun onUnavailable() {
                // user declined the connect dialog, or connect failed — retry
                log("WIFI onUnavailable — retrying request in 5 s")
                setPhase("WIFI_WAIT")
                scope.launch {
                    delay(5_000)
                    if (isActive && netCallback != null) {
                        netCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                        requestWifi(ssid, pattern, pass)
                    }
                }
            }
        }
        netCallback = cb
        log("requesting WiFi ${ssid?.let { "\"$it\"" } ?: "matching \"$pattern*\""} via WifiNetworkSpecifier (picker/approval dialog may appear)")
        cm.requestNetwork(request, cb)
    }

    // ---------------------------------------------------------------- sonar

    private suspend fun sonarLoop(network: Network) {
        val sock = DatagramSocket()
        try {
            network.bindSocket(sock) // critical: pin UDP to the T-Box network, not default route
            sock.soTimeout = 2_000
            val dest = InetSocketAddress(InetAddress.getByName(Sp200a.HOST), Sp200a.PORT)
            val buf = ByteArray(4096)
            var needMasterLogged = false

            while (scope.isActive && sonarJob?.isActive == true) {
                // ---- DISCOVER: FX @1 Hz until REDYFX with a real master MAC
                setPhase("DISCOVER")
                var mac: ByteArray? = null
                while (mac == null && sonarJob?.isActive == true) {
                    sock.send(DatagramPacket(Sp200a.FX, Sp200a.FX.size, dest))
                    val pkt = DatagramPacket(buf, buf.size)
                    val reply = try {
                        sock.receive(pkt)
                        Sp200a.parse(buf, pkt.length)
                    } catch (_: SocketTimeoutException) {
                        null
                    }
                    when (reply) {
                        is Sp200a.Reply.RedyFx -> {
                            val macS = Sp200a.macString(reply.mac)
                            log("REDYFX serial=${reply.serial} masterMac=$macS")
                            if (reply.mac.contentEquals(Sp200a.SENTINEL_MAC)) {
                                setPhase("NEED_MASTER")
                                if (!needMasterLogged) {
                                    log("master MAC is 11:11… sentinel — factory-reset unit; run the official SonarPhone app once to establish a master, leaving bridge polling")
                                    needMasterLogged = true
                                }
                            } else {
                                mac = reply.mac
                                BridgeState.update {
                                    it.copy(serial = reply.serial.trim(), masterMac = macS)
                                }
                            }
                        }
                        is Sp200a.Reply.Busy -> log("BUSY during discover")
                        else -> {}
                    }
                    delay(1_000)
                }
                mac ?: return

                // ---- RUN: FC every 10 s, parse stream, watchdog on 15 s silence
                setPhase("RUN")
                val cfg = getSharedPreferences("cfg", MODE_PRIVATE)
                val beam = when (cfg.getString("beam", "20")) {
                    "40" -> Sp200a.BEAM_80KHZ_40DEG
                    "125" -> Sp200a.BEAM_125KHZ_30DEG
                    else -> Sp200a.BEAM_200KHZ_20DEG
                }
                // device range in whole meters (we request meters); 0 = auto
                val devRangeM = cfg.getFloat("dev_range_m", 0f).toInt()
                log(
                    "FC settings: meters, range=${if (devRangeM > 0) "0-${devRangeM}m" else "auto"}, " +
                        "beam=0x%02x".format(beam)
                )
                val fc = Sp200a.buildFc(mac, depthMax = devRangeM, beam = beam)
                var lastFc = 0L
                var lastFrame = SystemClock.elapsedRealtime()
                var lastFrameLog = 0L
                var unitsWarned = false

                while (sonarJob?.isActive == true) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastFc >= 10_000) {
                        sock.send(DatagramPacket(fc, fc.size, dest))
                        lastFc = now
                        log("FC sent (meters, auto-range, 20deg)")
                    }
                    if (now - lastFrame > 15_000) {
                        log("REDYFC silent >15 s — back to DISCOVER")
                        break
                    }
                    val pkt = DatagramPacket(buf, buf.size)
                    val reply = try {
                        sock.receive(pkt)
                        Sp200a.parse(buf, pkt.length)
                    } catch (_: SocketTimeoutException) {
                        continue // FC cadence + watchdog re-checked at loop top
                    }
                    when (reply) {
                        is Sp200a.Reply.RedyFc -> {
                            val t = SystemClock.elapsedRealtime()
                            lastFrame = t
                            lastDataAt = t
                            // trust the units the frame REPORTS, not what we requested
                            val rawM = if (reply.unitsFeet) reply.depth * 0.3048 else reply.depth
                            val depthM = rawM + Units.keelOffsetM // keel offset at source
                            if (reply.unitsFeet && !unitsWarned) {
                                log("WARNING: T-Box reports FEET despite meters request — converting per-frame")
                                unitsWarned = true
                            }
                            BridgeState.update {
                                it.copy(
                                    depthM = depthM,
                                    tempC = reply.tempC.toDouble() + Units.tempOffsetC,
                                    vBatt = reply.vBatt,
                                    frameCount = it.frameCount + 1,
                                    frameSize = reply.size,
                                    unitsFeet = reply.unitsFeet,
                                    lastFrameWallMs = System.currentTimeMillis(),
                                )
                            }
                            EchoHistory.push(reply.echo, depthM)
                            checkShallowAlarm(depthM)
                            rawLog?.let { out ->
                                val hdr = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
                                    .putLong(System.currentTimeMillis())
                                    .putShort(pkt.length.toShort())
                                out.write(hdr.array())
                                out.write(buf, 0, pkt.length)
                            }
                            if (t - lastFrameLog >= 1_000) {
                                lastFrameLog = t
                                val s = BridgeState.flow.value
                                log(
                                    String.format(
                                        Locale.US,
                                        "FRAME #%d size=%dB depth=%.2fm temp=%.0fC vbatt=%.2fV range=%d-%d beam=0x%02x echo=%dpts",
                                        s.frameCount, reply.size, depthM, reply.tempC.toDouble(),
                                        reply.vBatt, reply.rangeMin, reply.rangeMax, reply.beam,
                                        reply.echo.size,
                                    )
                                )
                            }
                        }
                        is Sp200a.Reply.Busy -> log("BUSY during run")
                        is Sp200a.Reply.RedyFx -> {} // stray discover reply, ignore
                        null -> log("unknown packet ${pkt.length}B")
                    }
                }
            }
        } catch (e: Exception) {
            if (sonarJob?.isActive == true) log("sonar loop error: $e")
        } finally {
            sock.close()
        }
    }

    // ---------------------------------------------------------------- demo

    /**
     * Synthesizes a plausible sonar feed (wandering bottom, surface clutter,
     * the occasional fish) so the NMEA path and the chart view can be
     * exercised — including Navionics pairing — without the T-Box.
     */
    private suspend fun demoLoop() {
        log("demo data generator running (no sonar hardware)")
        BridgeState.update { it.copy(serial = "DEMO", masterMac = "de:mo:de:mo:de:mo") }
        val rnd = Random(System.currentTimeMillis())
        class Fish(var depth: Double, var life: Int, val strength: Int)
        val fish = mutableListOf<Fish>()
        var t = 0.0
        var frames = 0L
        while (scope.isActive && demoJob?.isActive == true) {
            delay(200)
            t += 0.2
            val depth = 8.0 + 4.0 * sin(t / 15) + rnd.nextDouble(-0.05, 0.05) + Units.keelOffsetM
            if (rnd.nextInt(40) == 0 && depth > 3.0) {
                fish += Fish(rnd.nextDouble(1.5, depth - 1.0), rnd.nextInt(15, 60), rnd.nextInt(150, 240))
            }
            fish.forEach { it.depth += rnd.nextDouble(-0.08, 0.08); it.life-- }
            fish.removeAll { it.life <= 0 || it.depth >= depth || it.depth < 0.5 }

            val col = ByteArray(EchoHistory.SAMPLES)
            for (i in col.indices) col[i] = rnd.nextInt(0, 26).toByte()
            for (i in 0 until 6) col[i] = (110 + rnd.nextInt(70)).toByte() // surface clutter
            val bottom = (depth * EchoHistory.SAMPLES_PER_M).toInt()
            // hardness cycles so the white-line band visibly varies in demo:
            // hard = bright return with a long tail, soft = dim and fast-fading
            val hard = 0.5 + 0.5 * sin(t / 8)
            val peak = 195 + 60 * hard
            val decay = 8.5 - 6.0 * hard
            val bandEnd = minOf(bottom + 45, col.size)
            for (i in bottom until bandEnd) {
                col[i] = (peak - (i - bottom) * decay + rnd.nextInt(12))
                    .toInt().coerceIn(24, 255).toByte()
            }
            // sub-bottom tail: hard bottoms keep echoing far down (thick
            // glow), soft ones die out fast (thin shell) — the Deeper cue
            var tail = peak - 45 * decay
            var i = bandEnd
            while (i < col.size && tail > 18) {
                val v = (tail + rnd.nextInt(14)).toInt()
                if (v > (col[i].toInt() and 0xFF)) col[i] = v.coerceAtMost(255).toByte()
                tail -= 0.35
                i++
            }
            // hard bottoms bounce a second return at ~2x depth (Deeper cue)
            val second = bottom * 2
            if (hard > 0.55 && second < col.size) {
                for (i in second until minOf(second + 22, col.size)) {
                    val v = (peak - 130 - (i - second) * 5 + rnd.nextInt(10)).toInt()
                    if (v > (col[i].toInt() and 0xFF)) col[i] = v.coerceIn(0, 255).toByte()
                }
            }
            for (f in fish) {
                val fi = (f.depth * EchoHistory.SAMPLES_PER_M).toInt()
                for (j in -3..3) {
                    val k = fi + j
                    if (k in col.indices) {
                        val v = f.strength - abs(j) * 35
                        if (v > (col[k].toInt() and 0xFF)) col[k] = v.toByte()
                    }
                }
            }

            lastDataAt = SystemClock.elapsedRealtime()
            frames++
            BridgeState.update {
                it.copy(
                    depthM = depth,
                    tempC = 18.0 + sin(t / 90) + Units.tempOffsetC,
                    vBatt = 12.4 + rnd.nextDouble(-0.03, 0.03),
                    frameCount = frames,
                    frameSize = 796,
                    unitsFeet = false,
                    lastFrameWallMs = System.currentTimeMillis(),
                )
            }
            EchoHistory.push(col, depth)
            checkShallowAlarm(depth)
        }
    }

    // ---------------------------------------------------------------- nmea

    /**
     * Decoupled from the recv loop so keepalive survives stream gaps:
     * fresh data -> emit at most ~1 Hz; no fresh data -> re-emit last value
     * every 4 s (Navionics clears depth on a >5 s quiet feed); data older
     * than 30 s -> stop emitting (truly stale).
     */
    private suspend fun nmeaEmitLoop() {
        var lastEmit = 0L
        var emits = 0L
        while (scope.isActive && emitJob?.isActive == true) {
            delay(250)
            val s = BridgeState.flow.value
            val depth = s.depthM ?: continue
            val now = SystemClock.elapsedRealtime()
            val dataAt = lastDataAt
            if (now - dataAt > 30_000) continue
            val fresh = dataAt > lastEmit
            if ((fresh && now - lastEmit >= 1_000) || now - lastEmit >= 4_000) {
                val payload = Nmea.forDepthTemp(depth, s.tempC ?: 0.0)
                    .toByteArray(Charsets.US_ASCII)
                nmea?.broadcast(payload)
                udpFallback?.let {
                    runCatching {
                        it.send(
                            DatagramPacket(
                                payload, payload.size,
                                InetAddress.getLoopbackAddress(), udpFallbackPort,
                            )
                        )
                    }
                }
                lastEmit = now
                emits++
                if (emits % 30 == 1L) {
                    log("NMEA emit #$emits: " +
                        Nmea.forDepthTemp(depth, s.tempC ?: 0.0).replace("\r\n", " "))
                }
                updateNotification(s)
            }
        }
    }

    // ---------------------------------------------------------------- misc

    private fun setPhase(phase: String) {
        if (BridgeState.flow.value.phase != phase) {
            log("STATE $phase")
            BridgeState.update { it.copy(phase = phase) }
            updateNotification(BridgeState.flow.value)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("SP200A Bridge")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(s: BridgeState.Snapshot) {
        val text = if (s.depthM != null) {
            String.format(
                Locale.US, "%s · %s · %s · %.1f V",
                s.phase, Units.depth(s.depthM), Units.temp(s.tempC ?: 0.0), s.vBatt ?: 0.0,
            )
        } else {
            s.phase
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotification(text))
    }

    private fun log(msg: String) = Log.i(TAG, msg)

    override fun onDestroy() {
        log("service destroyed")
        toneGen?.release(); toneGen = null
        stopBridge()
        BridgeState.update { BridgeState.Snapshot() }
        scope.cancel()
        super.onDestroy()
    }
}
