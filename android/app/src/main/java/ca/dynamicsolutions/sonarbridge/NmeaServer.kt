package ca.dynamicsolutions.sonarbridge

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Loopback-only NMEA TCP pusher (what Navionics pairs to as 127.0.0.1:10110).
 * Each client gets a bounded queue + dedicated writer thread so one stalled
 * reader (e.g. Navionics backgrounded) can never block the sonar loop.
 */
class NmeaServer(
    private val port: Int,
    private val log: (String) -> Unit,
    private val onClientCount: (Int) -> Unit,
) {
    private class Client(val socket: Socket) {
        val queue = LinkedBlockingQueue<ByteArray>(32)
        @Volatile var dead = false
    }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start() {
        running = true
        thread(name = "nmea-accept", isDaemon = true) {
            val srv = ServerSocket()
            try {
                srv.reuseAddress = true
                srv.bind(InetSocketAddress("127.0.0.1", port))
                server = srv
                log("NMEA TCP listening on 127.0.0.1:$port")
                while (running) {
                    val s = try { srv.accept() } catch (_: IOException) { break }
                    val c = Client(s)
                    clients.add(c)
                    onClientCount(clients.size)
                    log("NMEA client connected: ${s.remoteSocketAddress} (${clients.size} total)")
                    thread(name = "nmea-writer", isDaemon = true) { writer(c) }
                }
            } catch (e: IOException) {
                log("NMEA server failed: $e")
            } finally {
                try { srv.close() } catch (_: IOException) {}
            }
        }
    }

    private fun writer(c: Client) {
        try {
            val out = c.socket.getOutputStream()
            while (!c.dead) {
                val payload = c.queue.poll(30, TimeUnit.SECONDS) ?: continue
                out.write(payload)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            c.dead = true
            clients.remove(c)
            try { c.socket.close() } catch (_: IOException) {}
            onClientCount(clients.size)
            log("NMEA client dropped (${clients.size} left)")
        }
    }

    fun broadcast(payload: ByteArray) {
        for (c in clients) {
            if (!c.queue.offer(payload)) {
                // ~32 backlogged payloads: reader is stuck — cut it loose
                c.dead = true
                try { c.socket.close() } catch (_: IOException) {}
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: IOException) {}
        for (c in clients) {
            c.dead = true
            try { c.socket.close() } catch (_: IOException) {}
        }
        clients.clear()
    }
}
