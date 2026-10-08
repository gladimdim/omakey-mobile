package com.gladimdim.omakey.ui

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.LinearLayout
import android.widget.TextView
import com.gladimdim.omakey.R

/** Small helpers for the code-built screens. */

/** The phone's name as the user set it, for omakeyd and Bluetooth pairing. */
internal fun Context.phoneName(): String =
    Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: "${Build.MANUFACTURER} ${Build.MODEL}"

/**
 * Every screen is fullscreen: status and navigation bars hidden, back for a
 * moment with a swipe from the edge. Call it again whenever the window gets
 * focus: a dialog or another app brings the bars back.
 */
internal fun Activity.hideSystemBars() {
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

/**
 * Pads a fullscreen screen's root clear of the camera cutout (and of any
 * bar that stays visible). Swiped-in bars float over the content instead.
 */
internal fun View.padForCutout() {
    setOnApplyWindowInsetsListener { v, insets ->
        if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.displayCutout() or WindowInsets.Type.systemBars())
            v.setPadding(i.left, i.top, i.right, i.bottom)
        } else {
            val c = insets.displayCutout
            @Suppress("DEPRECATION")
            v.setPadding(
                maxOf(c?.safeInsetLeft ?: 0, insets.systemWindowInsetLeft), maxOf(c?.safeInsetTop ?: 0, insets.systemWindowInsetTop),
                maxOf(c?.safeInsetRight ?: 0, insets.systemWindowInsetRight), maxOf(c?.safeInsetBottom ?: 0, insets.systemWindowInsetBottom),
            )
        }
        insets
    }
}
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

/** [s] after the Bluetooth sign, which is [color] and [sizePx] tall: marks a Bluetooth connection. */
internal fun Context.bluetooth(s: CharSequence, color: Int, sizePx: Float): CharSequence {
    val icon = getDrawable(R.drawable.ic_bluetooth)!!.mutate().apply {
        val size = (sizePx * 1.15f).toInt()
        setBounds(0, 0, size, size)
        setTint(color)
    }
    return SpannableStringBuilder("\uFFFC ").append(s).apply {
        setSpan(ImageSpan(icon, ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
}

/** Shows [s] after the Bluetooth sign, in the text's own colour and size. */
internal fun TextView.setBluetoothText(s: CharSequence) {
    text = context.bluetooth(s, currentTextColor, textSize)
}

internal fun Context.section(s: String) = text(s, 12f, Palette.FG_DIM, bold = true).apply {
    letterSpacing = 0.12f
    setPadding(0, dp(28f), 0, dp(10f))
}

/**
 * A tappable card with a title and a detail line; [border] outlines it in
 * that colour, and [bluetooth] puts the Bluetooth sign before the detail.
 */
internal fun Context.card(
    title: String, detail: String, detailColor: Int = Palette.FG_DIM, border: Int? = null, bluetooth: Boolean = false,
) =
    LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = if (border != null) rounded(Palette.SURFACE, dp(10f).toFloat(), border, dp(1.5f))
        else rounded(Palette.SURFACE, dp(10f).toFloat())
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        isClickable = true
        isFocusable = true
        addView(text(title, 16f, Palette.FG, bold = true))
        addView(text(detail, 13f, detailColor).apply {
            setPadding(0, dp(4f), 0, 0)
            if (bluetooth) setBluetoothText(detail)
        })
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
