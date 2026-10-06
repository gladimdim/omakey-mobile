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
 * A laptop touchpad: a surface that moves the desktop's pointer, with
 * Left / Middle / Right buttons along the bottom edge.
 *
 * - One finger moves the pointer. A quick tap clicks; tap and hold
 *   right-clicks (a context menu).
 * - Two fingers scroll, both ways, content following the fingers. A
 *   two-finger tap right-clicks; a three-finger tap middle-clicks.
 * - The buttons are held for as long as a finger is on them, so dragging
 *   is: hold Left with one finger, move with another.
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

    // Geometry.
    private val pad = RectF()
    private val buttons = arrayOf(RectF(), RectF(), RectF())
    private val buttonCodes = intArrayOf(Wire.BTN_LEFT, Wire.BTN_MIDDLE, Wire.BTN_RIGHT)
    private val buttonLabels = arrayOf("Left", "", "Right")
    private val buttonHeld = IntArray(3)

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
        val top = h - buttonH - gap
        val middleW = maxOf(56 * density, w * 0.1f)
        val sideW = (w - gap * 4 - middleW) / 2
        buttons[0].set(gap, top, gap + sideW, h - gap)
        buttons[1].set(gap * 2 + sideW, top, gap * 2 + sideW + middleW, h - gap)
        buttons[2].set(w - gap - sideW, top, w - gap, h - gap)
    }

    /** Let go of every button, e.g. when the touchpad is put away. */
    fun releaseAll() {
        removeCallbacks(longPress)
        for (i in buttonHeld.indices) {
            if (buttonHeld[i] > 0) sink?.button(buttonCodes[i], false)
            buttonHeld[i] = 0
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
        val b = buttons.indexOfFirst { it.contains(x, y) }
        if (b >= 0) {
            role[id] = BUTTON + b
            if (buttonHeld[b]++ == 0) {
                sink?.button(buttonCodes[b], true)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            invalidate()
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
            if (moving) s.motion(dx * motionScale, dy * motionScale)
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
        if (r >= BUTTON) {
            val b = r - BUTTON
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
        textPaint.textSize = 13 * density
        if (supported) {
            textPaint.color = Palette.FG_DIM
            canvas.drawText("move · tap to click · hold for menu · two fingers scroll",
                pad.centerX(), pad.centerY(), textPaint)
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
            buttonPaint.color = if (held) Palette.ACCENT else Palette.KEY_MOD
            canvas.drawRoundRect(r, radius, radius, buttonPaint)
            textPaint.color = if (held) Palette.BG else Palette.FG
            textPaint.textSize = 15 * density
            val label = buttonLabels[i]
            if (label.isNotEmpty()) {
                canvas.drawText(label, r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            } else {
                // The middle button: a small scroll-wheel mark.
                val w = 6 * density
                val h = 14 * density
                canvas.drawRoundRect(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2,
                    w / 2, w / 2, textPaint)
            }
        }
    }

    private companion object {
        const val MAX_POINTERS = 32
        const val NONE = 0
        const val PAD = 1
        const val BUTTON = 10 // BUTTON + index into buttons
        const val TAP_MS = 220L
        const val MULTI_TAP_MS = 300L
        const val LONG_PRESS_MS = 450L
    }
}
