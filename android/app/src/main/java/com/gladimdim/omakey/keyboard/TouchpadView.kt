package com.gladimdim.omakey.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.gladimdim.omakey.protocol.Wire
import com.gladimdim.omakey.ui.Palette
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A laptop touchpad: a surface that moves the desktop's pointer, and a row
 * along the bottom edge: Ctrl, Shift, Left, Middle, Right, Shift, Ctrl.
 *
 * - One finger moves the pointer. A quick tap clicks; tap and hold
 *   right-clicks (a context menu).
 * - Two fingers scroll, both ways, content following the fingers. A
 *   two-finger tap right-clicks; a three-finger tap middle-clicks.
 * - The bottom row is held for as long as a finger is on it, so dragging is:
 *   hold Left with one finger, move with another; Ctrl/Shift + click works
 *   the same way. A press is sent when the finger lifts or after a short
 *   hold, because swiping up from the row instead puts the touchpad away
 *   (without clicking anything).
 */
class TouchpadView(context: Context) : View(context) {
    interface Sink {
        fun button(code: Int, down: Boolean)
        /** Pointer motion in mouse counts. */
        fun motion(dx: Float, dy: Float)
        /** Scroll in 1/120 of a notch: positive [v] scrolls up, positive [h] right. */
        fun scroll(v: Float, h: Float)
    }

    var sink: Sink? = null

    /** Swiping up from the bottom row drags the touchpad away. */
    interface PanelDrag {
        /** The finger is [dy] px from where it started (negative: up). */
        fun drag(dy: Float)
        fun release(dy: Float, flungUp: Boolean)
    }

    var panelDrag: PanelDrag? = null

    /** Pointer speed multiplier, set by the side sliders or a preset. */
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

    /** False while the computer's omakeyd is too old for the touchpad. */
    var supported = true
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val slop = 6 * density
    private val motionScale = 1.1f
    private val scrollScale = 2.4f // 1/120-notch units per pixel: a notch every 50 px

    // Geometry: the pad, a sensitivity slider down each side, the preset chip,
    // and the bottom row, mirrored around the Middle button.
    private val pad = RectF()
    private val sliders = arrayOf(RectF(), RectF())
    private val chip = RectF()
    private val buttonCodes = intArrayOf(
        KEY_LEFTCTRL, KEY_LEFTSHIFT, Wire.BTN_LEFT, Wire.BTN_MIDDLE, Wire.BTN_RIGHT, KEY_RIGHTSHIFT, KEY_RIGHTCTRL,
    )
    private val buttonLabels = arrayOf("Ctrl", "Shift", "Left", "Middle", "Right", "Shift", "Ctrl")
    private val buttonWeights = floatArrayOf(1f, 1.2f, 2.6f, 1.6f, 2.6f, 1.2f, 1f)
    private val buttons = Array(buttonCodes.size) { RectF() }
    private val buttonHeld = IntArray(buttonCodes.size)

    // Bottom-row fingers not yet sent: they may still turn into a swipe.
    private val pendingSince = LongArray(MAX_POINTERS)
    private val buttonStartY = FloatArray(MAX_POINTERS)
    private val buttonDownAt = LongArray(MAX_POINTERS)
    private val swipeSlop = 14 * density

