package com.gladimdim.omakey.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gladimdim.omakey.keyboard.Haptics
import com.gladimdim.omakey.store.AppSettings
import com.gladimdim.omakey.store.BtHostStore
import com.gladimdim.omakey.store.HostStore
import com.gladimdim.omakey.store.LayoutStore

/**
 * Settings: the layout keyboards open with, the theme, the linked computers
 * (with a way to unlink each), the typed text strip and haptics.
 */
class SettingsActivity : Activity() {
    private lateinit var settings: AppSettings
    private lateinit var layouts: LayoutStore
    private lateinit var hosts: HostStore
    private lateinit var btHosts: BtHostStore
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.load(this)
        settings = AppSettings(this)
        layouts = LayoutStore(this)
        hosts = HostStore(this)
        btHosts = BtHostStore(this)

        val back = text("←", 22f, Palette.ACCENT, bold = true).apply {
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            isClickable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(12f), dp(24f), dp(4f))
            addView(back)
            addView(text("Settings", 24f, Palette.FG, bold = true))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), 0, dp(24f), dp(32f))
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            padForCutout()
            addView(header)
            addView(ScrollView(this@SettingsActivity).apply { addView(list) },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onStart() {
        super.onStart()
        // The layout may have changed on the layouts page.
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun render() {
        list.removeAllViews()

        list.addView(section("DEFAULT LAYOUT"))
        list.addView(card(
            "⌨  ${LayoutsActivity.currentName(layouts)}",
            "Every keyboard opens with it. Switch any time with ⌨ on the keyboard.",
        ).apply {
            setOnClickListener { startActivity(Intent(this@SettingsActivity, LayoutsActivity::class.java)) }
        })

        list.addView(section("THEME"))
        renderThemes()

        list.addView(section("COMPUTERS"))
        renderComputers()

        list.addView(section("KEYBOARD"))
        list.addView(typedTextRow())
        list.addView(hapticsRow())

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            null
        }
        list.addView(text("Omakey ${version ?: ""}".trim(), 12f, Palette.FG_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(32f), 0, 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Two per row, each drawn in its own colours. First, the computer's own
     * Omarchy theme, as it last came over with the keyboard.
     */
    private fun renderThemes() {
        val current = Palette.theme.id
        val desktop = settings.desktopTheme
        val fromComputer = Palette.fromComputer(desktop)
        val detail = if (desktop != null) "${fromComputer.name} · ${settings.desktopThemeFrom ?: "computer"}"
        else "Type on a computer to fetch its theme"
        val tiles = listOf(fromComputer to detail) + Palette.THEMES.map { it to null }
        for (rowThemes in tiles.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowThemes.forEachIndexed { i, (t, sub) ->
                val title = if (sub != null) "⇄ From computer" else t.name
                row.addView(themeTile(t, title, sub, t.id == current), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(10f)
                    bottomMargin = dp(10f)
                })
            }
            if (rowThemes.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(10f) })
            list.addView(row)
        }
    }

    private fun themeTile(t: Theme, title: String, sub: String?, selected: Boolean): View {
        val radius = dp(10f).toFloat()
        val swatches = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10f), 0, 0)
            for (c in listOf(t.key, t.keyAccent, t.accent, t.layer, t.ok, t.error)) {
                addView(View(this@SettingsActivity).apply { background = rounded(c, dp(7f).toFloat()) },
                    LinearLayout.LayoutParams(dp(14f), dp(14f)).apply { marginEnd = dp(5f) })
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(t.bg, radius, if (selected) Palette.ACCENT else t.surface, dp(if (selected) 2.5f else 1.5f))
            setPadding(dp(14f), dp(12f), dp(14f), dp(12f))
            isClickable = true
            isFocusable = true
            addView(text(if (selected) "● $title" else title, 14f, t.fg, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            sub?.let {
                addView(text(it, 11f, t.fgDim).apply {
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3f), 0, 0)
                })
            }
            addView(swatches)
            setOnClickListener {
                if (selected) return@setOnClickListener
                settings.themeId = t.id
                // Every screen built so far has the old colours: start this one again.
                recreate()
            }
        }
    }

    private fun renderComputers() {
        val paired = hosts.all()
        val bt = btHosts.all()
        if (paired.isEmpty() && bt.isEmpty()) {
            list.addView(text("No computers yet. Pair one from the main screen.", 14f, Palette.FG_DIM))
            return
        }
        for (h in paired) {
            val via = if (h.btAddress != null) "Omakey · Wi-Fi + Bluetooth" else "Omakey · Wi-Fi"
            list.addView(computerRow(h.name, via, h.addresses.first()) { confirmUnlink(h, hosts, ::render) })
        }
        for (b in bt) {
            list.addView(computerRow(b.name, "Bluetooth keyboard", b.address) { confirmUnlink(b, btHosts, ::render) })
        }
    }

    private fun computerRow(name: String, type: String, address: String, unlink: () -> Unit): View {
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(name, 16f, Palette.FG, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(text(type, 13f, Palette.ACCENT).apply { setPadding(0, dp(4f), 0, 0) })
            addView(text(address, 12f, Palette.FG_DIM).apply { setPadding(0, dp(2f), 0, 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.SURFACE, dp(10f).toFloat())
            setPadding(dp(16f), dp(12f), dp(8f), dp(12f))
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("Unlink", 14f, Palette.ERROR, bold = true).apply {
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                isClickable = true
                setOnClickListener { unlink() }
            })
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8f) }
        }
    }

    private fun hapticsRow() = toggleRow(
        "Haptic feedback", "Touchpad clicks, scrolling and keys feel like a MacBook trackpad.",
        { settings.haptics },
    ) { on ->
        settings.haptics = on
        // Turned on: a click to feel what it's like.
        if (on) Haptics(this).tap()
    }

    private fun typedTextRow() = toggleRow(
        "Show typed text", "What you type runs along above the keyboard. Turn it off for passwords on a shared screen.",
        { settings.typedText },
    ) { settings.typedText = it }

    /** A card with a title, a line about it and an On/Off pill; tapping anywhere flips it. */
    private fun toggleRow(title: String, about: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val toggle = TextView(this)
        fun renderToggle() {
            val on = get()
            toggle.text = if (on) "On" else "Off"
            toggle.textSize = 14f
            toggle.typeface = MONO_BOLD
            toggle.gravity = Gravity.CENTER
            toggle.setTextColor(if (on) Palette.BG else Palette.FG_DIM)
            toggle.background = if (on) rounded(Palette.ACCENT, dp(16f).toFloat())
            else rounded(Palette.KEY_MOD, dp(16f).toFloat(), Palette.FG_DIM, dp(1f))
            toggle.setPadding(dp(18f), dp(8f), dp(18f), dp(8f))
        }
        renderToggle()
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 16f, Palette.FG, bold = true))
            addView(text(about, 13f, Palette.FG_DIM).apply { setPadding(0, dp(4f), dp(12f), 0) })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(Palette.SURFACE, dp(10f).toFloat())
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            isClickable = true
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle)
            setOnClickListener {
                set(!get())
                renderToggle()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(8f) }
        }
    }
}
