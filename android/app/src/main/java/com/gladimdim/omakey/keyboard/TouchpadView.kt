package com.gladimdim.omakey.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.gladimdim.omakey.protocol.Wire
import com.gladimdim.omakey.ui.Palette
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A laptop touchpad: a surface that moves the desktop's pointer, framed by
 * buttons.
 *
 * - One finger moves the pointer. A quick tap clicks; tap and hold
 *   right-clicks (a context menu).
 * - Two fingers scroll, both ways, content following the fingers. A
 *   two-finger tap right-clicks; a three-finger tap middle-clicks.
 * - Down each side, mirrored: Left click, Right click, Ctrl + Left and
 *   Shift + Left. They are held while touched, so dragging, Ctrl-click
 *   multi-select and Shift-click range select work with the other thumb
 *   moving the pointer.
 * - A strip along the bottom holds the pointer speed: a preset chip and a
 *   slider. Swiping up from the rest of the strip puts the touchpad away.
 */
class TouchpadView(context: Context) : View(context) {
    interface Sink {
        fun button(code: Int, down: Boolean)
        /** Pointer motion in mouse counts. */
        fun motion(dx: Float, dy: Float)
        /** Scroll in 1/120 of a notch: positive [v] scrolls up, positive [h] right. */
        fun scroll(v: Float, h: Float)
    }

    /** Swiping up from the bottom strip drags the touchpad away. */
    interface PanelDrag {
        /** The finger is [dy] px from where it started (negative: up). */
        fun drag(dy: Float)
        fun release(dy: Float, flungUp: Boolean)
    }

    var sink: Sink? = null
    var panelDrag: PanelDrag? = null

    /** False while the computer's omakeyd is too old for the touchpad. */
    var supported = true
        set(value) {
            field = value
            invalidate()
        }

    /** Pointer speed multiplier, set by the slider or a preset. */
    var sensitivity = 1f
        set(value) {
            field = value.coerceIn(MIN_SENS, MAX_SENS)
            invalidate()
        }

    /** The preset the sensitivity came from, or "Custom". Shown in the chip. */
    var presetName = ""
        set(value) {
            field = value
            invalidate()
        }

    /** The slider moved; the activity saves it and marks the preset Custom. */
    var onSensitivityChanged: ((Float) -> Unit)? = null

    /** The preset chip was tapped. */
    var onPresetsRequested: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val slop = 6 * density
    private val swipeSlop = 14 * density
    private val motionScale = 1.1f
    private val scrollScale = 2.4f // 1/120-notch units per pixel: a notch every 50 px

    private enum class Icon { LEFT, RIGHT }

    /** A button: the codes it holds (pressed in order, released in reverse). */
    private class Button(
        val codes: IntArray,
        val label: String,
        val icon: Icon,
        val modifier: String?,
    ) {
        val rect = RectF()
        var held = 0
    }

    private fun sideColumn() = listOf(
        Button(intArrayOf(Wire.BTN_LEFT), "Left", Icon.LEFT, null),
        Button(intArrayOf(Wire.BTN_RIGHT), "Right", Icon.RIGHT, null),
        Button(intArrayOf(KEY_LEFTCTRL, Wire.BTN_LEFT), "Ctrl + Left", Icon.LEFT, "Ctrl"),
        Button(intArrayOf(KEY_LEFTSHIFT, Wire.BTN_LEFT), "Shift + Left", Icon.LEFT, "⇧"),
    )

    // The same four down both sides, mirrored.
    private val leftColumn = sideColumn()
    private val rightColumn = sideColumn()
    private val buttons = leftColumn + rightColumn

    // Geometry.
    private val pad = RectF()
    private val strip = RectF()
    private val slider = RectF()
    private val sliderTouch = RectF()
    private val chip = RectF()

    // Pointers: what each finger is doing, and where it was last.
    private val role = IntArray(MAX_POINTERS) { NONE }
    private val lastX = FloatArray(MAX_POINTERS)
    private val lastY = FloatArray(MAX_POINTERS)
    private var padFingers = 0

    // Strip fingers that may become a swipe up.
    private val swipeStartY = FloatArray(MAX_POINTERS)
    private val swipeDownAt = LongArray(MAX_POINTERS)

    // The current pad gesture.
    private var gestureStart = 0L
    private var startId = -1
    private var startX = 0f
    private var startY = 0f
    private var maxFingers = 0
    private var travelled = 0f // finger travel, for telling taps from moves
    private var moving = false
    private var longPressed = false

