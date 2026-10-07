package com.gladimdim.omakey.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.keyboard.KeyboardView
import com.gladimdim.omakey.keyboard.TouchpadView
import android.widget.Toast
import com.gladimdim.omakey.net.BluetoothHidLink
import com.gladimdim.omakey.net.Discovery
import com.gladimdim.omakey.net.FallbackLink
import com.gladimdim.omakey.net.Found
import com.gladimdim.omakey.net.Link
import com.gladimdim.omakey.protocol.Ack
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import com.gladimdim.omakey.protocol.Wire
import com.gladimdim.omakey.store.BtHost
import com.gladimdim.omakey.store.BtHostStore
import com.gladimdim.omakey.store.HostStore
import com.gladimdim.omakey.store.LayoutStore

/**
 * The keyboard: landscape, fullscreen, screen kept on. While it is visible
 * it keeps a live connection to the computer: omakeyd over Wi-Fi (holding a
 * low-latency Wi-Fi lock) with a Bluetooth fallback, or the phone as a
 * plain Bluetooth keyboard. The computer can be switched from here without
 * leaving the keyboard.
 */
class KeyboardActivity : Activity() {
    /** What the keyboard types into. */
    private sealed interface Target {
        /** Saved and passed around as [EXTRA_TARGET]; also keys the touchpad settings. */
        val id: String
        val name: String

        class Omakey(val host: HostRecord) : Target {
            override val id get() = host.hostId
            override val name get() = host.name
        }

        /** Any computer, with the phone as its Bluetooth keyboard. No [address]: pair a new one. */
        class Bluetooth(val address: String?, override val name: String) : Target {
            override val id get() = if (address == null) BT_NEW else BT_PREFIX + address
        }
    }

    private lateinit var target: Target
    private lateinit var hosts: HostStore
    private lateinit var btHosts: BtHostStore
    private lateinit var layouts: LayoutStore
    private lateinit var keyboard: KeyboardView
    private lateinit var status: TextView
    private lateinit var stage: FrameLayout
    private lateinit var panel: FrameLayout
    private lateinit var touchpad: TouchpadView
    private lateinit var handleLabel: TextView
    private var padOpen = false

    private val keys = KeyState()
    private var link: Link? = null
    private var discovery: Discovery? = null
    private var nearby: List<Found> = emptyList()
    private var wifiLock: WifiManager.WifiLock? = null

    private var linkState = Link.State.CONNECTING
    private var hostName = ""
    private var pingMs = -1

    /**
     * A Bluetooth keyboard kept alive for a moment after the screen goes
     * off, so coming back doesn't mean the computer reconnecting it.
     */
    private var parked: Link? = null
    private var parkedFor: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopParked = Runnable {
        val p = parked ?: return@Runnable
        if (link === p) {
            link = null
            linkState = Link.State.CONNECTING
        }
        p.stop()
        parked = null
        parkedFor = null
    }

