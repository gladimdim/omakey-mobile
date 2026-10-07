package com.gladimdim.omakey.ui

import android.Manifest
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.gladimdim.omakey.layout.LayoutException
import com.gladimdim.omakey.layout.LayoutLink
import com.gladimdim.omakey.layout.LayoutParser
import com.gladimdim.omakey.net.Discovery
import com.gladimdim.omakey.net.Found
import com.gladimdim.omakey.net.Reachability
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.PairingException
import com.gladimdim.omakey.protocol.PairingUri
import com.gladimdim.omakey.store.AppSettings
import com.gladimdim.omakey.store.BtHostStore
import com.gladimdim.omakey.store.HostStore
import com.gladimdim.omakey.store.LayoutStore
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.net.InetSocketAddress

/**
 * Connect screen: paired computers, computers nearby, pairing by QR code or
 * pasted link, computers that use the phone as a Bluetooth keyboard, and
 * the layouts page. Also handles `omakey://` links and
 * shared layout files.
 */
class MainActivity : ComponentActivity() {
    private lateinit var hosts: HostStore
    private lateinit var btHosts: BtHostStore
    private lateinit var layouts: LayoutStore
    private lateinit var discovery: Discovery
    private var nearby: List<Found> = emptyList()
    private lateinit var reach: Reachability
    /** Paired computers by host id: the address that answered, or null when none did. Absent: not checked yet. */
    private var online: Map<String, InetSocketAddress?> = emptyMap()

