package com.gladimdim.omakey.net

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.gladimdim.omakey.protocol.ClientSession
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which paired computers answer right now, for the connect screen. Every
 * [PERIOD_MS] each one gets a HELLO at its known addresses (and where mDNS
 * last saw it), and a BYE as soon as its WELCOME comes back. A session that
 * never sends INPUT can't push out one that is typing, and omakeyd doesn't
 * list it as connected (PROTOCOL.md, "Safety rules on the server").
 * Results are delivered on the main thread, only when they change.
 */
class Reachability(private val phoneName: String, private val onChange: (Map<String, InetSocketAddress?>) -> Unit) {
    @Volatile private var hosts: List<HostRecord> = emptyList()
    @Volatile private var seen: Map<String, InetSocketAddress> = emptyMap()
    /** The flag of the running check thread; a stopped thread sees its own flag false and quits. */
    private var alive: AtomicBoolean? = null
    private val lock = Object()
    private val main = Handler(Looper.getMainLooper())

    /** The computers to check, and where mDNS found any of them. Checks a new one right away. */
    fun update(paired: List<HostRecord>, nearby: List<Found>) {
        val fresh = paired.any { p -> hosts.none { it.hostId == p.hostId } }
        hosts = paired
        seen = nearby.mapNotNull { f -> f.hostId?.let { it to f.address } }.toMap()
        if (fresh) synchronized(lock) { lock.notifyAll() }
    }

    fun start() {
        if (alive != null) return
        val flag = AtomicBoolean(true).also { alive = it }
        Thread({ run(flag) }, "omakey-reach").apply { isDaemon = true; start() }
    }

    fun stop() {
        alive?.set(false)
        alive = null
        synchronized(lock) { lock.notifyAll() }
    }

    private fun run(running: AtomicBoolean) {
        var last: Map<String, InetSocketAddress?>? = null
        while (running.get()) {
            val now = hosts.associate { it.hostId to probe(it, running) }
            if (!running.get()) break
            if (now != last) {
                last = now
                main.post { if (running.get()) onChange(now) }
            }
            synchronized(lock) { if (running.get()) lock.wait(PERIOD_MS) }
        }
    }

    /** The address that answered, or null. */
    private fun probe(host: HostRecord, running: AtomicBoolean): InetSocketAddress? {
        val targets = (listOfNotNull(seen[host.hostId]) + host.addresses.map { InetSocketAddress(it, host.port) }).distinct()
        val session = ClientSession(host, phoneName, KeyState())
        val buf = ByteArray(2048)
        return try {
            DatagramSocket().use { sock ->
                val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
                var nextHello = 0L
                while (running.get()) {
                    val t = SystemClock.elapsedRealtime()
                    if (t >= deadline) return null
                    if (t >= nextHello) {
                        val hello = session.helloPacket()
                        for (a in targets) {
                            try { sock.send(DatagramPacket(hello, hello.size, a)) } catch (e: IOException) { /* that network is gone */ }
                        }
                        nextHello = t + RETRY_MS
                    }
                    sock.soTimeout = (minOf(nextHello, deadline) - t).toInt().coerceAtLeast(1)
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    if (session.receive(buf, pkt.length, t.toInt()) is ClientSession.Result.Connected) {
                        val from = pkt.socketAddress as InetSocketAddress
                        // Twice, in case one is lost; omakeyd drops it after 30 s anyway.
                        repeat(2) { session.byePacket()?.let { sock.send(DatagramPacket(it, it.size, from)) } }
                        return from
                    }
                }
                null
            }
        } catch (e: IOException) {
            Log.w("omakey", "can't check ${host.name}", e)
            null
        }
    }

    private companion object {
        const val PERIOD_MS = 4000L
        const val TIMEOUT_MS = 1200L
        const val RETRY_MS = 300L
    }
}
