package com.gladimdim.omakey.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Small helpers for the code-built screens. */
internal fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

internal val MONO: Typeface = Typeface.MONOSPACE
internal val MONO_BOLD: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)

internal fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    if (strokeWidth > 0) setStroke(strokeWidth, stroke)
}

internal fun Context.text(s: CharSequence, size: Float = 15f, color: Int = Palette.FG, bold: Boolean = false) =
    TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        typeface = if (bold) MONO_BOLD else MONO
    }

internal fun Context.section(s: String) = text(s, 12f, Palette.FG_DIM, bold = true).apply {
    letterSpacing = 0.12f
    setPadding(0, dp(28f), 0, dp(10f))
}

/** A tappable card with a title and a detail line. */
internal fun Context.card(title: String, detail: String, detailColor: Int = Palette.FG_DIM) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(Palette.SURFACE, dp(10f).toFloat())
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        isClickable = true
        isFocusable = true
        addView(text(title, 16f, Palette.FG, bold = true))
        addView(text(detail, 13f, detailColor).apply { setPadding(0, dp(4f), 0, 0) })
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { bottomMargin = dp(8f) }
    }

internal fun Context.button(label: String, primary: Boolean = false, onClick: (View) -> Unit) =
    TextView(this).apply {
        text = label
        textSize = 15f
        typeface = MONO_BOLD
        gravity = Gravity.CENTER
        setTextColor(if (primary) Palette.BG else Palette.ACCENT)
        background = if (primary) rounded(Palette.ACCENT, dp(10f).toFloat())
        else rounded(Palette.BG, dp(10f).toFloat(), Palette.ACCENT, dp(1.5f))
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
    }
