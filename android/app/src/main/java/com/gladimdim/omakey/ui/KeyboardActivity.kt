package com.gladimdim.omakey.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Outline
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import kotlin.math.abs
import android.widget.FrameLayout
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.keyboard.Haptics
import com.gladimdim.omakey.keyboard.KeyLayouts
import com.gladimdim.omakey.keyboard.KeyboardView
import com.gladimdim.omakey.keyboard.UsKeys
import com.gladimdim.omakey.keyboard.TouchpadView
import android.widget.Toast
import com.gladimdim.omakey.net.BluetoothHidLink
import com.gladimdim.omakey.net.Discovery
import com.gladimdim.omakey.net.FallbackLink
import com.gladimdim.omakey.net.Found
import com.gladimdim.omakey.net.Link
import com.gladimdim.omakey.net.Waker
import com.gladimdim.omakey.protocol.Ack
import com.gladimdim.omakey.protocol.ClipTransfer
import com.gladimdim.omakey.protocol.DesktopTheme
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import com.gladimdim.omakey.protocol.Wire
import com.gladimdim.omakey.store.AppSettings
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
open class KeyboardActivity : Activity() {
    /**
     * Portrait mode ([PortraitKeyboardActivity]): the phone's own keyboard
     * at the bottom, typing into [ime], and the touchpad above it. No
     * layout and no sliding panel.
     */
    protected open val portrait = false

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
    /** The typed text strip; null when turned off in Settings. */
    private var typed: TypedTicker? = null
    /** Portrait mode: what the phone's keyboard types into, and its paced sender. */
    private var ime: ImeCapture? = null
    private var typist: Typist? = null
    /** Portrait mode: digits, F-keys, navigation and system keys above the phone's keyboard, a row you arrange over a row of pages. */
    private var keyStrip: KeyStrip? = null
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

    /** Wake-on-LAN packets sent since [connect]; the status then says "Waking". */
    private var wakesSent = 0
    /**
     * The computer doesn't answer: maybe it's asleep. Wake it, if it said it
     * wakes on LAN, and again a few times while it still doesn't answer.
     */
    private val wake = object : Runnable {
        override fun run() {
            val t = target as? Target.Omakey ?: return
            if (linkState == Link.State.CONNECTED || wakesSent >= WAKE_TRIES) return
            val mac = hosts.get(t.host.hostId)?.wakeMac ?: return
            if (!AppSettings(this@KeyboardActivity).wakeOnLan) return
            Waker.wake(this@KeyboardActivity, mac)
            wakesSent++
            renderStatus()
            handler.postDelayed(this, WAKE_EVERY_MS)
        }
    }

    private val prefs by lazy { getSharedPreferences("keyboard", MODE_PRIVATE) }
    private lateinit var stickyButton: TextView

    /** Copy and Paste, with the phone's clipboard when omakeyd has the computer's. */
    private val clipboard by lazy { ClipboardBridge(this, { link }, ::shortcut) { hostName } }

    private val sink = object : KeyboardModel.Sink {
        override fun keyDown(code: Int) {
            // A layout's Copy and Paste keys are the app's to do, not the computer's.
            when (code) {
                ClipboardBridge.KEY_COPY -> return clipboard.copy()
                ClipboardBridge.KEY_PASTE -> return clipboard.paste()
            }
            if (keys.press(code)) link?.send()
            typed?.keyDown(code)
        }

        override fun keyUp(code: Int) {
            if (code == ClipboardBridge.KEY_COPY || code == ClipboardBridge.KEY_PASTE) return
            if (keys.release(code)) link?.send()
            typed?.keyUp(code)
            // A key went out: Ctrl or Shift latched on the touchpad, Super or Alt on the key strip, were for it.
            if (code !in UsKeys.MODIFIERS) {
                touchpad.modifiersUsed()
                keyStrip?.modifiersUsed()
            }
        }
    }