    private lateinit var pairedList: LinearLayout
    private lateinit var nearbyList: LinearLayout
    private lateinit var btList: LinearLayout
    private lateinit var layoutButton: TextView
    /** The theme the screen was built with; Settings may change it. */
    private var builtTheme = ""

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleText(it) }
    }

    /**
     * The keyboard to open once the permission dialog is answered. Kept in
     * the saved state: the activity may be recreated while the dialog shows.
     */
    private var pendingOpen: String? = null
    /** Whether [pendingOpen] can't work without Bluetooth (a Bluetooth keyboard, not the fallback). */
    private var pendingNeedsBluetooth = false
    private val bluetoothPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val target = pendingOpen ?: return@registerForActivityResult
        pendingOpen = null
        if (bluetoothGranted() || !pendingNeedsBluetooth) openKeyboard(target)
        else toast("Bluetooth needs the Nearby devices permission")
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importLayoutFrom(it) }
    }

    private val layoutsPage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == LayoutsActivity.RESULT_IMPORT) {
            openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.load(this)
        builtTheme = Palette.theme.id
        hosts = HostStore(this)
        btHosts = BtHostStore(this)
        layouts = LayoutStore(this)
        discovery = Discovery(this) { nearby = it; render() }
        reach = Reachability(phoneName()) { online = it; render() }
        buildUi()
        pendingOpen = savedInstanceState?.getString(STATE_PENDING)
        pendingNeedsBluetooth = savedInstanceState?.getBoolean(STATE_PENDING_BT) ?: false
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING, pendingOpen)
        outState.putBoolean(STATE_PENDING_BT, pendingNeedsBluetooth)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (AppSettings(this).themeId != builtTheme) return recreate()
        render()
        discovery.start()
        reach.start()
    }

    override fun onStop() {
        discovery.stop()
        reach.stop()
        super.onStop()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
        }
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Omakey", 34f, Palette.FG, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("⚙", 26f, Palette.ACCENT).apply {
                setPadding(dp(12f), dp(4f), 0, dp(4f))
                isClickable = true
                contentDescription = "Settings"
                setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            })
        })
        col.addView(text("Your phone is the keyboard.", 15f, Palette.FG_DIM).apply { setPadding(0, dp(4f), 0, 0) })

        col.addView(section("SELECT COMPUTER TO USE"))
        pairedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pairedList)

        col.addView(section("NEARBY"))
        nearbyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(nearbyList)

        col.addView(section("PAIR A COMPUTER"))
        col.addView(text(
            "On Omarchy, click the keyboard icon in the bar and choose Pair a phone, or run `omakeyd pair` in a terminal. Then scan the code.",
            13f, Palette.FG_DIM,
        ).apply { setPadding(0, 0, 0, dp(12f)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Scan QR code", primary = true) { startScan() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        row.addView(button("Paste link") { pasteLink() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        col.addView(section("BLUETOOTH KEYBOARD"))
        col.addView(text(
            "Any computer, tablet or TV, no omakeyd needed: the phone becomes a Bluetooth keyboard and touchpad.",
            13f, Palette.FG_DIM,
        ).apply { setPadding(0, 0, 0, dp(12f)) })
        btList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(btList)
        col.addView(button("Pair over Bluetooth") { openWithBluetooth(KeyboardActivity.BT_NEW) })

        col.addView(section("LAYOUT"))
        layoutButton = button("") { pickLayout() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        col.addView(layoutButton)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            padForCutout()
            addView(col)
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun render() {
        val paired = hosts.all()
        val nearbyById = nearby.filter { it.hostId != null }.associateBy { it.hostId }

        pairedList.removeAllViews()
        if (paired.isEmpty()) {
            pairedList.addView(text("No computers yet.", 14f, Palette.FG_DIM))
        }
        reach.update(paired, nearby)
        for (h in paired) {
            val answered = online[h.hostId]
            val address = answered?.address?.hostAddress ?: nearbyById[h.hostId]?.address?.address?.hostAddress ?: h.addresses.first()
            val detail = when {
                answered != null -> "● Online · $address"
                h.hostId !in online -> "Checking… · $address"
                h.btAddress != null -> "○ Not on this Wi-Fi · will try Bluetooth"
                else -> "○ Offline · $address"
            }
            val color = if (answered != null) Palette.OK else Palette.FG_DIM
            pairedList.addView(card(h.name, detail, color, border = if (answered != null) Palette.OK else null).apply {
                setOnClickListener { openKeyboard(h) }
                setOnLongClickListener { confirmUnlink(h, hosts, ::render); true }
            })
        }

        nearbyList.removeAllViews()
        val pairedIds = paired.map { it.hostId }.toSet()
        val strangers = nearby.filter { it.hostId !in pairedIds }
        if (strangers.isEmpty()) {
            nearbyList.addView(text("Looking for computers running omakeyd…", 14f, Palette.FG_DIM))
        }
        for (f in strangers) {
            nearbyList.addView(card(f.name, "Not paired. Scan its pairing code to connect.").apply {
                setOnClickListener { startScan() }
            })
        }

        btList.removeAllViews()
        for (b in btHosts.all()) {
            btList.addView(card(b.name, "Bluetooth keyboard").apply {
                setOnClickListener { openWithBluetooth(KeyboardActivity.BT_PREFIX + b.address) }
                setOnLongClickListener { confirmUnlink(b, btHosts, ::render); true }
            })
        }

        layoutButton.text = "⌨  ${LayoutsActivity.currentName(layouts)}"
    }

    private fun startScan() {
        scan.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the Omakey pairing code")
            setBeepEnabled(false)
            setOrientationLocked(false)
        })
    }

    private fun pasteLink() {
        val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) toast("The clipboard is empty. Copy the pairing link first.")
        else handleText(text)
    }

    /** The layouts page; [render] in onStart picks up a new selection. */
    private fun pickLayout() {
        layoutsPage.launch(Intent(this, LayoutsActivity::class.java).putExtra(LayoutsActivity.EXTRA_CAN_IMPORT, true))
    }

    private fun openKeyboard(h: HostRecord) = openKeyboard(h.hostId)

    private fun openKeyboard(target: String) {
        startActivity(KeyboardActivity.intent(this, target, layouts))
    }

    private fun bluetoothGranted(): Boolean =
        Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Open a Bluetooth keyboard, asking for the Nearby devices permission (Android 12+) first if needed. */
    private fun openWithBluetooth(target: String) {
        if (bluetoothGranted()) return openKeyboard(target)
        pendingOpen = target
        pendingNeedsBluetooth = true
        bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE))
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data
                // A layout file opened from a file manager arrives as content://.
                if (data?.scheme == "content") importLayoutFrom(data)
                else intent.dataString?.let { handleText(it, fromOutside = true) }
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    stream != null -> importLayoutFrom(stream)
                    text != null -> handleText(text, fromOutside = true)
                }
            }
        }
    }

    /**
     * A pairing link, a layout link, or layout JSON. [fromOutside]: it came
     * from a web page or another app rather than this app's own scanner or
     * clipboard button.
     */
    private fun handleText(raw: String, fromOutside: Boolean = false) {
        val text = raw.trim()
        when {
            text.startsWith("omakey://pair") -> try {
                confirmPairing(PairingUri.parse(text), fromOutside)
            } catch (e: PairingException) {
                toast(e.message ?: "Bad pairing link")
            }
            LayoutLink.isLayoutLink(text) -> importLayout { LayoutLink.decode(text) }
            text.startsWith("{") -> importLayout { text }
            else -> toast("That isn't an Omakey pairing or layout link")
        }
    }

    private fun importLayoutFrom(uri: Uri) = importLayout {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > LayoutParser.MAX_BYTES) throw LayoutException("Layout is larger than 256 KB")
            }
            out.toString(Charsets.UTF_8.name())
        } ?: throw LayoutException("Can't read that file")
    }

    /**
     * A pairing link is that phone's credential for a computer, and a link
     * from anywhere can claim to be any computer. Show what it is first, with
     * the fingerprint omakeyd shows under its QR code, and warn loudly when
     * it would replace an existing pairing with a different key.
     */
    private fun confirmPairing(host: HostRecord, fromOutside: Boolean) {
        val existing = hosts.get(host.hostId)
        val replaces = existing != null && !(existing.key.contentEquals(host.key) && existing.deviceId.contentEquals(host.deviceId))
        val message = buildString {
            append("Computer: ${host.name}\n")
            append("Addresses: ${host.addresses.joinToString(", ")}\n")
            host.btAddress?.let { append("Bluetooth: $it\n") }
            append("\nFingerprint: ${host.fingerprint}\n")
            append("It must match the code under the QR code on your computer.")
            if (replaces) {
                append("\n\n⚠ This replaces your existing pairing with ${existing!!.name}. ")
                append("Only continue if you just paired again on that computer: otherwise someone may be trying ")
                append("to receive what you type.")
            } else if (fromOutside) {
                append("\n\nThis link came from another app or a web page.")
            }
        }
        AlertDialog.Builder(this, Palette.dialogTheme)
            .setTitle(if (replaces) "Replace pairing?" else "Pair with ${host.name}?")
            .setMessage(message)
            .setPositiveButton(if (replaces) "Replace" else "Pair") { _, _ -> pair(host) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pair(host: HostRecord) {
        hosts.put(host)
        toast("Paired with ${host.name}")
        render()
        // The computer has Bluetooth: allow it, so the keyboard keeps
        // working off Wi-Fi. Declining only loses that fallback.
        if (host.btAddress != null && !bluetoothGranted()) {
            pendingOpen = host.hostId
            pendingNeedsBluetooth = false
            bluetoothPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
        } else {
            openKeyboard(host)
        }
    }

    /** Validate, show the layout, and only then save it. */
    private fun importLayout(read: () -> String) {
        val json: String
        val layout = try {
            json = read()
            layouts.preview(json)
        } catch (e: LayoutException) {
            return toast(e.message ?: "Bad layout")
        } catch (e: java.io.IOException) {
            return toast("Can't read that file")
        }
        LayoutPreview.show(this, layout, layouts.importedWithId(layout.id)) {
            try {
                layouts.import(json)
                toast("Imported \"${layout.name}\"")
                render()
            } catch (e: LayoutException) {
                toast(e.message ?: "Bad layout")
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private companion object {
        const val STATE_PENDING = "pendingOpen"
        const val STATE_PENDING_BT = "pendingNeedsBluetooth"
    }
}
