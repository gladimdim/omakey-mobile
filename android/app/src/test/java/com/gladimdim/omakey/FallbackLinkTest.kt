package com.gladimdim.omakey

import com.gladimdim.omakey.net.FallbackLink
import com.gladimdim.omakey.net.Link
import com.gladimdim.omakey.net.Scheduler
import com.gladimdim.omakey.net.WifiLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class FallbackLinkTest {
    /** Runs tasks when told to; time only moves in [advance]. */
    private class ManualScheduler : Scheduler {
        private var now = 0L
        private val queue = ArrayDeque<() -> Unit>()
        private val delayed = mutableListOf<Pair<Long, () -> Unit>>()
        var shutDown = false

        override fun execute(task: () -> Unit) {
            queue.addLast(task)
        }

        override fun schedule(delayMs: Long, task: () -> Unit) {
            delayed += (now + delayMs) to task
        }

        override fun shutdown() {
            shutDown = true
            delayed.clear()
        }

        fun runAll() {
            while (queue.isNotEmpty()) queue.removeFirst()()
        }

        fun advance(ms: Long) {
            now += ms
            val due = delayed.filter { it.first <= now }
            delayed.removeAll(due)
            due.forEach { it.second() }
            runAll()
        }
    }

    private open class FakeLink(val listener: Link.Listener, val claim: (Link) -> Boolean, override val transport: String) : Link {
        var running = false
        var sends = 0
        var starts = 0
        override val features = 1
        override fun start() { running = true; starts++ }
        override fun stop() { running = false }
        override fun send() { sends++ }

        /** The computer welcomed this link: true when it got the session. */
        fun welcome(): Boolean = claim(this).also { if (it) listener.onState(Link.State.CONNECTED, "desk") }
        fun drop() = listener.onState(Link.State.CONNECTING, null)
    }

    private class FakeWifi(listener: Link.Listener, claim: (Link) -> Boolean) : FakeLink(listener, claim, "Wi-Fi"), WifiLink {
        override fun addCandidate(addr: InetSocketAddress) {}
        override val peer: InetSocketAddress? = null
        override var btAddress: String? = null
    }

    private class Recorder : Link.Listener {
        val states = mutableListOf<Link.State>()
        override fun onState(state: Link.State, hostName: String?) { states += state }
        override fun onPing(ms: Int) {}
    }

    private class Rig(btAddress: String? = "14:18:C3:68:87:1E", allowed: Boolean = true) {
        val sched = ManualScheduler()
        val out = Recorder()
        lateinit var wifi: FakeWifi
        var bt: FakeLink? = null
        var learned: String? = null
        var allowed = allowed
        val link = FallbackLink(
            out,
            { l, claim -> FakeWifi(l, claim).also { wifi = it } },
            { _, l, claim -> FakeLink(l, claim, "Bluetooth").also { bt = it } },
            { this.allowed }, btAddress, { learned = it }, sched,
        )
    }

    @Test
    fun wifiAloneWhenItConnectsInTime() {
        val r = Rig()
        r.link.start()
        assertTrue(r.wifi.running)
        assertTrue(r.wifi.welcome())
        r.sched.advance(FallbackLink.FALLBACK_MS)
        assertEquals(null, r.bt) // never needed
        assertEquals("Wi-Fi", r.link.transport)
        r.link.send()
        assertEquals(1, r.wifi.sends)
    }

    @Test
    fun bluetoothJoinsWhenWifiIsSlowAndWifiTakesBackOver() {
        val r = Rig()
        r.link.start()
        r.sched.advance(FallbackLink.FALLBACK_MS)
        val bt = r.bt!!
        assertTrue(bt.running && r.wifi.running) // both try
        assertTrue(bt.welcome())
        assertEquals("Bluetooth", r.link.transport)
        r.link.send()
        assertEquals(1, bt.sends)
        assertEquals(0, r.wifi.sends)
        // Wi-Fi comes back: it takes the session and Bluetooth stops.
        assertTrue(r.wifi.welcome())
        r.sched.runAll()
        assertFalse(bt.running)
        assertEquals("Wi-Fi", r.link.transport)
        assertEquals(Link.State.CONNECTED, r.out.states.last())
    }

    @Test
    fun bluetoothCantTakeTheSessionFromWifi() {
        val r = Rig()
        r.link.start()
        r.sched.advance(FallbackLink.FALLBACK_MS)
        assertTrue(r.wifi.welcome())
        assertFalse(r.bt!!.welcome())
        assertEquals("Wi-Fi", r.link.transport)
    }

    @Test
    fun aWifiDropStartsBluetoothAndReportsConnecting() {
        val r = Rig()
        r.link.start()
        r.wifi.welcome()
        r.sched.runAll()
        r.wifi.drop()
        r.sched.runAll()
        assertTrue(r.bt!!.running)
        assertEquals(Link.State.CONNECTING, r.out.states.last())
        // A drop of the link not in use isn't reported.
        r.bt!!.welcome()
        val before = r.out.states.size
        r.wifi.drop()
        assertEquals(before, r.out.states.size)
    }

    @Test
    fun noBluetoothWithoutAnAddressOrPermissionUntilBothArrive() {
        val r = Rig(btAddress = null, allowed = false)
        r.link.start()
        r.sched.advance(FallbackLink.FALLBACK_MS)
        assertEquals(null, r.bt)
        // Wi-Fi tells us the address; the permission comes later.
        r.wifi.btAddress = "AA:BB:CC:DD:EE:FF"
        r.wifi.welcome()
        assertEquals("AA:BB:CC:DD:EE:FF", r.learned)
        r.wifi.drop()
        r.sched.runAll()
        assertEquals(null, r.bt) // still no permission
        r.allowed = true
        r.link.bluetoothAllowedNow()
        r.sched.runAll()
        assertTrue(r.bt!!.running)
    }

    @Test
    fun stopDoesntBlockAndStopsEverythingOnTheScheduler() {
        val r = Rig()
        r.link.start()
        r.sched.advance(FallbackLink.FALLBACK_MS)
        r.link.stop()
        assertTrue(r.wifi.running) // not stopped on the caller's thread
        r.sched.runAll()
        assertFalse(r.wifi.running)
        assertFalse(r.bt!!.running)
        assertTrue(r.sched.shutDown)
        assertFalse(r.wifi.welcome()) // a late WELCOME isn't taken
    }
}
