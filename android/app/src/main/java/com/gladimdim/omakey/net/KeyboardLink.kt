package com.gladimdim.omakey.net

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
 * Owns the UDP socket and the timing rules of PROTOCOL.md on one dedicated
 * thread. The touch thread only updates [keys] and calls [wake]; the network
 * thread notices the change and sends at once.
 */
class KeyboardLink(
    host: HostRecord,
    phoneName: String,
    val keys: KeyState,
    private val listener: Listener,
) {
    enum class State { CONNECTING, CONNECTED, REJECTED }

    /** Called on the network thread. */
    interface Listener {
        fun onState(state: State, hostName: String?)
        fun onPing(ms: Int)
    }

    private val session = ClientSession(host, phoneName, keys)
    private val candidates = CopyOnWriteArrayList<InetSocketAddress>()
    @Volatile private var running = false
    @Volatile private var selector: Selector? = null
    private var thread: Thread? = null

    /** The address that answered; reported so it can be tried first next time. */
    /** WELCOME feature bits of the current session, e.g. [Wire.FEATURE_POINTER]. */
    @Volatile var features = 0
        private set

    @Volatile var peer: InetSocketAddress? = null
        private set

    init {
        for (a in host.addresses) addCandidate(InetSocketAddress(a, host.port))
    }

    /** Another place the computer might be, e.g. found over mDNS. */
    fun addCandidate(addr: InetSocketAddress) {
        if (candidates.none { it == addr }) candidates.add(0, addr)
        wake()
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "omakey-net").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** Sends BYE and stops. The server then releases every key we held. */
    fun stop() {
        running = false
        wake()
        thread?.join(500)
        thread = null
    }

    /** Wake the network thread: a key changed. Cheap; safe from any thread. */
    fun wake() {
        selector?.wakeup()
    }

    private fun now(): Int = SystemClock.elapsedRealtime().toInt()

    private fun run() {
        val channel = try {
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
        channel.register(sel, SelectionKey.OP_READ)
        selector = sel
        val rx = ByteBuffer.allocate(Wire.MAX_DATAGRAM)
        val rxArray = rx.array()

        var state: State? = null
        fun setState(s: State, name: String? = null) {
            if (s != state) {
                state = s
                listener.onState(s, name)
            }
        }
        setState(State.CONNECTING)

        var lastHello = Int.MIN_VALUE / 2
        var lastSend = 0
        var lastHeard = 0
        var sentVersion = -1L
        var lastPingReport = Int.MIN_VALUE / 2

        try {
            while (running) {
                val now = now()
                var timeout: Int
                if (!session.connected) {
                    val interval = if (state == State.REJECTED) 1000 else 250
                    if (now - lastHello >= interval) {
                        val pkt = ByteBuffer.wrap(session.helloPacket())
                        for (c in candidates) {
                            pkt.rewind()
                            try { channel.send(pkt, c) } catch (e: IOException) { /* unreachable now; retry */ }
                        }
                        lastHello = now
                    }
                    timeout = interval - (now - lastHello)
                } else {
                    if (now - lastHeard > LOST_MS) {
                        // No ACK for a while: the computer moved, slept or restarted.
                        session.restart()
                        peer = null
                        setState(State.CONNECTING)
                        continue
                    }
                    val v = keys.version
                    val unacked = keys.hasUnacked
                    val since = now - lastSend
                    if (v != sentVersion || (unacked && since >= RESEND_MS) || since >= HEARTBEAT_MS) {
                        sentVersion = v
                        session.inputPacket(now)?.let { send(channel, it) }
                        lastSend = now
                    }
                    timeout = (if (keys.hasUnacked) RESEND_MS else HEARTBEAT_MS) - (now() - lastSend)
                }

                sel.select(timeout.coerceIn(1, 1000).toLong())
                sel.selectedKeys().clear()

                while (true) {
                    rx.clear()
                    val from = channel.receive(rx) ?: break
                    val t = now()
                    when (val r = session.receive(rxArray, rx.position(), t)) {
                        is ClientSession.Result.Connected -> {
                            peer = from as InetSocketAddress
                            features = r.features
                            lastHeard = t
                            sentVersion = -1 // push the held set right away
                            setState(State.CONNECTED, r.hostName)
                        }
                        is ClientSession.Result.Acked -> {
                            lastHeard = t
                            if (t - lastPingReport >= 250) {
                                lastPingReport = t
                                listener.onPing(r.pingMs)
                            }
                        }
                        ClientSession.Result.Rejected -> setState(State.REJECTED)
                        null -> {}
                    }
                }
            }
            // Leaving: BYE twice, in case one is lost. The server's 500 ms
            // stuck-key timeout covers the rest.
            session.byePacket()?.let { bye ->
                send(channel, bye)
                session.byePacket()?.let { send(channel, it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "network thread died", e)
        } finally {
            selector = null
            try { sel.close() } catch (e: IOException) {}
            try { channel.close() } catch (e: IOException) {}
        }
    }

    private fun send(channel: DatagramChannel, data: ByteArray) {
        val p = peer ?: return
        try {
            channel.send(ByteBuffer.wrap(data), p)
        } catch (e: IOException) {
            // Wi-Fi blip; the next send or the heartbeat retries.
        }
    }

    companion object {
        private const val TAG = "omakey"
        const val RESEND_MS = 20
        const val HEARTBEAT_MS = 100
        const val LOST_MS = 1500
    }
}