    private val padSink = object : TouchpadView.Sink {
        override fun button(code: Int, down: Boolean) {
            if (if (down) keys.press(code) else keys.release(code)) link?.send()
            // Ctrl and Shift from the touchpad show in the typed text's shortcuts.
            if (code in UsKeys.MODIFIERS) if (down) typed?.keyDown(code) else typed?.keyUp(code)
            // A click let go: Super or Alt latched on the key strip was for it (Super + drag moves a window).
            if (!down && code in Wire.BTN_LEFT..Wire.BTN_MIDDLE) keyStrip?.modifiersUsed()
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
        Palette.load(this)
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
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            isClickable = true
            setOnClickListener { pickHost() }
        }

        // The pull-down handle, first in the top bar.
        handleLabel = text("⌄  touchpad", 12f, Palette.ACCENT, bold = true)
        val handle = FrameLayout(this).apply {
            background = rounded(Palette.SURFACE, dp(12f).toFloat(), Palette.ACCENT, dp(1f))
            addView(handleLabel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            // Tapped here; dragged anywhere on the top bar (see [top]).
            setOnClickListener { setPad(!padOpen) }
            contentDescription = "Touchpad: tap, or swipe the top bar down"
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(4f), dp(8f), 0)
            if (portrait) {
                // Narrow: the status takes the room between the buttons.
                addView(barButton("⌨") { ime?.show() }.apply { contentDescription = "Show the keyboard" })
                addView(FrameLayout(context).apply {
                    addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(iconButton(ClipIcon(paste = false, Palette.ACCENT, resources.displayMetrics.density)) { clipboard.copy() }
                    .apply { contentDescription = "Copy on the computer, to the phone too" })
                addView(iconButton(ClipIcon(paste = true, Palette.ACCENT, resources.displayMetrics.density)) { clipboard.paste() }
                    .apply { contentDescription = "Paste on the computer" })
            } else {
                addView(handle, LinearLayout.LayoutParams(dp(136f), dp(26f)))
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            }
            // Sticky keys are for the app's own keys.
            addView(barButton("") { toggleSticky() }.also {
                stickyButton = it
                it.contentDescription = "Sticky keys"
                if (portrait) it.visibility = View.GONE
            })
            addView(barButton("⇄") { pickHost() }.apply { contentDescription = "Switch computer" })
            addView(barButton("⌨") { pickLayout() }.apply { contentDescription = "Switch layout" })
            addView(barButton("✕") { finish() }.apply { contentDescription = "Close" })
        }
        val haptics = Haptics(this).apply { enabled = AppSettings(this@KeyboardActivity).haptics }
        keyboard = KeyboardView(this).apply {
            this.haptics = haptics
            // A quick swipe down on a key pulls the touchpad over the keyboard, as the top bar does.
            if (!portrait) pull = object : KeyboardView.Pull {
                override fun begin(rawY: Float) = dragBegin(rawY)
                override fun move(rawY: Float) = dragMove(rawY)
                override fun end(velocityY: Float) = dragEnd(velocityY)
            }
        }

        // The touchpad lives above the keyboard and slides down over it.
        touchpad = TouchpadView(this).apply {
            this.haptics = haptics
            sink = padSink
            onSensitivityChanged = { v ->
                padPrefs.edit().putFloat("sens:${target.id}", v).putString("preset:${target.id}", CUSTOM).apply()
                presetName = CUSTOM
            }
            onPresetsRequested = ::pickPreset
            compact = portrait
            // Swiping up from the bottom row drags the touchpad back up.
            if (!portrait) panelDrag = object : TouchpadView.PanelDrag {
                override fun drag(dy: Float) {
                    panelMoving = true
                    padAnim?.cancel()
                    fast(true)
                    setPanelY(dy.coerceIn(-stage.height.toFloat(), 0f))
                }

                override fun release(dy: Float, flungUp: Boolean) {
                    setPad(!(flungUp || -dy > stage.height * 0.25f))
                }
            }
        }
        panel = FrameLayout(this).apply {
            setBackgroundColor(Palette.BG)
            visibility = View.INVISIBLE
            // A sheet over the keyboard: a shadow, and rounded bottom corners while it moves.
            elevation = dp(20f).toFloat()
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) {
                    o.setRoundRect(0, -panelRadius.toInt() - 1, v.width, v.height, panelRadius)
                }
            }
            clipToOutline = true
            if (!portrait) addView(touchpad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        if (portrait) {
            val gate = object : LayoutGate {
                override fun current() = keys.layout
                // A Bluetooth keyboard can't change the computer's layout: nothing to wait for.
                override fun canSwitch() = link is BluetoothHidLink || keys.unacked == 0
                override fun switch(layout: String) {
                    keys.layout = layout
                }
            }
            val imm = getSystemService(InputMethodManager::class.java)
            val t = Typist(
                sink, gate,
                preferred = { KeyLayouts.preferred(imm.currentInputMethodSubtype?.languageTag?.ifEmpty { null }
                    ?: @Suppress("DEPRECATION") imm.currentInputMethodSubtype?.locale) },
                onChar = { c -> typed?.nextChar = c },
                // Hold back while omakeyd hasn't acknowledged most of what it can keep.
                busy = { link !is BluetoothHidLink && keys.unacked > Wire.MAX_EVENTS - 8 },
            )
            typist = t
            ime = ImeCapture(this, t) { keys.held().any { it in SHORTCUT_MODIFIERS } }
            stage = FrameLayout(this).apply {
                addView(ime, FrameLayout.LayoutParams(1, 1))
                addView(touchpad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            }
        } else stage = FrameLayout(this).apply {
            addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // Keep a closed panel parked just above the stage when its size
            // changes, but not on every layout pass (the status pill causes
            // those) or while it is being dragged or animated.
            addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
                if (!padOpen && !panelMoving && bottom - top != oldBottom - oldTop) {
                    setPanelY(-(bottom - top).toFloat())
                }
            }
        }

        // The connection status, centred in the top bar: as wide as the
        // space between the handle and the buttons, ellipsized past that.
        val top = FrameLayout(this).apply {
            addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (!portrait) addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { topMargin = dp(4f) })
        }
        if (!portrait) bar.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = right - left
            val side = maxOf(handle.right, width - stickyButton.left) + dp(8f)
            val max = (width - side * 2).coerceAtLeast(dp(80f))
            // Only on a change: setting it lays the bar out again.
            if (status.maxWidth != max) status.maxWidth = max
        }
        // The header: the top bar, a grip and the typed text. A vertical swipe
        // anywhere on it pulls the touchpad down or throws it back up; the
        // bar's buttons still take taps.
        val header = object : LinearLayout(this) {
            private val slop = ViewConfiguration.get(context).scaledTouchSlop
            private var x0 = 0f
            private var y0 = 0f
            private var dragging = false
            private val tracker = VelocityTracker.obtain()

            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (portrait) return false
                return watch(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (portrait) return false
                watch(ev)
                return true
            }

            /** True once the touch is a vertical drag: from then on it moves the panel. */
            private fun watch(ev: MotionEvent): Boolean {
                tracker.addMovement(ev)
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x0 = ev.rawX
                        y0 = ev.rawY
                        dragging = false
                        tracker.clear()
                        tracker.addMovement(ev)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - y0
                        if (!dragging && abs(dy) > slop && abs(dy) > abs(ev.rawX - x0)) {
                            dragging = true
                            dragBegin(y0)
                        }
                        if (dragging) dragMove(ev.rawY)
                    }
                    MotionEvent.ACTION_UP -> if (dragging) {
                        tracker.computeCurrentVelocity(1000)
                        dragEnd(tracker.yVelocity)
                        dragging = false
                    }
                    MotionEvent.ACTION_CANCEL -> if (dragging) {
                        dragEnd(0f)
                        dragging = false
                    }
                }
                return dragging
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            addView(top, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32f)))
            if (!portrait) addView(PullGrip(this@KeyboardActivity), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10f)))
            if (AppSettings(this@KeyboardActivity).typedText) {
                typed = TypedTicker(this@KeyboardActivity).also {
                    addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(22f)))
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(header, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // Portrait: clear of the camera cutout on top and of the phone's keyboard below.
            // Landscape: the grab bar under the touchpad stays above the system's home gesture zone.
            if (!portrait && Build.VERSION.SDK_INT >= 29) setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                touchpad.bottomInset = insets.mandatorySystemGestureInsets.bottom.toFloat()
                insets
            }
            if (portrait) {
                // The phone's keyboard hidden: a button in its place, so the touchpad keeps its size.
                val reopen = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    background = rounded(Palette.SURFACE, dp(16f).toFloat(), Palette.ACCENT, dp(1.5f))
                    isClickable = true
                    contentDescription = "Open the keyboard"
                    addView(text("⌨", 40f, Palette.ACCENT).apply { gravity = Gravity.CENTER })
                    addView(text("Tap to open the keyboard", 14f, Palette.FG, bold = true).apply {
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6f), 0, 0)
                    })
                    setOnClickListener { ime?.show() }
                }
                val reopenArea = FrameLayout(context).apply {
                    setPadding(dp(12f), dp(8f), dp(12f), dp(12f))
                    addView(reopen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                }
                // The upper row as arranged; the pages as last left, digits at first.
                keyStrip = KeyStrip(this@KeyboardActivity, typist!!, sink).also { strip ->
                    strip.haptics = haptics
                    strip.page = prefs.getInt("stripPage", 0)
                    strip.onPageChanged = { prefs.edit().putInt("stripPage", it).apply() }
                    strip.slotCodes = prefs.getString("stripSlots", "")!!.split(',').map { it.toIntOrNull() ?: 0 }
                    strip.onSlotsChanged = { prefs.edit().putString("stripSlots", it.joinToString(",")).apply() }
                    addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(96f)))
                }
                var keyboardHeight = (resources.displayMetrics.heightPixels * 0.38f).toInt()
                addView(reopenArea, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyboardHeight))
                // Clear of the camera cutout on top and of the phone's keyboard below.
                setOnApplyWindowInsetsListener { v, insets ->
                    val top: Int
                    val imeHeight: Int
                    if (Build.VERSION.SDK_INT >= 30) {
                        top = insets.getInsets(WindowInsets.Type.displayCutout()).top
                        imeHeight = insets.getInsets(WindowInsets.Type.ime()).bottom
                    } else {
                        top = insets.displayCutout?.safeInsetTop ?: 0
                        // Only the keyboard is that tall at the bottom.
                        @Suppress("DEPRECATION")
                        imeHeight = insets.systemWindowInsetBottom.takeIf { it > dp(120f) } ?: 0
                    }
                    if (imeHeight > 0) keyboardHeight = imeHeight
                    v.setPadding(0, top, 0, imeHeight)
                    reopenArea.visibility = if (imeHeight > 0) View.GONE else View.VISIBLE
                    if (reopenArea.layoutParams.height != keyboardHeight) {
                        reopenArea.layoutParams = reopenArea.layoutParams.apply { height = keyboardHeight }
                    }
                    insets
                }
            }
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
        stickyButton.text = "⇧"
        stickyButton.setTextColor(if (keyboard.sticky) Palette.ACCENT else Palette.FG_DIM)
    }

    private fun barButton(label: String, onClick: (View) -> Unit) = text(label, 13f, Palette.ACCENT, bold = true).apply {
        setPadding(dp(14f), dp(4f), dp(14f), dp(4f))
        isClickable = true
        setOnClickListener(onClick)
    }

    /** A top-bar button with a drawn icon, padded like [barButton]. */
    private fun iconButton(icon: android.graphics.drawable.Drawable, onClick: (View) -> Unit) = android.widget.ImageView(this).apply {
        setImageDrawable(icon)
        setPadding(dp(11f), dp(4f), dp(11f), dp(4f))
        isClickable = true
        setOnClickListener(onClick)
    }

    /**
     * Presses [modifier] + [key] on the computer, a step at a time: a
     * Bluetooth keyboard sends key state, not events, so each step must go
     * out on its own.
     */
    private fun shortcut(modifier: Int, key: Int) {
        val steps = listOf<() -> Boolean>({ keys.press(modifier) }, { keys.press(key) }, { keys.release(key) }, { keys.release(modifier) })
        steps.forEachIndexed { i, step ->
            handler.postDelayed({ if (step()) link?.send() }, i * SHORTCUT_STEP_MS)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            ime?.show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_TARGET, target.id)
    }

    private fun resolve(id: String): Target? = when {
        id.startsWith(BT_PREFIX) && !BLUETOOTH -> null
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

    private fun bluetoothGranted(): Boolean = BLUETOOTH && (
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)

    /** Start a connection to [target]. Callbacks from a replaced link are ignored. */
    private fun connect() {
        val t = target
        if (t is Target.Bluetooth && !bluetoothGranted()) {
            // Connected from onRequestPermissionsResult instead.
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQ_BLUETOOTH)
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
                        hosts.rememberWakeMac(t.host.hostId, (l as FallbackLink).wakeMac)
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
                if (link !== l) return@runOnUiThread
                keyboard.setCapsLock(leds and Ack.LED_CAPS != 0)
                typed?.capsLock = leds and Ack.LED_CAPS != 0
            }

            override fun onClip(outcome: ClipTransfer.Outcome) = runOnUiThread {
                if (link !== l) return@runOnUiThread
                clipboard.onOutcome(outcome)
            }

            override fun onTheme(theme: DesktopTheme) = runOnUiThread {
                if (link !== l) return@runOnUiThread
                val settings = AppSettings(this@KeyboardActivity)
                if (settings.desktopTheme == theme && settings.desktopThemeFrom == hostName) return@runOnUiThread
                settings.desktopTheme = theme
                settings.desktopThemeFrom = hostName
                // Following the computer: take its new colours now. The link
                // reconnects in a moment and the theme then matches.
                if (settings.themeId == Palette.FROM_COMPUTER) recreate()
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
        handler.removeCallbacks(wake)
        wakesSent = 0
        if (t is Target.Omakey) handler.postDelayed(wake, WAKE_AFTER_MS)
    }

    /**
     * The computer turned out to have Bluetooth: offer the fallback, once per
     * computer, if the permission isn't there yet.
     */
    private fun askForBluetoothFallback(hostId: String) {
        if (!BLUETOOTH || bluetoothGranted() || prefs.getBoolean("btAsked:$hostId", false)) return
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
        typed?.capsLock = false
        typed?.clear()
        // The other computer's cursor is somewhere else.
        typist?.clear()
        ime?.reset()
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
            (if (BLUETOOTH) btHosts.all() else emptyList()).map { Target.Bluetooth(it.address, it.name) }
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
            val label = h.name + "  ·  " + detail
            val viaBluetooth = h is Target.Bluetooth || (current && link?.transport == "Bluetooth")
            TextUtils.concat(if (current) "● " else "   ",
                if (viaBluetooth) bluetooth(label, Palette.ACCENT, dp(16f).toFloat()) else label)
        }.toTypedArray()

        AlertDialog.Builder(this, Palette.dialogTheme)
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
        typist?.clear()
        keyStrip?.reset()
        // Never leave a key held on the computer while we're not looking.
        keyboard.releaseAll()
        touchpad.releaseAll()
        keys.releaseAll()
        link?.send()
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(wake)
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
    /** How much of the panel showed when the drag began, px. */
    private var dragStartShown = 0f
    private var padAnim: ValueAnimator? = null
    /** Rounding of the panel's bottom corners: full while it flies, none once it covers the keyboard. */
    private var panelRadius = 0f

    private fun dragBegin(rawY: Float) {
        val h = stage.height.toFloat()
        if (h <= 0f) return
        padAnim?.cancel()
        fast(true)
        panelMoving = true
        panel.visibility = View.VISIBLE
        dragStartY = rawY
        dragStartShown = panel.translationY + h
    }

    private fun dragMove(rawY: Float) {
        val h = stage.height.toFloat()
        setPanelY((dragStartShown + rawY - dragStartY).coerceIn(0f, h) - h)
    }

    /** Let go: a flick decides, else whichever is nearer; the flick's speed carries on. */
    private fun dragEnd(velocityY: Float) {
        val h = stage.height.toFloat()
        val shown = panel.translationY + h
        val flick = FLICK_DP_PER_S * resources.displayMetrics.density
        val open = when {
            velocityY > flick -> true
            velocityY < -flick -> false
            else -> shown > h / 2
        }
        setPad(open, velocityY)
    }

    /**
     * Moves the panel and gives the depth: as it covers the keyboard, the
     * keyboard sinks back (smaller, dimmer) and the panel widens to full
     * and loses its rounded corners, as a sheet landing over it.
     */
    private fun setPanelY(y: Float) {
        panel.translationY = y
        val h = stage.height.toFloat()
        if (h <= 0f) return
        val p = ((y + h) / h).coerceIn(0f, 1f)
        keyboard.pivotY = keyboard.height * 0.6f
        keyboard.scaleX = 1f - 0.07f * p
        keyboard.scaleY = 1f - 0.07f * p
        keyboard.alpha = 1f - 0.6f * p
        panel.scaleX = 0.94f + 0.06f * p
        panelRadius = dp(28f) * (1f - p)
        panel.invalidateOutline()
    }

    /** The display's highest refresh rate while the panel moves (Android 15+). */
    private fun fast(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) {
            panel.requestedFrameRate = if (on) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
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
        AlertDialog.Builder(this, Palette.dialogTheme)
            .setTitle("Pointer speed for ${target.name}")
            .setItems(labels) { _, i ->
                val p = presets[i]
                padPrefs.edit().putFloat("sens:${target.id}", p.sensitivity).putString("preset:${target.id}", p.name).apply()
                applyPadSettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Fly the panel over the keyboard ([open]) or away above it. [velocity]:
     * the finger's speed when let go, px/s, so the motion carries on from it.
     */
    private fun setPad(open: Boolean, velocity: Float = 0f) {
        val h = stage.height.toFloat()
        if (open && !padOpen) keyboard.releaseAll()
        if (!open && padOpen) touchpad.releaseAll()
        padOpen = open
        handleLabel.text = if (open) "⌃  keyboard" else "⌄  touchpad"
        panel.visibility = View.VISIBLE
        panelMoving = true
        fast(true)
        padAnim?.cancel()
        val from = panel.translationY
        val to = if (open) 0f else -h
        val distance = abs(to - from)
        padAnim = ValueAnimator.ofFloat(from, to).apply {
            duration = if (abs(velocity) > 1f) (distance / abs(velocity) * 1000 * 2.2f).toLong().coerceIn(160, 360)
            else (220 + 140 * distance / h.coerceAtLeast(1f)).toLong()
            // Fast at first, easing to rest: picks up the flick's speed.
            interpolator = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
            addUpdateListener { setPanelY(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled) return
                    panelMoving = false
                    fast(false)
                    if (!padOpen) panel.visibility = View.INVISIBLE
                }
            })
            start()
        }
    }

    @Deprecated("Back closes the touchpad first")
    override fun onBackPressed() {
        if (padOpen) setPad(false) else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun pickLayout() {
        val page = if (portrait) LayoutsActivity::class.java else LandscapeLayoutsActivity::class.java
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, page), REQ_LAYOUT)
    }

    @Deprecated("Plain Activity: the layouts page answers here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_LAYOUT || resultCode != RESULT_OK) return
        if (layouts.portrait != portrait) {
            // Into or out of portrait mode: the other screen, same computer.
            startActivity(intent(this, target.id, layouts))
            finish()
            return
        }
        // Keys were already let go in onPause.
        keyboard.setLayout(layouts.selected(), sink)
    }

    private fun renderStatus() {
        // Wi-Fi is the usual way and goes unsaid; anything else is named.
        val via = link?.transport?.takeIf { it != "Wi-Fi" }?.let { " · $it" } ?: ""
        val (dot, color, msg) = when (linkState) {
            Link.State.CONNECTED ->
                Triple("●", Palette.OK, hostName + via + if (pingMs >= 0) " · $pingMs ms" else "")
            Link.State.CONNECTING ->
                Triple("●", Palette.WARN, if (wakesSent > 0) "Waking $hostName…" else "Connecting to $hostName…")
            Link.State.REJECTED -> Triple("●", Palette.ERROR, "$hostName doesn't know this phone. Pair again.")
            Link.State.WAITING ->
                Triple("●", Palette.WARN, "On the computer, open Bluetooth settings and pair with “${phoneName()}”")
            Link.State.UNSUPPORTED ->
                Triple("●", Palette.ERROR, "This phone can't be a Bluetooth keyboard (or Bluetooth is off)")
        }
        // The phone as a Bluetooth keyboard, or omakeyd over its Bluetooth fallback.
        val viaBluetooth = link is BluetoothHidLink || link?.transport == "Bluetooth"
        status.text = TextUtils.concat("$dot  ", if (viaBluetooth) bluetooth(msg, color, status.textSize) else msg)
        status.setTextColor(color)
    }

    companion object {
        /** Opens the keyboard for [target]: landscape with a layout, or portrait mode. */
        fun intent(context: android.content.Context, target: String, layouts: LayoutStore): Intent =
            Intent(context, if (layouts.portrait) PortraitKeyboardActivity::class.java else KeyboardActivity::class.java)
                .putExtra(EXTRA_TARGET, target)

        /** An omakeyd host id, [BT_PREFIX] + a Bluetooth address, or [BT_NEW]. */
        const val EXTRA_TARGET = "target"
        const val BT_PREFIX = "bt:"
        const val BT_NEW = "bt:new"
        private const val REQ_BLUETOOTH = 1
        private const val REQ_BLUETOOTH_FALLBACK = 2
        private const val REQ_LAYOUT = 3
        /** How long a Bluetooth keyboard stays connected after the screen goes off. */
        private const val PARK_MS = 20_000L
        /**
         * No answer this long after connecting: send a Wake-on-LAN packet. An
         * awake computer answers within milliseconds; a slow Wi-Fi start
         * can take a second, and isn't worth calling "Waking".
         */
        private const val WAKE_AFTER_MS = 2_000L
        private const val WAKE_EVERY_MS = 5_000L
        private const val WAKE_TRIES = 6
        private const val CUSTOM = "Custom"
        /** A swipe of the top bar faster than this throws the touchpad that way. */
        private const val FLICK_DP_PER_S = 400f
        /** Between the presses and releases of a Copy or Paste shortcut. */
        private const val SHORTCUT_STEP_MS = 25L
        /** Ctrl, Alt and Super, left and right: held, a key makes a shortcut. */
        private val SHORTCUT_MODIFIERS = setOf(29, 97, 56, 100, 125, 126)
    }
}

/** Portrait mode: the phone's own keyboard below, the touchpad above. Declared portrait in the manifest. */
class PortraitKeyboardActivity : KeyboardActivity() {
    override val portrait = true
}

/** Under the top bar: a handle with arrows pointing down, to show the bar pulls the touchpad down. */
private class PullGrip(context: android.content.Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.6f * density
        strokeCap = android.graphics.Paint.Cap.ROUND
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val hw = 18 * density
        fill.color = Palette.FG_DIM
        canvas.drawRoundRect(cx - hw, cy - 1.5f * density, cx + hw, cy + 1.5f * density, 1.5f * density, 1.5f * density, fill)
        // ⌄ either side of the handle.
        stroke.color = Palette.FG_DIM
        val a = 3.5f * density
        for (x in listOf(cx - hw - 12 * density, cx + hw + 12 * density)) {
            canvas.drawLine(x - a, cy - a / 2, x, cy + a / 2, stroke)
            canvas.drawLine(x, cy + a / 2, x + a, cy - a / 2, stroke)
        }
    }
}

