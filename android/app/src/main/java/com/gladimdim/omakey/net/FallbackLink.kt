package com.gladimdim.omakey.net

import android.content.Context
import com.gladimdim.omakey.protocol.ClipTransfer
import com.gladimdim.omakey.protocol.DesktopTheme
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import java.net.InetSocketAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** What [FallbackLink] needs from the Wi-Fi link: [KeyboardLink], or a fake in tests. */
interface WifiLink : Link {
    fun addCandidate(addr: InetSocketAddress)

    /** The address that answered. */
    val peer: InetSocketAddress?

    /** The computer's Bluetooth address from its WELCOME, if it has one. */
    val btAddress: String?
}

/** Runs [FallbackLink]'s starts and stops in order, off the caller's thread. */
interface Scheduler {
    fun execute(task: () -> Unit)
    fun schedule(delayMs: Long, task: () -> Unit)
    /** Runs what's queued, then stops; delayed tasks are dropped. */
    fun shutdown()
}

/**
 * omakeyd over Wi-Fi, falling back to Bluetooth.
 *
 * - Wi-Fi runs the whole time. Bluetooth starts when Wi-Fi hasn't connected
 *   within [FALLBACK_MS], or when the Wi-Fi connection drops.
 * - The first to connect owns the session; the computer only ever sees one.
 * - Wi-Fi is preferred: when it connects while Bluetooth owns the session it
 *   takes over and Bluetooth stops, so a phone back in range of the Wi-Fi
 *   returns to the faster path by itself.
 *
 * Bluetooth needs the computer's address (from the pairing link, or learned
 * from a WELCOME over Wi-Fi) and [bluetoothAllowed] (the permission).
 */
class FallbackLink(
    private val listener: Link.Listener,
    makeWifi: (Link.Listener, (Link) -> Boolean) -> WifiLink,
    private val makeBluetooth: (address: String, Link.Listener, (Link) -> Boolean) -> Link,
    private val bluetoothAllowed: () -> Boolean,
    btAddress: String?,
    /** Called with each new Bluetooth address learned over Wi-Fi, to save it. */
    private val onBtAddress: (String) -> Unit,
    private val scheduler: Scheduler = ExecutorScheduler(),
) : Link {
    private val lock = Any()
    private var owner: Link? = null // guarded by lock
    @Volatile private var running = false
    @Volatile private var btAddress = btAddress

    val wifi: WifiLink = makeWifi(inner { wifi }, ::claim)

    // Scheduler thread only.
    private var bt: Link? = null
    private var btRunning = false

    /** The Wi-Fi address that answered, to try first next time. */
    val peer: InetSocketAddress? get() = wifi.peer

    override val features get() = current()?.features ?: 0
    override val transport get() = current()?.transport ?: wifi.transport

    private fun current(): Link? = synchronized(lock) { owner }

    fun addCandidate(addr: InetSocketAddress) = wifi.addCandidate(addr)

    override fun start() {
        running = true
        wifi.start()
        scheduler.schedule(FALLBACK_MS) { if (running && current() == null) startBluetooth() }
    }

    /** Doesn't block: the links are stopped on the scheduler's thread. */
    override fun stop() {
        running = false
        try {
            scheduler.execute {
                wifi.stop()
                bt?.stop()
            }
        } catch (e: RejectedExecutionException) {
        }
        scheduler.shutdown()
    }

    override fun send() {
        (current() ?: wifi).send()
    }

    override fun clip(transfer: ClipTransfer): Boolean = current()?.clip(transfer) ?: false

    /** The Bluetooth permission was just granted: use it if Wi-Fi isn't connected. */
    fun bluetoothAllowedNow() = later { if (current() == null) startBluetooth() }

    private fun claim(link: Link): Boolean = synchronized(lock) {
        when {
            !running -> false
            owner == null || owner === link -> { owner = link; true }
            link === wifi -> {
                // Back on Wi-Fi: it takes over and Bluetooth stops.
                owner = link
                later(::stopBluetooth)
                true
            }
            else -> false
        }
    }

    /** Scheduler thread. */
    private fun startBluetooth() {
        val address = btAddress ?: return
        if (!running || btRunning || !bluetoothAllowed()) return
        val link = bt ?: makeBluetooth(address, inner { bt!! }, ::claim).also { bt = it }
        btRunning = true
        link.start()
    }

    /** Scheduler thread. */
    private fun stopBluetooth() {
        if (!btRunning) return
        btRunning = false
        bt?.stop()
    }

    private fun inner(self: () -> Link) = object : Link.Listener {
        override fun onState(state: Link.State, hostName: String?) {
            val link = self()
            if (state == Link.State.CONNECTED) {
                if (link === wifi) {
                    wifi.btAddress?.takeIf { it != btAddress }?.let {
                        btAddress = it
                        onBtAddress(it)
                    }
                    later(::stopBluetooth)
                }
                if (current() === link) listener.onState(state, hostName)
                return
            }
            val dropped = synchronized(lock) { (owner === link).also { if (it) owner = null } }
            when {
                // The link in use dropped. Wi-Fi keeps trying by itself; Bluetooth joins in.
                dropped -> {
                    if (link === wifi) later(::startBluetooth)
                    listener.onState(Link.State.CONNECTING, null)
                }
                current() == null -> listener.onState(state, hostName)
            }
        }

        override fun onPing(ms: Int) {
            if (current() === self()) listener.onPing(ms)
        }

        override fun onLeds(leds: Int) {
            if (current() === self()) listener.onLeds(leds)
        }

        override fun onTheme(theme: DesktopTheme) {
            if (current() === self()) listener.onTheme(theme)
        }

        override fun onClip(outcome: ClipTransfer.Outcome) {
            if (current() === self()) listener.onClip(outcome)
        }
    }

    private fun later(task: () -> Unit) {
        if (!running) return
        try {
            scheduler.execute { if (running) task() }
        } catch (e: RejectedExecutionException) {
            // Stopping.
        }
    }

    companion object {
        /** How long Wi-Fi gets before Bluetooth tries too. */
        const val FALLBACK_MS = 1200L

        fun create(
            context: Context,
            host: HostRecord,
            phoneName: String,
            keys: KeyState,
            listener: Link.Listener,
            bluetoothAllowed: () -> Boolean,
            onBtAddress: (String) -> Unit,
        ) = FallbackLink(
            listener,
            { l, claim -> KeyboardLink(host, phoneName, keys, l, claim) },
            { address, l, claim -> RfcommLink(context, host, address, phoneName, keys, l, claim) },
            bluetoothAllowed, host.btAddress, onBtAddress,
        )
    }
}

/** A single background thread; starting and stopping links waits on their threads. */
class ExecutorScheduler : Scheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { Thread(it, "omakey-fallback") }.apply {
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }

    override fun execute(task: () -> Unit) = executor.execute(task)

    override fun schedule(delayMs: Long, task: () -> Unit) {
        try {
            executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
        }
    }

    override fun shutdown() = executor.shutdown()
}