    private val longPress = Runnable {
        if (padFingers == 1 && maxFingers == 1 && !moving) {
            longPressed = true
            click(Wire.BTN_RIGHT)
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.SURFACE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.KEY }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.MONOSPACE
    }
    private val iconBody = RectF()
    private val iconHalf = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 6 * density
        val stripH = 46 * density
        val columnW = maxOf(80 * density, w * 0.095f)

        // Side columns, full height, mirrored.
        for ((column, left) in listOf(leftColumn to gap, rightColumn to w - gap - columnW)) {
            val cellH = (h - gap * 2 - gap * (column.size - 1)) / column.size
            column.forEachIndexed { i, b ->
                val top = gap + i * (cellH + gap)
                b.rect.set(left, top, left + columnW, top + cellH)
            }
        }
        pad.set(gap * 2 + columnW, gap, w - gap * 2 - columnW, h - gap * 2 - stripH)
        strip.set(pad.left, pad.bottom + gap, pad.right, h - gap)

        // Speed in the strip: the preset chip, then the slider, centred together.
        val chipW = minOf(strip.width() * 0.32f, 230 * density)
        val sliderW = minOf(strip.width() * 0.42f, 320 * density)
        val labels = 44 * density // room for "slow" and "fast"
        val total = chipW + 16 * density + labels + sliderW + labels
        var x = strip.centerX() - total / 2
        val chipH = 28 * density
        chip.set(x, strip.centerY() - chipH / 2, x + chipW, strip.centerY() + chipH / 2)
        x += chipW + 16 * density + labels
        val sliderH = 22 * density
        slider.set(x, strip.centerY() - sliderH / 2, x + sliderW, strip.centerY() + sliderH / 2)
        sliderTouch.set(slider.left - 8 * density, strip.top, slider.right + 8 * density, strip.bottom)
    }

    /** Let go of every button, e.g. when the touchpad is put away. */
    fun releaseAll() {
        removeCallbacks(longPress)
        for (b in buttons) {
            if (b.held > 0) {
                b.held = 1
                release(b)
            }
        }
        role.fill(NONE)
        padFingers = 0
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> fingerDown(ev, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> fingersMoved(ev)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> fingerUp(ev, ev.actionIndex)
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun fingerDown(ev: MotionEvent, index: Int) {
        val id = ev.getPointerId(index)
        if (id !in 0 until MAX_POINTERS) return
        val x = ev.getX(index)
        val y = ev.getY(index)
        lastX[id] = x
        lastY[id] = y
        if (sliderTouch.contains(x, y)) {
            role[id] = SLIDER
            slide(x)
            return
        }
        if (chip.contains(x, y)) {
            role[id] = CHIP
            return
        }
        val b = buttons.indexOfFirst { it.rect.contains(x, y) }
        if (b >= 0) {
            role[id] = BUTTON + b
            press(buttons[b])
            return
        }
        if (strip.contains(x, y)) {
            role[id] = STRIP
            swipeStartY[id] = y
            swipeDownAt[id] = ev.eventTime
            return
        }
        if (!pad.contains(x, y)) return
        role[id] = PAD
        padFingers++
        if (padFingers == 1) {
            gestureStart = ev.eventTime
            startId = id
            startX = x
            startY = y
            maxFingers = 1
            travelled = 0f
            moving = false
            longPressed = false
            postDelayed(longPress, LONG_PRESS_MS)
        } else {
            removeCallbacks(longPress)
            maxFingers = maxOf(maxFingers, padFingers)
        }
    }

    private fun fingersMoved(ev: MotionEvent) {
        var sumDx = 0f
        var sumDy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id !in 0 until MAX_POINTERS) continue
            when {
                role[id] == SLIDER -> slide(ev.getX(i))
                // Swiping up from the strip puts the touchpad away.
                role[id] == STRIP && ev.getY(i) - swipeStartY[id] < -swipeSlop -> role[id] = SWIPE
                role[id] == SWIPE -> panelDrag?.drag(ev.getY(i) - swipeStartY[id])
                role[id] == PAD -> {
                    val x = ev.getX(i)
                    val y = ev.getY(i)
                    sumDx += x - lastX[id]
                    sumDy += y - lastY[id]
                    lastX[id] = x
                    lastY[id] = y
                    n++
                }
            }
        }
        if (n == 0) return
        val dx = sumDx / n
        val dy = sumDy / n
        travelled += abs(dx) + abs(dy)
        val s = sink ?: return
        if (maxFingers == 1) {
            val i = ev.findPointerIndex(startId)
            if (!moving && i >= 0 && hypot(ev.getX(i) - startX, ev.getY(i) - startY) > slop) {
                moving = true
                removeCallbacks(longPress)
            }
            if (moving) s.motion(dx * motionScale * sensitivity, dy * motionScale * sensitivity)
        } else if (padFingers >= 2) {
            // Content follows the fingers: fingers up scrolls down.
            s.scroll(dy * scrollScale, -dx * scrollScale)
        }
    }

    private fun fingerUp(ev: MotionEvent, index: Int) {
        val id = ev.getPointerId(index)
        if (id !in 0 until MAX_POINTERS) return
        val r = role[id]
        role[id] = NONE
        when {
            r == SLIDER -> return
            r == CHIP -> {
                if (chip.contains(ev.getX(index), ev.getY(index))) onPresetsRequested?.invoke()
                return
            }
            r == SWIPE -> {
                val dy = ev.getY(index) - swipeStartY[id]
                val speed = dy / maxOf(1L, ev.eventTime - swipeDownAt[id])
                panelDrag?.release(dy, speed < -FLING_PX_PER_MS * density)
                return
            }
            r >= BUTTON -> {
                release(buttons[r - BUTTON])
                return
            }
            r != PAD -> return
        }
        padFingers--
        if (padFingers > 0) return
        removeCallbacks(longPress)
        val duration = ev.eventTime - gestureStart
        val still = travelled < slop * maxFingers
        when {
            maxFingers == 1 && !moving && !longPressed && duration < TAP_MS -> click(Wire.BTN_LEFT)
            maxFingers == 2 && still && duration < MULTI_TAP_MS -> click(Wire.BTN_RIGHT)
            maxFingers == 3 && still && duration < MULTI_TAP_MS -> click(Wire.BTN_MIDDLE)
        }
    }

    private fun press(b: Button) {
        if (b.held++ == 0) {
            // Modifier first, then the mouse button: Ctrl is down before the click lands.
            for (c in b.codes) sink?.button(c, true)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
    }

    private fun release(b: Button) {
        if (b.held > 0 && --b.held == 0) {
            for (i in b.codes.indices.reversed()) sink?.button(b.codes[i], false)
        }
        invalidate()
    }

    private fun click(code: Int) {
        val s = sink ?: return
        s.button(code, true)
        s.button(code, false)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Slider position (0 left .. 1 right) to sensitivity, on a log scale. */
    private fun sensAt(t: Float) = (MIN_SENS * Math.pow((MAX_SENS / MIN_SENS).toDouble(), t.toDouble())).toFloat()

    private fun posOf(sens: Float) =
        (Math.log((sens / MIN_SENS).toDouble()) / Math.log((MAX_SENS / MIN_SENS).toDouble())).toFloat()

    private fun slide(x: Float) {
        val t = ((x - slider.left) / slider.width()).coerceIn(0f, 1f)
        // Snap to 0.05, which is plenty and keeps the label steady.
        val v = (Math.round(sensAt(t) * 20) / 20f).coerceIn(MIN_SENS, MAX_SENS)
        if (v != sensitivity) {
            sensitivity = v
            onSensitivityChanged?.invoke(v)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = 14 * density
        canvas.drawRoundRect(pad, radius, radius, surfacePaint)
        // A faint dot grid, so the surface reads as a touchpad.
        val step = 22 * density
        val dot = 1.4f * density
        var y = pad.top + step
        while (y < pad.bottom - step / 2) {
            var x = pad.left + step
            while (x < pad.right - step / 2) {
                canvas.drawCircle(x, y, dot, dotPaint)
                x += step
            }
            y += step
        }

        textPaint.textSize = 13 * density
        val hintY = pad.top + pad.height() * 0.36f
        if (supported) {
            textPaint.color = Palette.FG_DIM
            canvas.drawText("move · tap to click · hold for menu · two fingers scroll", pad.centerX(), hintY, textPaint)
            textPaint.textSize = 11 * density
            canvas.drawText("swipe up from the bottom strip to hide", pad.centerX(), hintY + 20 * density, textPaint)
        } else {
            textPaint.color = Palette.WARN
            canvas.drawText("Update Omakey on your computer to use the touchpad:", pad.centerX(), hintY, textPaint)
            canvas.drawText("bar icon → Update the service", pad.centerX(), hintY + 22 * density, textPaint)
        }

        drawSpeed(canvas)
        for (b in buttons) drawButton(canvas, b, radius)
    }

    private fun drawSpeed(canvas: Canvas) {
        fillPaint.color = Palette.SURFACE
        canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, fillPaint)
        // A grabber line, so the strip reads as something to swipe.
        fillPaint.color = Palette.KEY
        val gw = 36 * density
        canvas.drawRoundRect(strip.centerX() - gw / 2, strip.top + 4 * density, strip.centerX() + gw / 2,
            strip.top + 7 * density, 2 * density, 2 * density, fillPaint)
        val r = slider
        val h = r.height()
        val t = posOf(sensitivity)
        fillPaint.color = Palette.KEY_MOD
        canvas.drawRoundRect(r, h / 2, h / 2, fillPaint)
        val knobX = r.left + t * r.width()
        fillPaint.color = Palette.KEY_ACCENT
        canvas.drawRoundRect(r.left, r.top, knobX.coerceAtLeast(r.left + h), r.bottom, h / 2, h / 2, fillPaint)
        fillPaint.color = Palette.ACCENT
        canvas.drawCircle(knobX.coerceIn(r.left + h / 2, r.right - h / 2), r.centerY(), h * 0.42f, fillPaint)
        textPaint.color = Palette.FG_DIM
        textPaint.textSize = 10 * density
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("slow", r.left - 8 * density, r.centerY() + 4 * density, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("fast", r.right + 8 * density, r.centerY() + 4 * density, textPaint)
        textPaint.textAlign = Paint.Align.CENTER

        fillPaint.color = Palette.KEY_MOD
        canvas.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, fillPaint)
        textPaint.color = Palette.ACCENT
        textPaint.textSize = 12 * density
        val value = "%.2f".format(sensitivity).trimEnd('0').trimEnd('.')
        val label = "${presetName.ifEmpty { "Pointer speed" }} · $value× ▾"
        canvas.drawText(label, chip.centerX(), chip.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    private fun drawButton(canvas: Canvas, b: Button, radius: Float) {
        val r = b.rect
        val held = b.held > 0
        val mouse = b.codes.last() in Wire.BTN_LEFT..Wire.BTN_MIDDLE
        fillPaint.color = when {
            held -> Palette.ACCENT
            mouse -> Palette.KEY
            else -> Palette.KEY_MOD
        }
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        val fg = if (held) Palette.BG else if (mouse) Palette.FG else Palette.FG_DIM
        // Mouse icon with the button it presses filled in, a modifier badge,
        // and the name underneath.
        val size = minOf(r.height() * 0.42f, r.width() * 0.5f)
        val cy = r.centerY() - r.height() * 0.1f
        drawMouse(canvas, r.centerX(), cy, size, b.icon, fg, if (held) Palette.BG else Palette.ACCENT)
        b.modifier?.let {
            textPaint.textSize = 11 * density
            val w = textPaint.measureText(it) + 10 * density
            val bx = r.centerX() + size * 0.55f
            val by = cy - size * 0.55f
            fillPaint.color = if (held) Palette.BG else Palette.SURFACE
            canvas.drawRoundRect(bx - w / 2, by - 9 * density, bx + w / 2, by + 9 * density, 9 * density, 9 * density, fillPaint)
            textPaint.color = if (held) Palette.ACCENT else Palette.LAYER
            canvas.drawText(it, bx, by - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
        textPaint.color = fg
        textPaint.textSize = 11 * density
        canvas.drawText(b.label, r.centerX(), r.bottom - 8 * density, textPaint)
    }

    /** A mouse outline, [size] tall, with the [which] button filled. */
    private fun drawMouse(canvas: Canvas, cx: Float, cy: Float, size: Float, which: Icon, outline: Int, fill: Int) {
        val w = size * 0.66f
        iconBody.set(cx - w / 2, cy - size / 2, cx + w / 2, cy + size / 2)
        val corner = w / 2
        val split = iconBody.top + size * 0.42f
        if (which == Icon.LEFT) iconHalf.set(iconBody.left, iconBody.top, cx, split)
        else iconHalf.set(cx, iconBody.top, iconBody.right, split)
        canvas.save()
        canvas.clipRect(iconHalf)
        fillPaint.color = fill
        canvas.drawRoundRect(iconBody, corner, corner, fillPaint)
        canvas.restore()
        strokePaint.color = outline
        canvas.drawRoundRect(iconBody, corner, corner, strokePaint)
        canvas.drawLine(iconBody.left, split, iconBody.right, split, strokePaint)
        canvas.drawLine(cx, iconBody.top, cx, split, strokePaint)
    }

    private companion object {
        const val MAX_POINTERS = 32
        const val NONE = 0
        const val PAD = 1
        const val SWIPE = 2 // a strip finger swiping the touchpad away
        const val STRIP = 5 // a finger on the strip, not yet swiping
        const val SLIDER = 3
        const val CHIP = 4
        const val BUTTON = 10 // BUTTON + index into buttons
        const val TAP_MS = 220L
        const val MULTI_TAP_MS = 300L
        const val LONG_PRESS_MS = 450L
        const val FLING_PX_PER_MS = 0.5f // per density unit
        const val MIN_SENS = 0.3f
        const val MAX_SENS = 3f
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
    }
}
