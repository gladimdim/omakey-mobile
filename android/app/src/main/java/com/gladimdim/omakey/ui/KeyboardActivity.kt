package com.gladimdim.omakey.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.keyboard.KeyboardView
import com.gladimdim.omakey.net.Discovery
import com.gladimdim.omakey.net.Found
import com.gladimdim.omakey.net.KeyboardLink
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.KeyState
import com.gladimdim.omakey.store.HostStore
import com.gladimdim.omakey.store.LayoutStore

/**
 * The keyboard: landscape, fullscreen, screen kept on. While it is visible
 * it holds a low-latency Wi-Fi lock and a live session with the computer.
 * The computer can be switched from here without leaving the keyboard.
 */
class KeyboardActivity : Activity() {
    private lateinit var host: HostRecord
    private lateinit var hosts: HostStore
    private lateinit var layouts: LayoutStore
    private lateinit var keyboard: KeyboardView
    private lateinit var status: TextView

    private val keys = KeyState()
    private var link: KeyboardLink? = null
    private var discovery: Discovery? = null
    private var nearby: List<Found> = emptyList()
    private var wifiLock: WifiManager.WifiLock? = null

    private var linkState = KeyboardLink.State.CONNECTING
    private var hostName = ""
    private var pingMs = -1

    private val sink = object : KeyboardModel.Sink {
        override fun keyDown(code: Int) {
            if (keys.press(code)) link?.wake()
        }

        override fun keyUp(code: Int) {
            if (keys.release(code)) link?.wake()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hosts = HostStore(this)
        layouts = LayoutStore(this)
        val hostId = savedInstanceState?.getString(EXTRA_HOST) ?: intent.getStringExtra(EXTRA_HOST)
        host = hostId?.let(hosts::get) ?: run { finish(); return }
        hostName = host.name

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
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
            addView(barButton("⇄ PC") { pickHost() })
            addView(barButton("⌨ Layout") { pickLayout() })
            addView(barButton("✕") { finish() })
        }
        keyboard = KeyboardView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(32f)))
            addView(keyboard, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            // Keep keys out from under a notch or rounded corners.
            setOnApplyWindowInsetsListener { v, insets ->
                val c = insets.displayCutout
                v.setPadding(c?.safeInsetLeft ?: 0, 0, c?.safeInsetRight ?: 0, c?.safeInsetBottom ?: 0)
                insets
            }
        }
        setContentView(root)
        keyboard.setLayout(layouts.selected(), sink)
        renderStatus()
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
        outState.putString(EXTRA_HOST, host.hostId)
    }

    override fun onStart() {
        super.onStart()
        acquireWifiLock()
        connect()
        // The computer may have a new address; mDNS finds it by host id. The
        // list also tells the computer switcher which ones are around.
        discovery = Discovery(this) { found ->
            nearby = found
            routeCandidates()
        }.also { it.start() }
    }

    /** Start a session with [host]. Callbacks from a replaced link are ignored. */
    private fun connect() {
        val target = host
        lateinit var l: KeyboardLink
        l = KeyboardLink(target, phoneName(), keys, object : KeyboardLink.Listener {
            override fun onState(state: KeyboardLink.State, hostName: String?) = runOnUiThread {
                if (link !== l) return@runOnUiThread
                linkState = state
                if (hostName != null) this@KeyboardActivity.hostName = hostName
                if (state == KeyboardLink.State.CONNECTED) {
                    l.peer?.address?.hostAddress?.let { hosts.rememberAddress(target.hostId, it) }
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
        })
        link = l
        l.start()
        routeCandidates()
    }

    private fun routeCandidates() {
        val l = link ?: return
        nearby.filter { it.hostId == host.hostId }.forEach { l.addCandidate(it.address) }
    }

    /** Move the keyboard to another paired computer. */
    private fun switchTo(next: HostRecord) {
        if (next.hostId == host.hostId) return
        // Let go of everything first: the old computer gets BYE and releases
        // whatever was held; nothing carries over to the new one.
        keyboard.releaseAll()
        keys.releaseAll()
        link?.stop()
        link = null
        host = next
        hostName = next.name
        linkState = KeyboardLink.State.CONNECTING
        pingMs = -1
        hosts.put(next) // most recently used first on the connect screen
        renderStatus()
        connect()
    }

    private fun pickHost() {
        val all = hosts.all()
        val nearbyIds = nearby.mapNotNull { it.hostId }.toSet()
        val labels = all.map { h ->
            val current = h.hostId == host.hostId
            val detail = when {
                current && linkState == KeyboardLink.State.CONNECTED ->
                    if (pingMs >= 0) "connected · $pingMs ms" else "connected"
                current -> "connecting…"
                h.hostId in nearbyIds -> "nearby"
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
        keys.releaseAll()
        link?.wake()
        super.onPause()
    }

    override fun onStop() {
        discovery?.stop()
        discovery = null
        link?.stop()
        link = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        linkState = KeyboardLink.State.CONNECTING
        super.onStop()
    }

    private fun acquireWifiLock() {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        // Keeps the radio out of power save: otherwise packets can wait 100+ ms.
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "omakey:keyboard").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun pickLayout() {
        LayoutPicker.show(this, layouts, onImport = null) {
            keyboard.releaseAll()
            keys.releaseAll()
            link?.wake()
            keyboard.setLayout(layouts.selected(), sink)
        }
    }

    private fun renderStatus() {
        val (dot, color, msg) = when (linkState) {
            KeyboardLink.State.CONNECTED ->
                Triple("●", Palette.OK, if (pingMs >= 0) "$hostName · $pingMs ms" else hostName)
            KeyboardLink.State.CONNECTING -> Triple("●", Palette.WARN, "Connecting to $hostName…")
            KeyboardLink.State.REJECTED -> Triple("●", Palette.ERROR, "$hostName doesn't know this phone. Pair again.")
        }
        status.text = "$dot  $msg"
        status.setTextColor(color)
    }

    private fun phoneName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        const val EXTRA_HOST = "host"
    }
}
