package com.gladimdim.omakey.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import com.gladimdim.omakey.protocol.Hid
import com.gladimdim.omakey.protocol.KeyState
import com.gladimdim.omakey.protocol.Wire
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The phone as a standard Bluetooth keyboard and mouse (the HID Device
 * profile), for any computer: no omakeyd needed. The computer pairs with
 * the phone in its own Bluetooth settings.
 *
 * With a [target] address it connects to that computer; without one it
 * waits for a new computer to pair and connect ([Link.State.WAITING]).
 * Some phones don't offer the HID Device profile: [Link.State.UNSUPPORTED].
 *
 * A HID host has no stuck-key timeout, so every key change is sent again a
 * little later, and a report Android didn't take is retried until it is.
 *
 * The caller must hold BLUETOOTH_CONNECT (Android 12+).
 */
@SuppressLint("MissingPermission")
class BluetoothHidLink(
    private val context: Context,
    private val target: String?,
    private val keys: KeyState,
    private val listener: Link.Listener,
    /** A computer connected: its address and name, to remember it. */
    private val onConnected: (address: String, name: String) -> Unit,
) : Link {
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    /** HID callbacks, repeats and retries. */
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "omakey-hid") }
    /** What the Bluetooth service calls back on; callbacks after [stop] are dropped. */
    private val callbacks = Executor { task ->
        try { worker.execute(task) } catch (e: RejectedExecutionException) {}
    }
    private val reports = Hid.Reports()
    @Volatile private var hid: BluetoothHidDevice? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var registered = false
    @Volatile private var running = false
    // Worker thread only.
    private var retry: ScheduledFuture<*>? = null
    private var repeats: ScheduledFuture<*>? = null
    private var registerAttempts = 0
    private var state: Link.State? = null

    override val features get() = Wire.FEATURE_POINTER
    override val transport get() = "Bluetooth keyboard"

    private fun setState(s: Link.State, name: String? = null) {
        if (s != state) {
            state = s
            listener.onState(s, name)
        }
    }

    override fun start() {
        running = true
        setState(Link.State.CONNECTING)
        val a = adapter
        val ok = try {
            a != null && a.getProfileProxy(context, serviceListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) setState(Link.State.UNSUPPORTED)
    }

    override fun stop() {
        running = false
        worker.shutdownNow()
        val h = hid ?: return
        try {
            // The computer sees the keyboard turn off and lets go of every key.
            if (registered) h.unregisterApp()
            adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h)
        } catch (e: SecurityException) {
        }
        hid = null
        device = null
    }

    override fun send() {
        val h = hid ?: return
        val d = device ?: return
        val repeat = synchronized(reports) {
            try {
                reports.build(keys.held(), keys.takePointer()) { id, data -> h.sendReport(d, id, data) } || reports.behind
            } catch (e: SecurityException) {
                false
            }
        }
        if (repeat) later { scheduleRepeats() }
    }

    /** Worker thread: the key state again at +20 ms and +80 ms, then until Android takes it. */
    private fun scheduleRepeats() {
        repeats?.cancel(false)
        var n = 0
        lateinit var step: () -> Unit
        step = {
            val h = hid
            val d = device
            if (running && h != null && d != null) {
                val behind = synchronized(reports) {
                    try {
                        reports.repeat { id, data -> h.sendReport(d, id, data) }
                    } catch (e: SecurityException) {
                    }
                    reports.behind
                }
                n++
                if (n < 2 || behind) repeats = schedule(if (n == 1) 60 else 50, step)
            }
        }
        repeats = schedule(20, step)
    }

    private val serviceListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (!running) {
                adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, proxy)
                return
            }
            hid = proxy as BluetoothHidDevice
            register()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            device = null
            registered = false
            if (running) setState(Link.State.CONNECTING)
        }
    }

    private fun register() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Omakey", "Omakey phone keyboard", "Omakey", BluetoothHidDevice.SUBCLASS1_COMBO, Hid.DESCRIPTOR,
        )
        val ok = try {
            h.registerApp(sdp, null, null, callbacks, callback)
        } catch (e: Exception) {
            Log.w(TAG, "registerApp failed", e)
            false
        }
        if (!ok) later { registrationFailed() }
    }

    /**
     * Worker thread. Registration fails while another app holds the profile,
     * Bluetooth is off, or the previous keyboard is still unregistering
     * (switching computers quickly): try again, backing off.
     */
    private fun registrationFailed() {
        if (!running) return
        registerAttempts++
        setState(if (registerAttempts >= 3) Link.State.UNSUPPORTED else Link.State.CONNECTING)
        val delay = (500L shl minOf(registerAttempts - 1, 4)).coerceAtMost(8000)
        retry?.cancel(false)
        retry = schedule(delay) { if (running && !registered) register() }
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@BluetoothHidLink.registered = registered
            if (!running) return
            if (!registered) {
                device = null
                registrationFailed()
                return
            }
            registerAttempts = 0
            if (target == null) setState(Link.State.WAITING) else connectToTarget()
        }

        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
            if (!running) return
            when (s) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (target != null && !d.address.equals(target, ignoreCase = true)) return
                    retry?.cancel(false)
                    device = d
                    keys.takePointer() // motion from before the connection is stale
                    synchronized(reports) { reports.reset() }
                    send()
                    val name = d.name ?: d.address
                    onConnected(d.address, name)
                    setState(Link.State.CONNECTED, name)
                }
                BluetoothProfile.STATE_DISCONNECTED -> if (device == d || device == null) {
                    device = null
                    if (target == null) setState(Link.State.WAITING) else connectToTarget()
                }
            }
        }

        override fun onGetReport(d: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val h = hid ?: return
            val data = synchronized(reports) { reports.current(id.toInt()) }
            if (data == null) h.reportError(d, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
            else h.replyReport(d, type, id, data)
        }

        override fun onSetReport(d: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            if (id.toInt() == Hid.ID_KEYBOARD) leds(data)
            hid?.reportError(d, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onInterruptData(d: BluetoothDevice, reportId: Byte, data: ByteArray) {
            if (reportId.toInt() == Hid.ID_KEYBOARD) leds(data)
        }

        override fun onSetProtocol(d: BluetoothDevice, protocol: Byte) {
            // Boot protocol is for BIOS-style hosts; ours always parse the descriptor.
            Log.i(TAG, "host set HID protocol $protocol")
        }
    }

    /** The keyboard's LED output report: bit 0 Num Lock, 1 Caps Lock, 2 Scroll Lock, as in ACK. */
    private fun leds(data: ByteArray) {
        // Some stacks pass the report id along as the first byte.
        val b = if (data.size >= 2 && data[0].toInt() == Hid.ID_KEYBOARD) data[1] else data.firstOrNull() ?: return
        listener.onLeds(b.toInt() and 0x07)
    }

    /** Ask the remembered computer to take the keyboard; retried while it's away. Worker thread. */
    private fun connectToTarget() {
        val h = hid ?: return
        val address = target ?: return
        setState(Link.State.CONNECTING)
        try {
            h.connect(adapter?.getRemoteDevice(address) ?: return)
        } catch (e: Exception) {
            Log.w(TAG, "HID connect failed", e)
        }
        retry?.cancel(false)
        retry = schedule(RETRY_MS) { if (running && device == null) connectToTarget() }
    }

    private fun schedule(ms: Long, task: () -> Unit): ScheduledFuture<*>? = try {
        worker.schedule(task, ms, TimeUnit.MILLISECONDS)
    } catch (e: RejectedExecutionException) {
        null
    }

    private fun later(task: () -> Unit) = callbacks.execute(task)

    companion object {
        private const val TAG = "omakey"
        const val RETRY_MS = 4000L
    }
}
