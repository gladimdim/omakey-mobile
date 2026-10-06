package com.gladimdim.omakey.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
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
import com.gladimdim.omakey.protocol.HostRecord
import com.gladimdim.omakey.protocol.PairingException
import com.gladimdim.omakey.protocol.PairingUri
import com.gladimdim.omakey.store.HostStore
import com.gladimdim.omakey.store.LayoutStore
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Connect screen: paired computers, computers nearby, pairing by QR code or
 * pasted link, and the layout picker. Also handles `omakey://` links and
 * shared layout files.
 */
class MainActivity : ComponentActivity() {
    private lateinit var hosts: HostStore
    private lateinit var layouts: LayoutStore
    private lateinit var discovery: Discovery
    private var nearby: List<Found> = emptyList()

    private lateinit var pairedList: LinearLayout
    private lateinit var nearbyList: LinearLayout
    private lateinit var layoutButton: TextView

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { handleText(it) }
    }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importLayoutFrom(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hosts = HostStore(this)
        layouts = LayoutStore(this)
        discovery = Discovery(this) { nearby = it; render() }
        buildUi()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        render()
        discovery.start()
    }

    override fun onStop() {
        discovery.stop()
        super.onStop()
    }

    private fun buildUi() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(32f), dp(24f), dp(32f))
        }
        col.addView(text("Omakey", 34f, Palette.FG, bold = true))
        col.addView(text("Your phone is the keyboard.", 15f, Palette.FG_DIM).apply { setPadding(0, dp(4f), 0, 0) })

        col.addView(section("PAIRED COMPUTERS"))
        pairedList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(pairedList)

        col.addView(section("NEARBY"))
        nearbyList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(nearbyList)

        col.addView(section("PAIR A COMPUTER"))
        col.addView(text(
            "On Omarchy, click the keyboard icon in the bar and choose Pair phone, or run `omakeyd pair` in a terminal. Then scan the code.",
            13f, Palette.FG_DIM,
        ).apply { setPadding(0, 0, 0, dp(12f)) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Scan QR code", primary = true) { startScan() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8f) })
        row.addView(button("Paste link") { pasteLink() },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        col.addView(row)

        col.addView(section("LAYOUT"))
        layoutButton = button("") { pickLayout() }.apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
        col.addView(layoutButton)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Palette.BG)
            fitsSystemWindows = true
            addView(col)
        })
    }

    private fun render() {
        val paired = hosts.all()
        val nearbyById = nearby.filter { it.hostId != null }.associateBy { it.hostId }

        pairedList.removeAllViews()
        if (paired.isEmpty()) {
            pairedList.addView(text("No computers yet.", 14f, Palette.FG_DIM))
        }
        for (h in paired) {
            val seen = nearbyById[h.hostId]
            val detail = if (seen != null) "● nearby · ${seen.address.address.hostAddress}" else h.addresses.first()
            pairedList.addView(card(h.name, detail, if (seen != null) Palette.OK else Palette.FG_DIM).apply {
                setOnClickListener { openKeyboard(h) }
                setOnLongClickListener { confirmForget(h); true }
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

        layoutButton.text = "⌨  ${layouts.selected().name}"
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

    private fun pickLayout() {
        LayoutPicker.show(this, layouts, onImport = {
            openFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }) { render() }
    }

    private fun confirmForget(h: HostRecord) {
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Forget ${h.name}?")
            .setMessage("This phone will need a new pairing code to connect again. Also remove it on the computer with `omakeyd forget`.")
            .setPositiveButton("Forget") { _, _ -> hosts.remove(h.hostId); render() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openKeyboard(h: HostRecord) {
        startActivity(Intent(this, KeyboardActivity::class.java).putExtra(KeyboardActivity.EXTRA_HOST, h.hostId))
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { handleText(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                when {
                    stream != null -> importLayoutFrom(stream)
                    text != null -> handleText(text)
                }
            }
        }
    }

    /** A pairing link, a layout link, or layout JSON. */
    private fun handleText(raw: String) {
        val text = raw.trim()
        when {
            text.startsWith("omakey://pair") -> try {
                val host = PairingUri.parse(text)
                hosts.put(host)
                toast("Paired with ${host.name}")
                render()
                openKeyboard(host)
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

    private fun importLayout(read: () -> String) {
        try {
            val layout = layouts.import(read())
            toast("Imported \"${layout.name}\"")
            render()
        } catch (e: LayoutException) {
            toast(e.message ?: "Bad layout")
        } catch (e: java.io.IOException) {
            toast("Can't read that file")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
