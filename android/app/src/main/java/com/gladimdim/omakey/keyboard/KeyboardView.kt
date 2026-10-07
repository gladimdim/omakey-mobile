package com.gladimdim.omakey.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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
    /** Outline of each shaped key (one with extra parts), or null for plain rectangles. */
    private var shapes: Array<Path?> = emptyArray()
    private var unit = 1f
    private var originX = 0f
    /** Units the right side of a split layout is moved right by. */
    private var stretch = 0f
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

    /** Caps Lock as sent from here or reported by the computer; kept when the layout changes. */
    private val locks = KeyboardModel.Locks()

    /** False for a preview: touches do nothing. */
    var interactive = true

    /** Tap modifiers and Fn instead of holding them (see [KeyboardModel.sticky]). */
    var sticky = false
        set(value) {
            field = value
            model?.sticky = value
            invalidate()
        }

    // Fitted label sizes, measured again only when a key's label changes.
    private var fittedLabel: Array<String?> = emptyArray()
    private var fittedSize = FloatArray(0)

    fun setLayout(layout: Layout, sink: KeyboardModel.Sink) {
        model?.cancelAll()
        model = KeyboardModel(layout, sink, locks).also { it.sticky = sticky }
        rects = Array(layout.keys.size) { RectF() }
        shapes = arrayOfNulls(layout.keys.size)
        fittedLabel = arrayOfNulls(layout.keys.size)
        fittedSize = FloatArray(layout.keys.size)
        computeGeometry()
        invalidate()
    }

    /** The computer's Caps Lock light, when it reports it. */
    fun setCapsLock(on: Boolean) {
        if (locks.capsLock == on) return
        locks.capsLock = on
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
        // Edge to edge: the keys' own gaps are the only margin.
        val pad = 0f
        val availW = width - pad * 2
        val availH = height - pad * 2
        if (availW <= 0 || availH <= 0) return
        unit = minOf(availW / l.width, availH / l.height)
        val slack = availW - unit * l.width
        val split = l.splitAt
        // A split layout keeps each side against its screen edge: the spare
        // width goes into the split instead of the margins.
        stretch = if (split != null && slack > 0f) slack / unit else 0f
        originX = if (stretch > 0f) pad else (width - unit * l.width) / 2
        originY = (height - unit * l.height) / 2
        val gap = unit * 0.05f
        val radius = unit * 0.12f
        // Sizes changed: fit every label again; prime the hit-test rectangles.
        fittedLabel.fill(null)
        m.hitTestStretched(0f, 0f, stretch)
        l.keys.forEachIndexed { i, k ->
            val drawn = k.rects.map { it.stretched(split, stretch) }
            val main = drawn[0]
            rects[i].set(
                originX + main.x * unit + gap, originY + main.y * unit + gap,
                originX + (main.x + main.w) * unit - gap, originY + (main.y + main.h) * unit - gap,
            )
            shapes[i] = if (drawn.size > 1) outline(drawn, gap, radius) else null
        }
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!interactive) return false
        val m = model ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                val key = m.hitTestStretched((ev.getX(i) - originX) / unit, (ev.getY(i) - originY) / unit, stretch)
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
            val pressed = m.pressCount[i] > 0 || m.isLatched(i)
            keyPaint.color = when {
                pressed -> Palette.ACCENT
                k.layer != null && k.layer == layer -> Palette.ACCENT
                k.code == KEY_CAPSLOCK && m.capsLock -> Palette.KEY_ACCENT
                k.style == KeyStyle.ACCENT -> Palette.KEY_ACCENT
                k.style == KeyStyle.MOD -> Palette.KEY_MOD
                k.style == KeyStyle.FKEY -> Palette.KEY_FKEY
                else -> Palette.KEY
            }
            val shape = shapes[i]
            if (shape != null) canvas.drawPath(shape, keyPaint) else canvas.drawRoundRect(r, radius, radius, keyPaint)

            val override = if (layer != null) k.layers[layer] else null
            val label = m.labelFor(i)
            labelPaint.color = when {
                pressed -> Palette.BG
                override != null -> Palette.LAYER
                override == null && layer != null && k.layer == null -> Palette.FG_DIM
                k.style == KeyStyle.ACCENT -> Palette.FG_ON_ACCENT
                else -> Palette.FG
            }
            drawFitted(canvas, i, label, r, if (label.length <= 2) 0.4f else 0.24f)
            if (m.isLocked(i)) {
                // A locked modifier: a bar under its label.
                keyPaint.color = Palette.BG
                canvas.drawRect(r.centerX() - unit * 0.15f, r.bottom - unit * 0.12f, r.centerX() + unit * 0.15f, r.bottom - unit * 0.08f, keyPaint)
            }

            if (layer == null) {
                m.subFor(i)?.let {
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

    /**
     * One outline for a key made of several rectangles: each is inset by the
     * key gap except on sides where it meets another part, which it overlaps
     * instead, so the parts read as a single key.
     */
    private fun outline(parts: List<com.gladimdim.omakey.layout.KeyRect>, gap: Float, radius: Float): Path {
        val e = 1e-4f
        val path = Path()
        for (p in parts) {
            fun meets(test: (com.gladimdim.omakey.layout.KeyRect) -> Boolean) = parts.any { it !== p && test(it) }
            val overlapY = { o: com.gladimdim.omakey.layout.KeyRect -> o.y < p.y + p.h - e && p.y < o.y + o.h - e }
            val overlapX = { o: com.gladimdim.omakey.layout.KeyRect -> o.x < p.x + p.w - e && p.x < o.x + o.w - e }
            val l = if (meets { overlapY(it) && kotlin.math.abs(it.x + it.w - p.x) < e }) -gap else gap
            val r = if (meets { overlapY(it) && kotlin.math.abs(p.x + p.w - it.x) < e }) -gap else gap
            val t = if (meets { overlapX(it) && kotlin.math.abs(it.y + it.h - p.y) < e }) -gap else gap
            val b = if (meets { overlapX(it) && kotlin.math.abs(p.y + p.h - it.y) < e }) -gap else gap
            val one = Path()
            one.addRoundRect(
                originX + p.x * unit + l, originY + p.y * unit + t,
                originX + (p.x + p.w) * unit - r, originY + (p.y + p.h) * unit - b,
                radius, radius, Path.Direction.CW,
            )
            path.op(one, Path.Op.UNION)
        }
        return path
    }

    private companion object {
        const val KEY_CAPSLOCK = 58
    }

    private fun drawFitted(canvas: Canvas, index: Int, text: String, r: RectF, sizeUnits: Float) {
        if (text.isEmpty()) return
        if (fittedLabel[index] != text) {
            labelPaint.textSize = unit * sizeUnits
            val maxW = r.width() - unit * 0.12f
            val w = labelPaint.measureText(text)
            fittedSize[index] = if (w > maxW) labelPaint.textSize * maxW / w else labelPaint.textSize
            fittedLabel[index] = text
        }
        labelPaint.textSize = fittedSize[index]
        val y = r.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
        canvas.drawText(text, r.centerX(), y, labelPaint)
    }
}
