package com.gladimdim.omakey.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.keyboard.KeyboardView
import com.gladimdim.omakey.layout.Layout
import com.gladimdim.omakey.layout.LayoutLink
import com.gladimdim.omakey.store.LayoutStore

/**
 * Every layout with a live preview of its keys. Tapping one makes it the
 * active layout and closes the page; each card can also be shared, and an
 * imported one removed. Callers start it for a result: [RESULT_OK] when the
 * selection may have changed, [RESULT_IMPORT] when an import was asked for.
 */
open class LayoutsActivity : Activity() {
    private lateinit var layouts: LayoutStore
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Palette.load(this)
        layouts = LayoutStore(this)
        buildUi()
        render(scrollToSelected = savedInstanceState == null)
    }

    private fun buildUi() {
        val back = text("←", 22f, Palette.ACCENT, bold = true).apply {
            setPadding(dp(16f), dp(8f), dp(16f), dp(8f))
            isClickable = true
            contentDescription = "Back"
            setOnClickListener { finish() }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(12f), dp(24f), dp(12f))
            addView(back)
            addView(text("Layouts", 24f, Palette.FG, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (intent.getBooleanExtra(EXTRA_CAN_IMPORT, false)) {
                addView(button("Import file…") {
                    setResult(RESULT_IMPORT)
                    finish()
                }.apply { setPadding(dp(14f), dp(8f), dp(14f), dp(8f)) })
            }
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), 0, dp(24f), dp(32f))
        }
        scroll = ScrollView(this).apply { addView(list) }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            padForCutout()
            addView(header)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun render(scrollToSelected: Boolean = false) {
        list.removeAllViews()
        val entries = layouts.all()
        val selected = if (layouts.portrait) LayoutStore.PORTRAIT_ID else layouts.selected().id
        list.addView(text("Tap a layout to type on it.", 13f, Palette.FG_DIM).apply { setPadding(0, 0, 0, dp(16f)) })

        // Side by side on a wide screen (landscape, tablets): keyboards are wide.
        val columns = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        var selectedCard: View? = null
        // Portrait mode second, after the recommended layout; null stands for it.
        val items: List<LayoutStore.Entry?> = entries.take(1) + listOf(null) + entries.drop(1)
        for (rowEntries in items.chunked(columns)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowEntries.forEachIndexed { i, e ->
                val id = e?.layout?.id ?: LayoutStore.PORTRAIT_ID
                val c = if (e == null) portraitCard(id == selected) else card(e, id == selected)
                if (id == selected) selectedCard = row
                row.addView(c, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = dp(12f)
                    bottomMargin = dp(12f)
                })
            }
            // A short last row keeps its card the width of the others.
            repeat(columns - rowEntries.size) {
                row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(12f) })
            }
            list.addView(row)
        }
        if (scrollToSelected) selectedCard?.let { c -> scroll.post { scroll.scrollTo(0, (c.top - dp(16f)).coerceAtLeast(0)) } }
    }

    private fun card(entry: LayoutStore.Entry, isSelected: Boolean): View {
        val layout = entry.layout
        val radius = dp(12f).toFloat()
        val title = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(layout.name, 16f, Palette.FG, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (!entry.builtIn) addView(badge("IMPORTED", Palette.FG_DIM))
            if (isSelected) addView(badge("● IN USE", Palette.OK))
        }

        val details = buildString {
            append("${layout.keys.size} keys")
            layout.author?.let { append(" · by $it") }
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(text(details, 12f, Palette.FG_DIM).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(action("Share") { share(this@LayoutsActivity, layout) })
            if (!entry.builtIn) addView(action("Remove", Palette.ERROR) { confirmRemove(layout) })
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (isSelected) rounded(Palette.SURFACE, radius, Palette.ACCENT, dp(2f))
            else rounded(Palette.SURFACE, radius)
            foreground = RippleDrawable(ColorStateList.valueOf(Palette.FG and 0x00FFFFFF or 0x22000000), null, rounded(Palette.FG, radius))
            setPadding(dp(14f), dp(12f), dp(14f), dp(6f))
            isClickable = true
            isFocusable = true
            setOnClickListener { pick(layout) }
            if (entry.builtIn && layout.id == LayoutStore.DEFAULT_ID) {
                addView(text("★ Recommended by gladimdim", 12f, Palette.WARN, bold = true).apply {
                    setPadding(0, 0, 0, dp(4f))
                })
            }
            addView(title)
            addView(preview(layout), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(10f) })
            layout.description?.let {
                // In full: it says how the layout works (layers, thumb keys).
                addView(text(it, 13f, Palette.FG).apply {
                    setLineSpacing(dp(2f).toFloat(), 1f)
                    setPadding(0, dp(12f), 0, dp(4f))
                })
            }
            addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(2f) })
        }
    }

    /** Portrait mode, which isn't a layout: the phone's keyboard under the touchpad. */
    private fun portraitCard(isSelected: Boolean): View {
        val radius = dp(12f).toFloat()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = if (isSelected) rounded(Palette.SURFACE, radius, Palette.ACCENT, dp(2f))
            else rounded(Palette.SURFACE, radius)
            foreground = RippleDrawable(ColorStateList.valueOf(Palette.FG and 0x00FFFFFF or 0x22000000), null, rounded(Palette.FG, radius))
            setPadding(dp(14f), dp(12f), dp(14f), dp(14f))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                layouts.selectedId = LayoutStore.PORTRAIT_ID
                setResult(RESULT_OK)
                finish()
            }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(PORTRAIT_NAME, 16f, Palette.FG, bold = true).apply {
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(badge("MODE", Palette.LAYER))
                if (isSelected) addView(badge("● IN USE", Palette.OK))
            })
            addView(PortraitPreview(context), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(190f))
                .apply { topMargin = dp(10f) })
            addView(text(
                "Hold the phone upright. Your own Android keyboard (Gboard, SwiftKey, any) types straight into the " +
                    "computer, autocorrect and all, with the touchpad and its buttons above it. Types what a US layout can.",
                13f, Palette.FG,
            ).apply {
                setLineSpacing(dp(2f).toFloat(), 1f)
                setPadding(0, dp(12f), 0, 0)
            })
        }
    }

    /** The layout drawn at the card's width, as tall as its own proportions ask. */
    private fun preview(layout: Layout): View {
        val keyboard = KeyboardView(this).apply {
            interactive = false
            setLayout(layout, object : KeyboardModel.Sink {
                override fun keyDown(code: Int) {}
                override fun keyUp(code: Int) {}
            })
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val ratio = layout.height / layout.width
        val pad = dp(6f)
        return object : FrameLayout(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val w = MeasureSpec.getSize(widthMeasureSpec)
                val h = ((w - pad * 2) * ratio).toInt() + pad * 2
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            }
        }.apply {
            background = rounded(Palette.BG, dp(8f).toFloat())
            setPadding(pad, pad, pad, pad)
            addView(keyboard, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
    }

    private fun badge(label: String, color: Int) = text(label, 10f, color, bold = true).apply {
        letterSpacing = 0.08f
        background = rounded(Palette.BG, dp(8f).toFloat())
        setPadding(dp(8f), dp(3f), dp(8f), dp(3f))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(6f) }
    }

    private fun action(label: String, color: Int = Palette.ACCENT, onClick: (View) -> Unit): TextView =
        text(label, 13f, color, bold = true).apply {
            setPadding(dp(12f), dp(10f), dp(4f), dp(10f))
            isClickable = true
            setOnClickListener(onClick)
        }

    private fun pick(layout: Layout) {
        layouts.selectedId = layout.id
        setResult(RESULT_OK)
        finish()
    }

    private fun confirmRemove(layout: Layout) {
        AlertDialog.Builder(this, Palette.dialogTheme)
            .setTitle("Remove ${layout.name}?")
            .setMessage("It's deleted from this phone. You can import it again from its file or link.")
            .setPositiveButton("Remove") { _, _ ->
                layouts.delete(layout.id)
                // The active layout may have fallen back to the default.
                setResult(RESULT_OK)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    companion object {
        /** Show the Import file… button; the caller handles [RESULT_IMPORT]. */
        const val EXTRA_CAN_IMPORT = "canImport"
        const val RESULT_IMPORT = RESULT_FIRST_USER

        const val PORTRAIT_NAME = "Portrait: your keyboard + touchpad"

        /** What the keyboard opens with, for the connect screen and Settings. */
        fun currentName(layouts: LayoutStore) = if (layouts.portrait) PORTRAIT_NAME else layouts.selected().name

        /**
         * Out through the share sheet: an `omakey://layout` link when it is
         * short enough to paste anywhere, otherwise the JSON itself. Either
         * imports on another phone or in the studio.
         */
        fun share(activity: Activity, layout: Layout) {
            val link = LayoutLink.encode(layout.source)
            val text = if (link.length <= MAX_LINK) link else layout.source
            val send = Intent(Intent.ACTION_SEND)
                .setType(if (text === link) "text/plain" else "application/json")
                .putExtra(Intent.EXTRA_SUBJECT, "${layout.name} — Omakey layout")
                .putExtra(Intent.EXTRA_TEXT, text)
            activity.startActivity(Intent.createChooser(send, "Share ${layout.name}"))
        }

        private const val MAX_LINK = 8000
    }
}

/**
 * The layouts page opened from the keyboard. Landscape is declared in the
 * manifest, so it opens that way instead of starting in portrait and turning.
 */
class LandscapeLayoutsActivity : LayoutsActivity()
