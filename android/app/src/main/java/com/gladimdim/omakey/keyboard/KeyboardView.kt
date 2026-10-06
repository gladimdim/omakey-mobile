package com.gladimdim.omakey.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.gladimdim.omakey.layout.KeyStyle
import com.gladimdim.omakey.layout.Layout
import com.gladimdim.omakey.ui.Palette

/**
 * Draws a layout and turns raw multi-touch into key presses. Keys fire on
 * touch-down (ACTION_DOWN / ACTION_POINTER_DOWN), not on lift, and every
 * finger is its own key, so chords like SUPER + SHIFT + 4 work.
 */
class KeyboardView(context: Context) : View(context) {
    private var model: KeyboardModel? = null
    private var rects: Array<RectF> = emptyArray()
    private var unit = 1f
    private var originX = 0f
    private var originY = 0f

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.LEFT
    }
    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textAlign = Paint.Align.RIGHT
        color = Palette.LAYER
    }

    init {
        setBackgroundColor(Palette.BG)
        isHapticFeedbackEnabled = true
    }

    fun setLayout(layout: Layout, sink: KeyboardModel.Sink) {
        model?.cancelAll()
        model = KeyboardModel(layout, sink)
        rects = Array(layout.keys.size) { RectF() }
        computeGeometry()
        invalidate()
    }

    /** Lift every finger, e.g. when the activity pauses. */
    fun releaseAll() {
        if (model?.cancelAll() == true) invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        computeGeometry()
    }

    private fun computeGeometry() {
        val m = model ?: return
        val l = m.layout
        val pad = resources.displayMetrics.density * 4
        val availW = width - pad * 2
        val availH = height - pad * 2
        if (availW <= 0 || availH <= 0) return
        unit = minOf(availW / l.width, availH / l.height)
        originX = (width - unit * l.width) / 2
        originY = (height - unit * l.height) / 2
        val gap = unit * 0.05f
        l.keys.forEachIndexed { i, k ->
            rects[i].set(
                originX + k.x * unit + gap, originY + k.y * unit + gap,
                originX + (k.x + k.w) * unit - gap, originY + (k.y + k.h) * unit - gap,
            )
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val m = model ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val key = m.hitTest((ev.getX(i) - originX) / unit, (ev.getY(i) - originY) / unit)
                if (m.down(ev.getPointerId(i), key)) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                if (m.up(ev.getPointerId(ev.actionIndex))) invalidate()
            MotionEvent.ACTION_CANCEL -> if (m.cancelAll()) invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val m = model ?: return
        val keys = m.layout.keys
        val radius = unit * 0.12f
        val layer = m.activeLayer
        for (i in keys.indices) {
            val k = keys[i]
            val r = rects[i]
            val pressed = m.pressCount[i] > 0
            keyPaint.color = when {
                pressed -> Palette.ACCENT
                k.layer != null && k.layer == layer -> Palette.ACCENT
                k.style == KeyStyle.ACCENT -> Palette.KEY_ACCENT
                k.style == KeyStyle.MOD -> Palette.KEY_MOD
                k.style == KeyStyle.FKEY -> Palette.KEY_FKEY
                else -> Palette.KEY
            }
            canvas.drawRoundRect(r, radius, radius, keyPaint)

            val override = if (layer != null) k.layers[layer] else null
            val label = m.labelFor(i)
            labelPaint.color = when {
                pressed -> Palette.BG
                override != null -> Palette.LAYER
                override == null && layer != null && k.layer == null -> Palette.FG_DIM
                k.style == KeyStyle.ACCENT -> Palette.FG_ON_ACCENT
                else -> Palette.FG
            }
            drawFitted(canvas, label, r, if (label.length <= 2) 0.4f else 0.24f)

            if (layer == null) {
                k.sub?.let {
                    subPaint.textSize = unit * 0.2f
                    subPaint.color = if (pressed) Palette.BG else Palette.FG_DIM
                    canvas.drawText(it, r.left + unit * 0.1f, r.top + unit * 0.26f, subPaint)
                }
                // Laptop-style printed Fn legend in the corner.
                val fn = k.layers["fn"]?.label
                if (fn != null && r.width() > unit * 0.6f) {
                    layerPaint.textSize = unit * 0.16f
                    layerPaint.color = if (pressed) Palette.BG else Palette.LAYER
                    canvas.drawText(fn, r.right - unit * 0.08f, r.bottom - unit * 0.1f, layerPaint)
                }
            }
        }
    }

    private fun drawFitted(canvas: Canvas, text: String, r: RectF, sizeUnits: Float) {
        if (text.isEmpty()) return
        labelPaint.textSize = unit * sizeUnits
        val maxW = r.width() - unit * 0.12f
        val w = labelPaint.measureText(text)
        if (w > maxW) labelPaint.textSize *= maxW / w
        val y = r.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
        canvas.drawText(text, r.centerX(), y, labelPaint)
    }
}
