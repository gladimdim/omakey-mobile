package com.gladimdim.omakey.net

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.gladimdim.omakey.protocol.ClientSession
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import com.gladimdim.omakey.protocol.Wire
import java.io.IOException
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.CopyOnWriteArrayList

/**
 * omakeyd over UDP: the socket and the timing rules of PROTOCOL.md. A key
 * change is sent by [send] on the touch thread itself, so it leaves the
 * phone without waiting for another thread to wake up. A dedicated network
 * thread does the rest: handshake, resends, heartbeats and reading ACKs.
 *
 * Everything touching [session] holds its lock, so packet counters go out
 * in the order they were assigned. Times are Long milliseconds; only the
 * wire's client_time_ms is truncated to 32 bits.
 */
class KeyboardLink(
    host: HostRecord,
    phoneName: String,
    val keys: KeyState,
    private val listener: Link.Listener,
    /** Asked before a new session is taken; false when another link has the computer. */
    private val claim: (Link) -> Boolean = { true },
) : WifiLink {
    private val session = ClientSession(host, phoneName, keys)
    private val candidates = CopyOnWriteArrayList<InetSocketAddress>()
    @Volatile private var running = false
    /** Bumped by every [start]: a thread from an earlier start that outlived [stop] sees it and quits. */
    @Volatile private var generation = 0
    @Volatile private var selector: Selector? = null
    @Volatile private var channel: DatagramChannel? = null
    private var thread: Thread? = null

    // Guarded by the session lock: what was sent last, and when.
    private var sentVersion = -1L
    private var lastSend = 0L

    /** A new candidate arrived: say HELLO to it now, not at the next retry. */
    @Volatile private var helloNow = false

    @Volatile override var features = 0
        private set

    override val transport get() = "Wi-Fi"

    @Volatile override var btAddress: String? = null
        private set

    @Volatile override var peer: InetSocketAddress? = null
        private set

    init {
        for (a in host.addresses) addCandidate(InetSocketAddress(a, host.port))
    }

    override fun addCandidate(addr: InetSocketAddress) {
        if (candidates.none { it == addr }) {
            candidates.add(0, addr)
            helloNow = true
        }
        wake()
    }

    override fun start() {
        if (running) return
        running = true
        val gen = ++generation
        thread = Thread({ run(gen) }, "omakey-net").apply { start() }
    }

    /** Sends BYE and stops. The server then releases every key we held. */
    override fun stop() {
        running = false
        wake()
        thread?.join(500)
        thread = null
    }

    /**
     * A key changed: send the new state right now, from the calling thread.
     * The UDP send never blocks. On the main thread this needs network
     * access allowed in StrictMode (KeyboardActivity does that).
     */
    override fun send() {
        val ch = channel
        if (ch != null) synchronized(session) { if (session.connected) sendInput(ch, now()) }
        // The network thread re-arms its resend timer for the new events.
        wake()
    }

    /** Wake the network thread. Cheap; safe from any thread. */
    fun wake() {
        selector?.wakeup()
    }

    private fun now(): Long = SystemClock.elapsedRealtime()

    /** Call with the session lock held. */
    private fun sendInput(ch: DatagramChannel, now: Long) {
        val v = keys.version
        val pkt = session.inputPacket(now.toInt()) ?: return
        sentVersion = v
        lastSend = now
        send(ch, pkt)
    }

    private fun run(gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val ch = try {
            DatagramChannel.open().apply {
                configureBlocking(false)
                // DSCP EF: Wi-Fi WMM puts it in the voice queue, the lowest-latency one.
                try { setOption(StandardSocketOptions.IP_TOS, 0xB8) } catch (e: Exception) {}
                bind(null)
            }
        } catch (e: IOException) {
            Log.e(TAG, "can't open socket", e)
            return
        }
        val sel = Selector.open()
        ch.register(sel, SelectionKey.OP_READ)
        selector = sel
        channel = ch
        val rx = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
        val rxArray = rx.array()
        // A restarted link begins a fresh handshake; the old session got BYE.
        synchronized(session) { session.restart() }
        peer = null
        helloNow = true

        var state: Link.State? = null
        fun setState(s: Link.State, name: String? = null) {
            if (s != state) {
                state = s
                listener.onState(s, name)
            }
        }
        setState(Link.State.CONNECTING)

        var nextHello = now()
        var helloInterval = HELLO_FIRST_MS
        var lastHeard = 0L
        var lastPingReport = 0L
        // Smoothed round trip from ACKs. A lost event is resent once an ACK
        // is clearly overdue, instead of after a fixed RESEND_MS.
        var srtt = RESEND_MS.toFloat()
        var resendMs = RESEND_MS

        val alive = { running && gen == generation }
        try {
            while (alive()) {
                val now = now()
                var timeout: Long
                if (!synchronized(session) { session.connected }) {
                    if (helloNow || now >= nextHello) {
                        helloNow = false
                        val pkt = ByteBuffer.wrap(synchronized(session) { session.helloPacket() })
                        for (c in candidates) {
                            pkt.rewind()
                            try { ch.send(pkt, c) } catch (e: IOException) { /* unreachable now; retry */ }
                        }
                        // Retry quickly at first, in case the first HELLO was lost:
                        // after 50 ms, then 100, 200, 250, 250…
                        nextHello = now + if (state == Link.State.REJECTED) REJECTED_HELLO_MS else helloInterval
                        helloInterval = minOf(helloInterval * 2, HELLO_MS)
                    }
                    timeout = nextHello - now
                } else {
                    if (now - lastHeard > LOST_MS) {
                        // No ACK for a while: the computer moved, slept or restarted.
                        synchronized(session) { session.restart() }
                        peer = null
                        helloInterval = HELLO_FIRST_MS
                        nextHello = now
                        setState(Link.State.CONNECTING)
                        continue
                    }
                    timeout = synchronized(session) {
                        val since = now - lastSend
                        if (keys.version != sentVersion || (keys.hasUnacked && since >= resendMs) || since >= HEARTBEAT_MS) {
                            sendInput(ch, now)
                        }
                        (if (keys.hasUnacked) resendMs else HEARTBEAT_MS) - (now() - lastSend)
                    }
                }

                sel.select(timeout.coerceIn(1, 1000))
                sel.selectedKeys().clear()

                while (true) {
                    rx.clear()
                    val from = ch.receive(rx) as InetSocketAddress? ?: break
                    val t = now()
                    val r = synchronized(session) {
                        session.receive(rxArray, rx.position(), t.toInt()) { claim(this) }.also {
                            if (it is ClientSession.Result.Connected) {
                                peer = from
                                sentVersion = -1 // push the held set right away
                            }
                        }
                    }
                    when (r) {
                        is ClientSession.Result.Connected -> {
                            features = r.features
                            btAddress = r.btAddress
                            lastHeard = t
                            setState(Link.State.CONNECTED, r.hostName)
                        }
                        is ClientSession.Result.Acked -> {
                            lastHeard = t
                            srtt += (r.pingMs.coerceAtLeast(0) - srtt) / 8
                            resendMs = (srtt * 1.5f + 2).toLong().coerceIn(RESEND_MIN_MS, RESEND_MS)
                            r.leds?.let(listener::onLeds)
                            r.theme?.let(listener::onTheme)
                            if (t - lastPingReport >= 250) {
                                lastPingReport = t
                                listener.onPing(r.pingMs)
                            }
                        }
                        // REJECT isn't authenticated: only believe one from where we
                        // sent HELLO, so a stranger can't slow our retries.
                        ClientSession.Result.Rejected -> if (from in candidates) setState(Link.State.REJECTED)
                        null -> {}
                    }
                }
            }
            // Leaving: BYE twice, in case one is lost. The server's 500 ms
            // stuck-key timeout covers the rest.
            synchronized(session) {
                session.byePacket()?.let { bye ->
                    send(ch, bye)
                    session.byePacket()?.let { send(ch, it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "network thread died", e)
        } finally {
            // A newer start() may already own these fields.
            if (channel === ch) channel = null
            if (selector === sel) selector = null
            try { sel.close() } catch (e: IOException) {}
            try { ch.close() } catch (e: IOException) {}
        }
    }

    private fun send(ch: DatagramChannel, data: ByteArray) {
        val p = peer ?: return
        try {
            ch.send(ByteBuffer.wrap(data), p)
        } catch (e: IOException) {
            // Wi-Fi blip, or the socket just closed; the next send or the heartbeat retries.
        }
    }

    companion object {
        private const val TAG = "omakey"
        /** Resend bounds for un-acknowledged events; the actual wait follows the measured ping. */
        const val RESEND_MIN_MS = 5L
        const val RESEND_MS = 20L
        const val HEARTBEAT_MS = 100L
        const val HELLO_FIRST_MS = 50L
        const val HELLO_MS = 250L
        const val REJECTED_HELLO_MS = 1000L
        const val LOST_MS = 1500L
    }
}