    private val prefs by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }
    private lateinit var stickyButton: TextView

    private val sink = object : KeyboardModel.Sink {
        override fun keyDown(code: Int) {
            if (keys.press(code)) link?.send()
        }

        override fun keyUp(code: Int) {
            if (keys.release(code)) link?.send()
        }
    }

    private val padSink = object : TouchpadView.Sink {
        override fun button(code: Int, down: Boolean) {
            if (if (down) keys.press(code) else keys.release(code)) link?.send()
        }

        override fun motion(dx: Float, dy: Float) {
            keys.addMotion(dx, dy)
            link?.send()
        }

        override fun scroll(v: Float, h: Float) {
            keys.addScroll(v, h)
            link?.send()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hosts = HostStore(this)
        btHosts = BtHostStore(this)
        layouts = LayoutStore(this)
        val id = savedInstanceState?.getString(EXTRA_TARGET) ?: intent.getStringExtra(EXTRA_TARGET)
        target = id?.let(::resolve) ?: run { finish(); return }
        hostName = target.name
        // Key changes go out from the touch handler itself (KeyboardLink.send):
        // a non-blocking UDP send, cheaper than waking another thread for it.
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder(StrictMode.getThreadPolicy()).permitNetwork().build())

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Draw under the camera cutout too: the keyboard uses the whole display.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        status = text("", 12f, Palette.FG_DIM, bold = true).apply {
            background = rounded(Palette.SURFACE, dp(12f).toFloat())
            setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
            isClickable = true
            setOnClickListener { pickHost() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), 0)
            addView(status)
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(barButton("") { toggleSticky() }.also { stickyButton = it })
            addView(barButton("⇄ PC") { pickHost() })
            addView(barButton("⌨ Layout") { pickLayout() })
            addView(barButton("✕") { finish() })
        }
        keyboard = KeyboardView(this)

        // The touchpad lives above the keyboard and slides down over it.
        touchpad = TouchpadView(this).apply {
            sink = padSink
            onSensitivityChanged = { v ->
                padPrefs.edit().putFloat("sens:${target.id}", v).putString("preset:${target.id}", CUSTOM).apply()
                presetName = CUSTOM
            }
            onPresetsRequested = ::pickPreset
            // Swiping up from the bottom row drags the touchpad back up.
            panelDrag = object : TouchpadView.PanelDrag {
                override fun drag(dy: Float) {
                    panelMoving = true
                    panel.animate().cancel()
                    panel.translationY = dy.coerceIn(-stage.height.toFloat(), 0f)
                }

                override fun release(dy: Float, flungUp: Boolean) {
                    setPad(!(flungUp || -dy > stage.height * 0.25f))
                }
            }
        }
        panel = FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            visibility = View.INVISIBLE
            addView(touchpad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        stage = FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // Keep a closed panel parked just above the stage when its size
            // changes, but not on every layout pass (the status pill causes
            // those) or while it is being dragged or animated.
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (!padOpen && !panelMoving && bottom - top != oldBottom - oldTop) {
                    panel.translationY = -(bottom - top).toFloat()
                }
            }
        }

        // The pull-down handle, centred in the top bar.
        handleLabel = text("⌄  touchpad", 12f, Palette.ACCENT, bold = true)
        val handle = FrameLayout(this).apply {
            background = rounded(Palette.SURFACE, dp(12f).toFloat(), Palette.ACCENT, dp(1f))
            addView(handleLabel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            setOnTouchListener(::onHandleTouch)
            contentDescription = "Touchpad: tap or pull down"
        }
        val top = FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(handle, FrameLayout.LayoutParams(dp(136f), dp(26f), Gravity.CENTER).apply { topMargin = dp(2f) })
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32f)))
            addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        applyPadSettings()
        keyboard.sticky = prefs.getBoolean("sticky", false)
        renderSticky()
        keyboard.setLayout(layouts.selected(), sink)
        renderStatus()
    }

    /** Sticky keys: tap Shift, Ctrl, Alt, Super or Fn instead of holding it. */
    private fun toggleSticky() {
        keyboard.releaseAll()
        keyboard.sticky = !keyboard.sticky
        prefs.edit().putBoolean("sticky", keyboard.sticky).apply()
        renderSticky()
        Toast.makeText(this, if (keyboard.sticky) "Sticky keys on: tap a modifier for the next key, twice to lock it"
            else "Sticky keys off", Toast.LENGTH_SHORT).show()
    }

    private fun renderSticky() {
        stickyButton.text = if (keyboard.sticky) "⇧ Sticky ●" else "⇧ Sticky"
        stickyButton.setTextColor(if (keyboard.sticky) Palette.ACCENT else Palette.FG_DIM)
    }

    private fun barButton(label: String, onClick: (View) -> Unit) = text(label, 13f, Palette.ACCENT, bold = true).apply {
        setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
        isClickable = true
        setOnClickListener(onClick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TARGET, target.id)
    }

    private fun resolve(id: String): Target? = when {
        id == BT_NEW -> Target.Bluetooth(null, "a new computer")
        id.startsWith(BT_PREFIX) -> btHosts.get(id.removePrefix(BT_PREFIX))?.let { Target.Bluetooth(it.address, it.name) }
        else -> hosts.get(id)?.let { Target.Omakey(it) }
    }

    override fun onStart() {
        super.onStart()
        updateWifiLock()
        connect()
        // The computer may have a new address; mDNS finds it by host id. The
        // list also tells the computer switcher which ones are around.
        discovery = Discovery(this) { found ->
            nearby = found
            routeCandidates()
        }.also { it.start() }
    }

    private fun bluetoothGranted(): Boolean =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Start a connection to [target]. Callbacks from a replaced link are ignored. */
    private fun connect() {
        val t = target
        if (t is Target.Bluetooth && !bluetoothGranted()) {
            // Connected from onRequestPermissionsResult instead.
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), REQ_BLUETOOTH)
            return
        }
        // Back within a few seconds: the Bluetooth keyboard is still connected.
        if (parked != null && parkedFor == t.id && link === parked) {
            handler.removeCallbacks(stopParked)
            parked = null
            parkedFor = null
            renderStatus()
            return
        }
        handler.removeCallbacks(stopParked)
        stopParked.run()
        lateinit var l: Link
        val listener = object : Link.Listener {
            override fun onState(state: Link.State, hostName: String?) = runOnUiThread {
                if (link !== l) return@runOnUiThread
                linkState = state
                if (hostName != null) this@KeyboardActivity.hostName = hostName
                if (state == Link.State.CONNECTED) {
                    if (t is Target.Omakey && l.transport == "Wi-Fi") {
                        (l as FallbackLink).peer?.address?.hostAddress?.let { hosts.rememberAddress(t.host.hostId, it) }
                    }
                    touchpad.supported = l.features and Wire.FEATURE_POINTER != 0
                } else {
                    pingMs = -1
                }
                renderStatus()
            }

            override fun onPing(ms: Int) = runOnUiThread {
                if (link !== l) return@runOnUiThread
                pingMs = ms
                renderStatus()
            }

            override fun onLeds(leds: Int) = runOnUiThread {
                if (link === l) keyboard.setCapsLock(leds and Ack.LED_CAPS != 0)
            }
        }
        l = when (t) {
            is Target.Omakey -> FallbackLink.create(this, t.host, phoneName(), keys, listener, ::bluetoothGranted) { address ->
                runOnUiThread {
                    hosts.rememberBtAddress(t.host.hostId, address)
                    if (target.id == t.id) hosts.get(t.id)?.let { target = Target.Omakey(it) }
                    askForBluetoothFallback(t.host.hostId)
                }
            }
            is Target.Bluetooth -> BluetoothHidLink(this, t.address, keys, listener) { address, name ->
                runOnUiThread { onBluetoothConnected(address, name) }
            }
        }
        link = l
        l.start()
        routeCandidates()
        if (t is Target.Bluetooth && t.address == null) requestDiscoverable()
    }

    /**
     * The computer turned out to have Bluetooth: offer the fallback, once per
     * computer, if the permission isn't there yet.
     */
    private fun askForBluetoothFallback(hostId: String) {
        if (bluetoothGranted() || prefs.getBoolean("btAsked:$hostId", false)) return
        prefs.edit().putBoolean("btAsked:$hostId", true).apply()
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH_FALLBACK)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_BLUETOOTH_FALLBACK) {
            if (bluetoothGranted()) (link as? FallbackLink)?.bluetoothAllowedNow()
            return
        }
        if (requestCode != REQ_BLUETOOTH) return
        if (bluetoothGranted()) {
            connect()
        } else {
            Toast.makeText(this, "Omakey needs the Nearby devices permission to be a Bluetooth keyboard", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    /** Pairing a new computer: it has to see the phone first. */
    private fun requestDiscoverable() {
        try {
            startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
                .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 180))
        } catch (e: Exception) {
            // No permission or no Bluetooth: the status pill says what's wrong.
        }
    }

    /** A computer took the Bluetooth keyboard; remember it for next time. */
    private fun onBluetoothConnected(address: String, name: String) {
        btHosts.put(BtHost(address, name))
        val t = target
        if (t is Target.Bluetooth && t.address == null) {
            target = Target.Bluetooth(address, name)
            applyPadSettings()
        }
    }

    private fun routeCandidates() {
        val l = link as? FallbackLink ?: return
        val t = target as? Target.Omakey ?: return
        nearby.filter { it.hostId == t.host.hostId }.forEach { l.addCandidate(it.address) }
    }

    /** Move the keyboard to another computer. */
    private fun switchTo(next: Target) {
        if (next.id == target.id) return
        // Let go of everything first: the old computer gets BYE (or the
        // Bluetooth keyboard turns off) and releases whatever was held;
        // nothing carries over to the new one.
        keyboard.releaseAll()
        keys.releaseAll()
        handler.removeCallbacks(stopParked)
        parked = null
        parkedFor = null
        link?.stop()
        link = null
        target = next
        hostName = next.name
        // Another computer, its own Caps Lock: assume off until it says.
        keyboard.setCapsLock(false)
        linkState = Link.State.CONNECTING
        pingMs = -1
        // Most recently used first on the connect screen.
        when (next) {
            is Target.Omakey -> hosts.put(next.host)
            is Target.Bluetooth -> next.address?.let { btHosts.put(BtHost(it, next.name)) }
        }
        applyPadSettings()
        updateWifiLock()
        renderStatus()
        connect()
    }

    private fun pickHost() {
        val all: List<Target> = hosts.all().map { Target.Omakey(it) } +
            btHosts.all().map { Target.Bluetooth(it.address, it.name) }
        val nearbyIds = nearby.mapNotNull { it.hostId }.toSet()
        val labels = all.map { h ->
            val current = h.id == target.id
            val detail = when {
                current && linkState == Link.State.CONNECTED ->
                    if (pingMs >= 0) "connected · $pingMs ms" else "connected"
                current -> "connecting…"
                h is Target.Bluetooth -> "Bluetooth keyboard"
                h.id in nearbyIds -> "nearby"
                else -> "not seen on this network"
            }
            (if (current) "● " else "   ") + h.name + "  ·  " + detail
        }.toTypedArray()

        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Type on")
            .setItems(labels) { _, i -> switchTo(all[i]) }
            .setNeutralButton("Pair another…") { _, _ ->
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onPause() {
        // Never leave a key held on the computer while we're not looking.
        keyboard.releaseAll()
        touchpad.releaseAll()
        keys.releaseAll()
        link?.send()
        super.onPause()
    }

    override fun onStop() {
        discovery?.stop()
        discovery = null
        val l = link
        if (l is BluetoothHidLink && !isFinishing) {
            // Screen off or a quick app switch: keep the Bluetooth keyboard
            // (still the live link, its callbacks still landing) for a little
            // while, so the computer doesn't have to reconnect it.
            parked = l
            parkedFor = target.id
            handler.postDelayed(stopParked, PARK_MS)
        } else {
            link = null
            l?.stop()
            linkState = Link.State.CONNECTING
        }
        releaseWifiLock()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopParked)
        stopParked.run()
        super.onDestroy()
    }

    /** Held for omakeyd only: a Bluetooth keyboard doesn't use Wi-Fi. */
    private fun updateWifiLock() {
        if (target !is Target.Omakey) return releaseWifiLock()
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        // Keeps the radio out of power save: otherwise packets can wait 100+ ms.
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "omakey:keyboard").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    // ---- touchpad panel ----

    private var dragStartY = 0f
    private var dragStartOffset = 0f
    private var dragMoved = false

    /** Tap toggles the touchpad; dragging pulls it down or pushes it back up. */
    private fun onHandleTouch(v: View, ev: MotionEvent): Boolean {
        val h = stage.height.toFloat()
        if (h <= 0f) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = ev.rawY
                dragStartOffset = if (padOpen) h else 0f
                dragMoved = false
                panel.animate().cancel()
                panel.visibility = View.VISIBLE
            }
            MotionEvent.ACTION_MOVE -> {
                val d = ev.rawY - dragStartY
                if (kotlin.math.abs(d) > dp(4f)) dragMoved = true
                if (dragMoved) panel.translationY = (dragStartOffset + d).coerceIn(0f, h) - h
            }
            MotionEvent.ACTION_UP -> {
                val shown = panel.translationY + h
                setPad(if (!dragMoved) !padOpen else if (padOpen) shown > h * 0.65f else shown > h * 0.35f)
            }
            // The system took the gesture: put the panel back where it was.
            MotionEvent.ACTION_CANCEL -> setPad(padOpen)
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN || ev.actionMasked == MotionEvent.ACTION_MOVE) panelMoving = true
        return true
    }

    /** The panel is being dragged or animated: layout passes leave it alone. */
    private var panelMoving = false

    private val padPrefs by lazy { getSharedPreferences("touchpad", MODE_PRIVATE) }

    /** Each computer keeps its own touchpad speed: their monitors differ. */
    private fun applyPadSettings() {
        val d = PointerPresets.default
        touchpad.sensitivity = padPrefs.getFloat("sens:${target.id}", d.sensitivity)
        touchpad.presetName = padPrefs.getString("preset:${target.id}", d.name) ?: d.name
    }

    private fun pickPreset() {
        val presets = PointerPresets.all
        val labels = presets.map { p ->
            val mark = if (p.name == touchpad.presetName) "● " else "   "
            "$mark${p.name}  ·  ${p.sensitivity}×\n     ${p.detail}"
        }.toTypedArray()
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Pointer speed for ${target.name}")
            .setItems(labels) { _, i ->
                val p = presets[i]
                padPrefs.edit().putFloat("sens:${target.id}", p.sensitivity).putString("preset:${target.id}", p.name).apply()
                applyPadSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setPad(open: Boolean) {
        val h = stage.height.toFloat()
        if (open && !padOpen) keyboard.releaseAll()
        if (!open && padOpen) touchpad.releaseAll()
        padOpen = open
        handleLabel.text = if (open) "⌃  keyboard" else "⌄  touchpad"
        panel.visibility = View.VISIBLE
        panelMoving = true
        panel.animate()
            .translationY(if (open) 0f else -h)
            .setDuration(240)
            .setInterpolator(DecelerateInterpolator(2f))
            .withEndAction {
                panelMoving = false
                if (!padOpen) panel.visibility = View.INVISIBLE
            }
            .start()
    }

    @Deprecated("Back closes the touchpad first")
    override fun onBackPressed() {
        if (padOpen) setPad(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun pickLayout() {
        LayoutPicker.show(this, layouts, onImport = null) {
            keyboard.releaseAll()
            keys.releaseAll()
            link?.send()
            keyboard.setLayout(layouts.selected(), sink)
        }
    }

    private fun renderStatus() {
        // Wi-Fi is the usual way and goes unsaid; anything else is named.
        val via = link?.transport?.takeIf { it != "Wi-Fi" }?.let { " · $it" } ?: ""
        val (dot, color, msg) = when (linkState) {
            Link.State.CONNECTED ->
                Triple("●", Palette.OK, hostName + via + if (pingMs >= 0) " · $pingMs ms" else "")
            Link.State.CONNECTING -> Triple("●", Palette.WARN, "Connecting to $hostName…")
            Link.State.REJECTED -> Triple("●", Palette.ERROR, "$hostName doesn't know this phone. Pair again.")
            Link.State.WAITING ->
                Triple("●", Palette.WARN, "On the computer, open Bluetooth settings and pair with “${phoneName()}”")
            Link.State.UNSUPPORTED ->
                Triple("●", Palette.ERROR, "This phone can't be a Bluetooth keyboard (or Bluetooth is off)")
        }
        status.text = "$dot  $msg"
        status.setTextColor(color)
    }

    private fun phoneName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        /** An omakeyd host id, [BT_PREFIX] + a Bluetooth address, or [BT_NEW]. */
        const val EXTRA_TARGET = "target"
        const val BT_PREFIX = "bt:"
        const val BT_NEW = "bt:new"
        private const val REQ_BLUETOOTH = 1
        private const val REQ_BLUETOOTH_FALLBACK = 2
        /** How long a Bluetooth keyboard stays connected after the screen goes off. */
        private const val PARK_MS = 20_000L
        private const val CUSTOM = "Custom"
    }
}
