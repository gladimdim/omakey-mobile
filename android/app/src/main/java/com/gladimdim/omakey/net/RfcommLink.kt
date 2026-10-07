package com.gladimdim.omakey.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.gladimdim.omakey.protocol.ClientSession
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import com.gladimdim.omakey.protocol.Wire
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * omakeyd over Bluetooth RFCOMM, for when Wi-Fi can't reach the computer.
 * The packets are the UDP ones, each framed with a 2-byte big-endian
 * length (PROTOCOL.md, "Bluetooth"). The stream is reliable, so nothing
 * is resent; the heartbeat still runs, for ping and the stuck-key timeout.
 *
 * A stream write can block while the radio link is congested, so only this
 * link's own thread writes: [send] just wakes it. That also folds a burst
 * of touchpad moves into one packet.
 *
 * The caller must hold BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class RfcommLink(
    context: Context,
    host: HostRecord,
    private val address: String,
    phoneName: String,
    val keys: KeyState,
    private val listener: Link.Listener,
    private val claim: (Link) -> Boolean = { true },
) : Link {
    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val session = ClientSession(host, phoneName, keys)

    /** Wakes the writer thread: a key changed, a packet arrived, the stream broke, or we're stopping. */
    private val tick = Object()
    private var woken = false // guarded by tick

    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    // Writer thread only (and the session lock for packet building).
    private var out: OutputStream? = null
    private var sentVersion = -1L
    private var lastSend = 0L

    @Volatile private var lastHeard = 0L

    @Volatile override var features = 0
        private set

    override val transport get() = "Bluetooth"

    override fun start() {
        if (running) return
        running = true
        val gen = ++generation
        thread = Thread({ run(gen) }, "omakey-rfcomm").apply { start() }
    }

    /** Sends BYE and stops; blocks for up to about a second, so call it off the main thread. */
    override fun stop() {
        running = false
        wake()
        // The writer sends BYE on its way out; then abort any connect() or read().
        thread?.join(300)
        try { socket?.close() } catch (e: IOException) {}
        thread?.join(500)
        thread = null
    }

    override fun send() = wake()

    private fun wake() {
        synchronized(tick) {
            woken = true
            tick.notifyAll()
        }
    }

    /** Sleep until [wake] or [ms] pass; a wake that came first isn't missed. */
    private fun sleep(ms: Long) {
        synchronized(tick) {
            if (!woken) tick.wait(ms.coerceIn(1, 3000))
            woken = false
        }
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** Writer thread only. One write per frame. */
    private fun write(pkt: ByteArray) {
        val o = out ?: return
        val frame = ByteArray(2 + pkt.size)
        frame[0] = (pkt.size shr 8).toByte()
        frame[1] = pkt.size.toByte()
        System.arraycopy(pkt, 0, frame, 2, pkt.size)
        o.write(frame)
    }

    private var state: Link.State? = null

    private fun setState(s: Link.State, name: String? = null) {
        if (s != state) {
            state = s
            listener.onState(s, name)
        }
    }

    private fun run(gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val alive = { running && gen == generation }
        while (alive()) {
            setState(Link.State.CONNECTING)
            val sock = try {
                // Insecure: no Bluetooth pairing needed. Every packet is
                // AES-GCM authenticated with the key from the QR code.
                adapter?.getRemoteDevice(address)?.createInsecureRfcommSocketToServiceRecord(SERVICE_UUID)
            } catch (e: Exception) {
                null // Bluetooth off, bad address, or no permission
            }
            if (sock != null) {
                socket = sock
                try {
                    if (alive()) {
                        sock.connect()
                        talk(sock, alive)
                    }
                } catch (e: IOException) {
                    // Out of range, Bluetooth off, or omakeyd isn't listening.
                } catch (e: SecurityException) {
                } finally {
                    synchronized(session) { session.restart() }
                    out = null
                    try { sock.close() } catch (e: IOException) {}
                    if (socket === sock) socket = null
                }
                // Say so at once, so the Wi-Fi link can take over.
                if (alive()) setState(Link.State.CONNECTING)
            }
            if (alive()) sleep(RETRY_MS)
        }
    }

    /** One RFCOMM connection: handshake, then input and heartbeats until it breaks. */
    private fun talk(sock: BluetoothSocket, alive: () -> Boolean) {
        val input = DataInputStream(sock.inputStream)
        out = sock.outputStream
        synchronized(session) { session.restart() }
        val broken = AtomicBoolean(false)
        val reader = Thread({
            read(input)
            broken.set(true)
            wake()
        }, "omakey-rfcomm-rx").apply { start() }

        var nextHello = now()
        lastHeard = nextHello
        try {
            while (alive() && !broken.get()) {
                val now = now()
                if (now - lastHeard > LOST_MS) break
                val pkt: ByteArray?
                val wait: Long
                synchronized(session) {
                    if (!session.connected) {
                        pkt = if (now >= nextHello) session.helloPacket().also { nextHello = now + HELLO_MS } else null
                        wait = nextHello - now
                    } else if (keys.version != sentVersion || now - lastSend >= HEARTBEAT_MS) {
                        sentVersion = keys.version
                        lastSend = now
                        pkt = session.inputPacket(now.toInt())
                        wait = HEARTBEAT_MS
                    } else {
                        pkt = null
                        wait = HEARTBEAT_MS - (now - lastSend)
                    }
                }
                // Written outside the session lock: a slow write holds up nobody else.
                pkt?.let(::write)
                sleep(wait)
            }
            if (!running) synchronized(session) { session.byePacket() }?.let(::write)
        } finally {
            try { sock.close() } catch (e: IOException) {}
            reader.join(500)
        }
    }

    /** Reads framed packets until the stream breaks or another link has the computer. */
    private fun read(input: DataInputStream) {
        val buf = ByteArray(Wire.MAX_DATAGRAM)
        var lastPingReport = 0L
        try {
            while (running) {
                val n = input.readUnsignedShort()
                if (n == 0 || n > buf.size) return
                input.readFully(buf, 0, n)
                val t = now()
                var lost = false
                val r = synchronized(session) {
                    session.receive(buf, n, t.toInt()) { claim(this).also { lost = !it } }.also {
                        if (it is ClientSession.Result.Connected) sentVersion = -1 // push the held set
                    }
                }
                if (lost) return
                when (r) {
                    is ClientSession.Result.Connected -> {
                        features = r.features
                        lastHeard = t
                        setState(Link.State.CONNECTED, r.hostName)
                    }
                    is ClientSession.Result.Acked -> {
                        lastHeard = t
                        r.leds?.let(listener::onLeds)
                        if (t - lastPingReport >= 250) {
                            lastPingReport = t
                            listener.onPing(r.pingMs)
                        }
                    }
                    ClientSession.Result.Rejected -> {
                        lastHeard = t // keep asking on this connection, once a second
                        setState(Link.State.REJECTED)
                    }
                    null -> {}
                }
                wake()
            }
        } catch (e: IOException) {
        }
    }

    companion object {
        /** omakeyd's RFCOMM service, found over SDP. Must match bluetooth.rs. */
        val SERVICE_UUID: UUID = UUID.fromString("4f4b6579-6d61-4b79-9000-6f6d616b6579")
        const val HEARTBEAT_MS = 100L
        const val HELLO_MS = 1000L
        const val LOST_MS = 1500L
        const val RETRY_MS = 3000L
    }
}