    // Pointers: what each finger is doing, and where it was last.
    private val role = IntArray(MAX_POINTERS) { NONE }
    private val lastX = FloatArray(MAX_POINTERS)
    private val lastY = FloatArray(MAX_POINTERS)
    private var padFingers = 0

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
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val gap = 6 * density
        val buttonH = maxOf(56 * density, h * 0.2f)
        pad.set(gap, gap, w - gap, h - buttonH - gap * 2)
        val sliderW = 30 * density
        val inset = 8 * density
        sliders[0].set(pad.left + inset, pad.top + inset, pad.left + inset + sliderW, pad.bottom - inset)
        sliders[1].set(pad.right - inset - sliderW, pad.top + inset, pad.right - inset, pad.bottom - inset)
        val chipW = 230 * density
        chip.set(pad.centerX() - chipW / 2, pad.top + inset, pad.centerX() + chipW / 2, pad.top + inset + 30 * density)
        val top = h - buttonH - gap
        val unit = (w - gap * (buttons.size + 1)) / buttonWeights.sum()
        var x = gap
        for (i in buttons.indices) {
            val bw = unit * buttonWeights[i]
            buttons[i].set(x, top, x + bw, h - gap)
            x += bw + gap
        }
    }

    /** Let go of every button, e.g. when the touchpad is put away. */
    fun releaseAll() {
        removeCallbacks(longPress)
        for (i in buttonHeld.indices) {
            if (buttonHeld[i] > 0) sink?.button(buttonCodes[i], false)
            buttonHeld[i] = 0
        }
        role.fill(NONE)
        pendingSince.fill(0)
        padFingers = 0
        invalidate()
    }

    private fun commitButton(b: Int) {
        if (buttonHeld[b]++ == 0) {
            sink?.button(buttonCodes[b], true)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        invalidate()
    }

    /** A bottom-row finger held long enough without swiping is pressed for real. */
    private val commitPending = Runnable {
        val now = android.os.SystemClock.uptimeMillis()
        for (id in 0 until MAX_POINTERS) {
            val since = pendingSince[id]
            if (since != 0L && role[id] >= BUTTON && now - since >= COMMIT_MS) {
                pendingSince[id] = 0
                commitButton(role[id] - BUTTON)
            }
        }
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
        val sl = sliders.indexOfFirst { it.contains(x, y) || expanded(it).contains(x, y) }
        if (sl >= 0) {
            role[id] = SLIDER
            slide(y)
            return
        }
        if (chip.contains(x, y)) {
            role[id] = CHIP
            return
        }
        val b = buttons.indexOfFirst { it.contains(x, y) }
        if (b >= 0) {
            role[id] = BUTTON + b
            pendingSince[id] = ev.eventTime
            buttonDownAt[id] = ev.eventTime
            buttonStartY[id] = y
            postDelayed(commitPending, COMMIT_MS)
            return
        }
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
        // A bottom-row finger swiping up puts the touchpad away instead of pressing.
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id !in 0 until MAX_POINTERS) continue
            if (role[id] == SLIDER) {
                slide(ev.getY(i))
                continue
            }
            val dy = ev.getY(i) - buttonStartY[id]
            if (role[id] >= BUTTON && pendingSince[id] != 0L && dy < -swipeSlop) {
                pendingSince[id] = 0
                role[id] = SWIPE
            }
            if (role[id] == SWIPE) panelDrag?.drag(dy)
        }
        var sumDx = 0f
        var sumDy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id !in 0 until MAX_POINTERS || role[id] != PAD) continue
            val x = ev.getX(i)
            val y = ev.getY(i)
            sumDx += x - lastX[id]
            sumDy += y - lastY[id]
            lastX[id] = x
            lastY[id] = y
            n++
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
        if (r == SLIDER) return
        if (r == CHIP) {
            if (chip.contains(ev.getX(index), ev.getY(index))) onPresetsRequested?.invoke()
            return
        }
        if (r == SWIPE) {
            val dy = ev.getY(index) - buttonStartY[id]
            val speed = dy / maxOf(1L, ev.eventTime - buttonDownAt[id])
            panelDrag?.release(dy, speed < -FLING_PX_PER_MS * density)
            return
        }
        if (r >= BUTTON) {
            val b = r - BUTTON
            if (pendingSince[id] != 0L) {
                // Lifted before the hold timer: a click.
                pendingSince[id] = 0
                commitButton(b)
            }
            if (buttonHeld[b] > 0 && --buttonHeld[b] == 0) sink?.button(buttonCodes[b], false)
            invalidate()
            return
        }
        if (r != PAD) return
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

    /** A slider's touch area is wider than its track. */
    private fun expanded(r: RectF) = RectF(r.left - 10 * density, r.top, r.right + 10 * density, r.bottom)

    /** Slider position (0 bottom .. 1 top) to sensitivity, on a log scale. */
    private fun sensAt(t: Float) = (MIN_SENS * Math.pow((MAX_SENS / MIN_SENS).toDouble(), t.toDouble())).toFloat()

    private fun posOf(sens: Float) =
        (Math.log((sens / MIN_SENS).toDouble()) / Math.log((MAX_SENS / MIN_SENS).toDouble())).toFloat()

    private fun slide(y: Float) {
        val r = sliders[0]
        val t = 1f - ((y - r.top) / r.height()).coerceIn(0f, 1f)
        // Snap to one decimal, which is plenty and makes the label steady.
        val v = (Math.round(sensAt(t) * 20) / 20f).coerceIn(MIN_SENS, MAX_SENS)
        if (v != sensitivity) {
            sensitivity = v
            onSensitivityChanged?.invoke(v)
        }
    }

    private fun click(code: Int) {
        val s = sink ?: return
        s.button(code, true)
        s.button(code, false)
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
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
        // Sensitivity sliders, mirrored.
        val t = posOf(sensitivity)
        for (r in sliders) {
            buttonPaint.color = Palette.KEY_MOD
            canvas.drawRoundRect(r, r.width() / 2, r.width() / 2, buttonPaint)
            val knobY = r.bottom - t * r.height()
            buttonPaint.color = Palette.KEY_ACCENT
            canvas.drawRoundRect(r.left, knobY, r.right, r.bottom, r.width() / 2, r.width() / 2, buttonPaint)
            buttonPaint.color = Palette.ACCENT
            canvas.drawCircle(r.centerX(), knobY.coerceIn(r.top + r.width() / 2, r.bottom - r.width() / 2), r.width() * 0.42f, buttonPaint)
            textPaint.color = Palette.FG_DIM
            textPaint.textSize = 10 * density
            canvas.drawText("fast", r.centerX(), r.top - 2 * density + 12 * density, textPaint)
            canvas.drawText("slow", r.centerX(), r.bottom - 6 * density, textPaint)
        }
        // The preset chip.
        buttonPaint.color = Palette.KEY_MOD
        canvas.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, buttonPaint)
        textPaint.color = Palette.ACCENT
        textPaint.textSize = 12 * density
        val chipText = "${presetName.ifEmpty { "Pointer speed" }} · ${"%.2f".format(sensitivity).trimEnd('0').trimEnd('.')}× ▾"
        canvas.drawText(chipText, chip.centerX(), chip.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)

        textPaint.textSize = 13 * density
        if (supported) {
            textPaint.color = Palette.FG_DIM
            canvas.drawText("move · tap to click · hold for menu · two fingers scroll",
                pad.centerX(), pad.centerY(), textPaint)
            textPaint.textSize = 11 * density
            canvas.drawText("swipe up on the buttons to hide", pad.centerX(), pad.bottom - 12 * density, textPaint)
        } else {
            textPaint.color = Palette.WARN
            canvas.drawText("Update Omakey on your computer to use the touchpad:",
                pad.centerX(), pad.centerY() - 10 * density, textPaint)
            canvas.drawText("bar icon → Update the service",
                pad.centerX(), pad.centerY() + 12 * density, textPaint)
        }
        for (i in buttons.indices) {
            val r = buttons[i]
            val held = buttonHeld[i] > 0
            val mouse = buttonCodes[i] in Wire.BTN_LEFT..Wire.BTN_MIDDLE
            buttonPaint.color = when {
                held -> Palette.ACCENT
                mouse -> Palette.KEY
                else -> Palette.KEY_MOD
            }
            canvas.drawRoundRect(r, radius, radius, buttonPaint)
            textPaint.color = if (held) Palette.BG else if (mouse) Palette.FG else Palette.FG_DIM
            textPaint.textSize = 15 * density
            canvas.drawText(buttonLabels[i], r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
    }

    private companion object {
        const val MAX_POINTERS = 32
        const val NONE = 0
        const val PAD = 1
        const val SWIPE = 2 // a bottom-row finger swiping the touchpad away
        const val SLIDER = 3
        const val CHIP = 4
        const val MIN_SENS = 0.3f
        const val MAX_SENS = 3f
        const val BUTTON = 10 // BUTTON + index into buttons
        const val COMMIT_MS = 120L
        const val FLING_PX_PER_MS = 0.5f // per density unit
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
        const val KEY_RIGHTSHIFT = 54
        const val KEY_RIGHTCTRL = 97
        const val TAP_MS = 220L
        const val MULTI_TAP_MS = 300L
        const val LONG_PRESS_MS = 450L
    }
}
