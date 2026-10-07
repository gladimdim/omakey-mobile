package com.gladimdim.omakey.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.VelocityTracker
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
 *   right-clicks (a context menu). Tap, then touch again and move: drag
 *   with the left button held (tap twice quickly for a double click).
 *   Lifting one finger of a two-finger scroll goes back to moving.
 * - Two fingers scroll, both ways, content following the fingers. A
 *   two-finger tap right-clicks; a three-finger tap middle-clicks.
 * - Inside them, a narrow scroll strip down each side of the surface: slide
 *   a finger up to scroll up, down to scroll down, a tick every notch.
 * - Down each side, mirrored: Left click, Right click, Ctrl + Left and
 *   Shift + Left. In portrait mode ([compact]) plain Ctrl and Shift instead,
 *   for the phone's own keyboard: hold one, or tap it to keep it down for
 *   the next key or click (tap again to let go). They are held while touched, so dragging, Ctrl-click
 *   multi-select and Shift-click range select work with the other thumb
 *   moving the pointer.
 * - A strip along the bottom holds the pointer speed: a preset chip and a
 *   slider. Swiping up from the rest of the strip, from the grab bar at the
 *   very bottom (or tapping it), or from a side button puts the touchpad
 *   away. Side buttons press a moment late for that, so a swipe never clicks.
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

    /** Trackpad-style feedback for clicks, buttons and scrolling; null for none. */
    var haptics: Haptics? = null

    /**
     * Portrait mode, under the phone's own keyboard: Ctrl and Shift buttons
     * instead of Ctrl + Left and Shift + Left, slimmer columns, and the speed
     * strip across the whole width. Set before the view is laid out.
     */
    var compact = false
        set(value) {
            field = value
            leftColumn = sideColumn()
            rightColumn = sideColumn()
            buttons = leftColumn + rightColumn
            if (width > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

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

    private enum class Icon { LEFT, RIGHT, KEY }

    /** A button: the codes it holds (pressed in order, released in reverse). */
    private class Button(
        val codes: IntArray,
        val label: String,
        val icon: Icon,
        /** A badge on the mouse icon, or the symbol of a key button. */
        val modifier: String?,
    ) {
        val rect = RectF()
        var held = 0
        /** A key button (Ctrl, Shift) can stay down after a tap, for the next key or click. */
        val sticky get() = icon == Icon.KEY
        var latched = false
        var downAt = 0L
        /** Something was typed or clicked while it was held: lifting it lets go. */
        var used = false
        /** Touched while latched: lifting it lets go. */
        var unlatchOnUp = false
    }

    private fun sideColumn() = if (compact) listOf(
        Button(intArrayOf(Wire.BTN_LEFT), "Left", Icon.LEFT, null),
        Button(intArrayOf(Wire.BTN_RIGHT), "Right", Icon.RIGHT, null),
        Button(intArrayOf(KEY_LEFTCTRL), "Ctrl", Icon.KEY, "⌃"),
        Button(intArrayOf(KEY_LEFTSHIFT), "Shift", Icon.KEY, "⇧"),
    ) else listOf(
        Button(intArrayOf(Wire.BTN_LEFT), "Left", Icon.LEFT, null),
        Button(intArrayOf(Wire.BTN_RIGHT), "Right", Icon.RIGHT, null),
        Button(intArrayOf(KEY_LEFTCTRL, Wire.BTN_LEFT), "Ctrl + Left", Icon.LEFT, "Ctrl"),
        Button(intArrayOf(KEY_LEFTSHIFT, Wire.BTN_LEFT), "Shift + Left", Icon.LEFT, "⇧"),
    )

    // The same four down both sides, mirrored.
    private var leftColumn = sideColumn()
    private var rightColumn = sideColumn()
    private var buttons = leftColumn + rightColumn

    /**
     * A key went to the computer, or a click: latched Ctrl and Shift let go
     * (they were for that one), held ones let go when lifted instead of latching.
     */
    fun modifiersUsed() {
        for (b in buttons) {
            if (!b.sticky) continue
            if (b.latched && !b.unlatchOnUp) {
                b.latched = false
                release(b)
            } else if (b.held > 0) {
                b.used = true
            }
        }
    }

    // Geometry.
    private val pad = RectF()
    /** The scroll strips, left and right, and their slightly wider touch areas. */
    private val scrollers = arrayOf(RectF(), RectF())
    private val scrollTouch = arrayOf(RectF(), RectF())
    /** Fingers on each scroll strip, for drawing it pressed. */
    private val scrollHeld = IntArray(2)
    /** Scroll sent since the last notch tick, in 1/120 notch; also slides the grip lines. */
    private var scrollPending = 0f
    private var scrollTravel = 0f
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
    /** Where a swipe began on screen: the view itself moves with the panel. */
    private val swipeStartRawY = FloatArray(MAX_POINTERS)
    private val swipeDownAt = LongArray(MAX_POINTERS)

    /** Side buttons waiting to press, by pointer: a swipe up in the meantime puts the touchpad away instead. */
    private val deferred = arrayOfNulls<Runnable>(MAX_POINTERS)

    /** The grab bar along the very bottom, when the touchpad can be put away. */
    private val grab = RectF()

    /**
     * The height of the system's gesture zone along the bottom edge, px: a
     * swipe up from there goes home, never here, so the grab bar sits above it.
     */
    var bottomInset = 0f
        set(value) {
            if (field == value) return
            field = value
            if (width > 0) onSizeChanged(width, height, width, height)
            invalidate()
        }

    // The current pad gesture.
    private var gestureStart = 0L
    private var startId = -1
    private var startX = 0f
    private var startY = 0f
    private var maxFingers = 0
    private var travelled = 0f // finger travel, for telling taps from moves
    private var moving = false
    private var longPressed = false

    // Tap-and-drag: a tap's click waits briefly, in case a second touch
    // turns it into a drag.
    private var pendingClick = false
    private var tapDragging = false
    private var dragMoved = false
    private val flushClick = Runnable {
        if (pendingClick) {
            pendingClick = false
            click(Wire.BTN_LEFT, felt = true)
        }
    }

    private val velocity = VelocityTracker.obtain()

    private val longPress = Runnable {
        if (padFingers == 1 && maxFingers == 1 && !moving && !tapDragging) {
            longPressed = true
            click(Wire.BTN_RIGHT, felt = true)
            haptics?.force()
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
        val grabH = if (panelDrag != null) GRAB_DP * density + bottomInset else 0f
        val columnW = if (compact) maxOf(60 * density, w * 0.16f) else maxOf(80 * density, w * 0.095f)
        val bottom = h - grabH - gap * 2 - stripH
        grab.set(0f, h - grabH, w.toFloat(), h.toFloat())

        // Side columns, mirrored: full height, or above the speed strip in portrait.
        val columnBottom = if (compact) bottom else h - grabH - gap
        for ((column, left) in listOf(leftColumn to gap, rightColumn to w - gap - columnW)) {
            val cellH = (columnBottom - gap - gap * (column.size - 1)) / column.size
            column.forEachIndexed { i, b ->
                val top = gap + i * (cellH + gap)
                b.rect.set(left, top, left + columnW, top + cellH)
            }
        }
        // Scroll strips a quarter of a button column wide, then the surface between them.
        val scrollW = columnW * 0.25f
        scrollers[0].set(gap * 2 + columnW, gap, gap * 2 + columnW + scrollW, bottom)
        scrollers[1].set(w - gap * 2 - columnW - scrollW, gap, w - gap * 2 - columnW, bottom)
        for (i in 0..1) scrollTouch[i].set(scrollers[i].left - gap, scrollers[i].top, scrollers[i].right + gap, scrollers[i].bottom)
        pad.set(scrollers[0].right + gap, gap, scrollers[1].left - gap, bottom)
        strip.set(if (compact) gap else scrollers[0].left, pad.bottom + gap, if (compact) w - gap else scrollers[1].right, h - grabH - gap)
        val chipH = 28 * density
        val sliderH = 22 * density
        if (compact) {
            // The chip at the start, the slider filling the rest.
            textPaint.textSize = 12 * density
            val chipW = textPaint.measureText(chipLabel()) + 28 * density
            chip.set(strip.left + 9 * density, strip.centerY() - chipH / 2, strip.left + 9 * density + chipW, strip.centerY() + chipH / 2)
            slider.set(chip.right + 18 * density, strip.centerY() - sliderH / 2, strip.right - 18 * density, strip.centerY() + sliderH / 2)
            sliderTouch.set(slider.left - 10 * density, strip.top, strip.right, strip.bottom)
            return
        }

        // Speed in the strip: the preset chip, then the slider, centred together.
        val chipW = minOf(strip.width() * if (compact) 0.4f else 0.32f, 230 * density)
        val labels = 44 * density // room for "slow" and "fast"
        // The slider takes what's left when the strip is narrow.
        val sliderW = minOf(strip.width() * 0.42f, 320 * density, strip.width() - chipW - 16 * density - labels * 2 - 8 * density)
            .coerceAtLeast(40 * density)
        val total = chipW + 16 * density + labels + sliderW + labels
        var x = strip.centerX() - total / 2
        chip.set(x, strip.centerY() - chipH / 2, x + chipW, strip.centerY() + chipH / 2)
        x += chipW + 16 * density + labels
        slider.set(x, strip.centerY() - sliderH / 2, x + sliderW, strip.centerY() + sliderH / 2)
        sliderTouch.set(slider.left - 8 * density, strip.top, slider.right + 8 * density, strip.bottom)
    }

    /** Let go of every button, e.g. when the touchpad is put away. */
    fun releaseAll() {
        removeCallbacks(longPress)
        // A gesture that was swiping the panel snaps back instead of freezing half way.
        for (id in 0 until MAX_POINTERS) if (role[id] == SWIPE) panelDrag?.release(0f, false)
        removeCallbacks(flushClick)
        if (pendingClick) {
            pendingClick = false
            click(Wire.BTN_LEFT, felt = true)
        }
        if (tapDragging) {
            tapDragging = false
            sink?.button(Wire.BTN_LEFT, false)
        }
        for (b in buttons) {
            b.latched = false
            b.unlatchOnUp = false
            if (b.held > 0) {
                b.held = 1
                release(b, felt = false)
            }
        }
        for (i in deferred.indices) deferred[i]?.let {
            removeCallbacks(it)
            deferred[i] = null
        }
        role.fill(NONE)
        padFingers = 0
        scrollHeld.fill(0)
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Deliver every move as the digitizer reports it. By default Android
        // holds moves until the next frame and resamples them, which adds
        // up to a frame plus 5 ms to every pointer motion.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) requestUnbufferedDispatch(ev)
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
        val sc = scrollTouch.indexOfFirst { it.contains(x, y) }
        if (sc >= 0) {
            role[id] = SCROLL + sc
            scrollHeld[sc]++
            invalidate()
            return
        }
        // A touch in the gap between two side buttons counts as the nearer one.
        val b = buttons.indexOfFirst { it.rect.contains(x, y) }.takeIf { it >= 0 }
            ?: buttons.indexOfFirst { x >= it.rect.left && x <= it.rect.right && y >= it.rect.top - 3 * density && y <= it.rect.bottom + 3 * density }
        if (b >= 0) {
            role[id] = BUTTON + b
            val btn = buttons[b]
            if (btn.sticky) {
                if (btn.latched) {
                    // Already down: this touch lets it go when lifted.
                    btn.unlatchOnUp = true
                    invalidate()
                    return
                }
                btn.downAt = ev.eventTime
                btn.used = false
            }
            if (!btn.sticky && panelDrag != null) {
                // Pressed a moment later: a swipe up from here puts the touchpad away without clicking.
                swipeStartY[id] = y
                swipeStartRawY[id] = ev.getRawY(index)
                velocity.clear()
                velocity.addMovement(ev)
                val later = Runnable {
                    deferred[id] = null
                    if (role[id] == BUTTON + b) press(btn)
                }
                deferred[id] = later
                postDelayed(later, BUTTON_DELAY_MS)
                return
            }
            press(btn)
            return
        }
        if (grab.contains(x, y) || strip.contains(x, y)) {
            role[id] = if (grab.contains(x, y)) GRAB else STRIP
            swipeStartY[id] = y
            swipeStartRawY[id] = ev.getRawY(index)
            swipeDownAt[id] = ev.eventTime
            velocity.clear()
            velocity.addMovement(ev)
            return
        }
        if (!pad.contains(x, y)) return
        role[id] = PAD
        padFingers++
        if (padFingers == 1) {
            if (pendingClick) {
                // A touch right after a tap: hold the button and drag.
                removeCallbacks(flushClick)
                pendingClick = false
                tapDragging = true
                dragMoved = false
                sink?.button(Wire.BTN_LEFT, true)
                haptics?.down()
            }
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
        velocity.addMovement(ev)
        var sumDx = 0f
        var sumDy = 0f
        var n = 0
        for (i in 0 until ev.pointerCount) {
            val id = ev.getPointerId(i)
            if (id !in 0 until MAX_POINTERS) continue
            when {
                role[id] == SLIDER -> slide(ev.getX(i))
                // Swiping up from a side button not pressed yet puts the touchpad away.
                role[id] >= BUTTON && deferred[id] != null && ev.getY(i) - swipeStartY[id] < -swipeSlop -> {
                    removeCallbacks(deferred[id])
                    deferred[id] = null
                    role[id] = SWIPE
                }
                // So does swiping up from the strip or the grab bar.
                (role[id] == STRIP || role[id] == GRAB) && ev.getY(i) - swipeStartY[id] < -swipeSlop -> role[id] = SWIPE
                role[id] == SWIPE -> panelDrag?.drag(ev.getRawY(i) - swipeStartRawY[id])
                role[id] == SCROLL || role[id] == SCROLL + 1 -> {
                    val y = ev.getY(i)
                    stripScroll(y - lastY[id])
                    lastY[id] = y
                }
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
        // The pointer moving: a side button held for a drag goes down now, not a moment later.
        flushDeferred()
        val dx = sumDx / n
        val dy = sumDy / n
        travelled += abs(dx) + abs(dy)
        val s = sink ?: return
        if (padFingers == 1) {
            val i = ev.findPointerIndex(startId)
            if (!moving && i >= 0 && hypot(ev.getX(i) - startX, ev.getY(i) - startY) > slop) {
                moving = true
                dragMoved = true
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
            r == GRAB -> {
                // A tap on the grab bar: back to the keyboard.
                panelDrag?.release(0f, true)
                return
            }
            r == SWIPE -> {
                val dy = ev.getRawY(index) - swipeStartRawY[id]
                // The finger's speed as it let go, not the whole swipe's average.
                velocity.addMovement(ev)
                velocity.computeCurrentVelocity(1000)
                panelDrag?.release(dy, velocity.getYVelocity(id) < -FLING_PX_PER_MS * 1000 * density)
                return
            }
            r == SCROLL || r == SCROLL + 1 -> {
                scrollHeld[r - SCROLL] = (scrollHeld[r - SCROLL] - 1).coerceAtLeast(0)
                invalidate()
                return
            }
            r >= BUTTON -> {
                val btn = buttons[r - BUTTON]
                when {
                    // Lifted before it pressed: a click.
                    deferred[id] != null -> {
                        removeCallbacks(deferred[id])
                        deferred[id] = null
                        press(btn)
                        release(btn)
                    }
                    !btn.sticky -> release(btn)
                    btn.unlatchOnUp -> {
                        btn.unlatchOnUp = false
                        btn.latched = false
                        release(btn)
                    }
                    // A quick tap with nothing typed meanwhile: stays down for the next key.
                    ev.eventTime - btn.downAt < TAP_MS && !btn.used -> {
                        btn.latched = true
                        invalidate()
                    }
                    else -> release(btn)
                }
                return
            }
            r != PAD -> return
        }
        padFingers--
        if (padFingers == 1) {
            // Back to one finger: it moves the pointer, from where it is now.
            val left = (0 until MAX_POINTERS).firstOrNull { role[it] == PAD }
            if (left != null) {
                startId = left
                startX = lastX[left]
                startY = lastY[left]
                moving = false
            }
        }
        if (padFingers > 0) return
        removeCallbacks(longPress)
        val duration = ev.eventTime - gestureStart
        val still = travelled < slop * maxFingers
        if (tapDragging) {
            tapDragging = false
            sink?.button(Wire.BTN_LEFT, false)
            modifiersUsed()
            // Touched again without moving: that was a double tap, a double click.
            if (!dragMoved && maxFingers == 1 && duration < TAP_MS) click(Wire.BTN_LEFT)
            else haptics?.up()
            return
        }
        when {
            maxFingers == 1 && !moving && !longPressed && duration < TAP_MS -> {
                // Felt now, as the finger lifts; the click itself waits for a possible drag.
                haptics?.tap()
                pendingClick = true
                postDelayed(flushClick, DRAG_WINDOW_MS)
            }
            maxFingers == 2 && still && duration < MULTI_TAP_MS -> click(Wire.BTN_RIGHT)
            maxFingers == 3 && still && duration < MULTI_TAP_MS -> click(Wire.BTN_MIDDLE)
        }
    }

    /** A scroll strip finger moved [dy] px: up scrolls up, a tick each notch. */
    private fun stripScroll(dy: Float) {
        if (dy == 0f) return
        val v = -dy * scrollScale
        sink?.scroll(v, 0f)
        scrollTravel += dy
        scrollPending += v
        if (abs(scrollPending) >= NOTCH) {
            scrollPending %= NOTCH
            haptics?.notch()
        }
        invalidate()
    }

    private fun press(b: Button) {
        if (b.held++ == 0) {
            // Modifier first, then the mouse button: Ctrl is down before the click lands.
            for (c in b.codes) sink?.button(c, true)
            haptics?.down()
        }
        invalidate()
    }

    /** Press every side button still waiting, now. */
    private fun flushDeferred() {
        for (i in deferred.indices) deferred[i]?.let {
            removeCallbacks(it)
            it.run()
        }
    }

    /** [felt]: play the release click; not when everything is let go at once. */
    private fun release(b: Button, felt: Boolean = true) {
        if (b.held > 0 && --b.held == 0) {
            for (i in b.codes.indices.reversed()) sink?.button(b.codes[i], false)
            if (felt) haptics?.up()
            // A mouse button let go: that was the click a latched Ctrl or Shift was for.
            if (felt && !b.sticky) modifiersUsed()
        }
        invalidate()
    }

    /** Press and release [code]; [felt]: its haptic has already played. */
    private fun click(code: Int, felt: Boolean = false) {
        val s = sink ?: return
        s.button(code, true)
        s.button(code, false)
        if (!felt) haptics?.tap()
        modifiersUsed()
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
            val hint = "move · tap to click · hold for menu · two fingers scroll"
            // Too wide for the surface: one hint per line.
            val lines = if (textPaint.measureText(hint) > pad.width() - 24 * density) hint.split(" · ") else listOf(hint)
            var y = hintY - (lines.size - 1) * 9 * density
            for (line in lines) {
                canvas.drawText(line, pad.centerX(), y, textPaint)
                y += 18 * density
            }
            if (panelDrag != null) {
                textPaint.textSize = 11 * density
                canvas.drawText("swipe up on the side buttons or the bottom edge to hide", pad.centerX(), y + 2 * density, textPaint)
            }
        } else {
            textPaint.color = Palette.WARN
            canvas.drawText("Update Omakey on your computer to use the touchpad:", pad.centerX(), hintY, textPaint)
            canvas.drawText("bar icon → Update the service", pad.centerX(), hintY + 22 * density, textPaint)
        }

        drawSpeed(canvas)
        if (panelDrag != null) drawGrab(canvas)
        for (i in 0..1) drawScroller(canvas, scrollers[i], scrollHeld[i] > 0)
        for (b in buttons) drawButton(canvas, b, radius)
    }

    /** A scroll strip: arrows at the ends, grip lines between them that follow the finger. */
    private fun drawScroller(canvas: Canvas, r: RectF, held: Boolean) {
        val corner = r.width() / 2
        fillPaint.color = if (held) Palette.KEY_ACCENT else Palette.KEY_MOD
        canvas.drawRoundRect(r, corner, corner, fillPaint)
        val fg = if (held) Palette.FG_ON_ACCENT else Palette.FG_DIM
        strokePaint.color = fg
        val half = minOf(r.width() * 0.3f, 6 * density)
        val cx = r.centerX()
        val inset = 10 * density
        // ⌃ at the top, ⌄ at the bottom.
        val upTip = r.top + inset
        canvas.drawLine(cx - half, upTip + half, cx, upTip, strokePaint)
        canvas.drawLine(cx, upTip, cx + half, upTip + half, strokePaint)
        val downTip = r.bottom - inset
        canvas.drawLine(cx - half, downTip - half, cx, downTip, strokePaint)
        canvas.drawLine(cx, downTip, cx + half, downTip - half, strokePaint)
        // Grip lines in the middle third, sliding with the scroll.
        val top = r.top + r.height() / 3
        val bottom = r.bottom - r.height() / 3
        val step = 9 * density
        val shift = ((scrollTravel % step) + step) % step
        canvas.save()
        canvas.clipRect(r.left, top, r.right, bottom)
        var y = top - step + shift
        while (y < bottom + step) {
            canvas.drawLine(cx - half * 0.8f, y, cx + half * 0.8f, y, strokePaint)
            y += step
        }
        canvas.restore()
    }

    private fun drawSpeed(canvas: Canvas) {
        fillPaint.color = Palette.SURFACE
        canvas.drawRoundRect(strip, strip.height() / 2, strip.height() / 2, fillPaint)
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
        if (!compact) {
            textPaint.color = Palette.FG_DIM
            textPaint.textSize = 10 * density
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("slow", r.left - 8 * density, r.centerY() + 4 * density, textPaint)
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("fast", r.right + 8 * density, r.centerY() + 4 * density, textPaint)
            textPaint.textAlign = Paint.Align.CENTER
        }

        fillPaint.color = Palette.KEY_MOD
        canvas.drawRoundRect(chip, chip.height() / 2, chip.height() / 2, fillPaint)
        textPaint.color = Palette.ACCENT
        textPaint.textSize = 12 * density
        canvas.drawText(chipLabel(), chip.centerX(), chip.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    /**
     * The grab bar: a hairline across the whole width, a handle in the
     * middle with an arrow pointing the way the touchpad goes.
     */
    private fun drawGrab(canvas: Canvas) {
        // In the part above the system's gesture zone.
        val cy = grab.top + (grab.height() - bottomInset) / 2 + 2 * density
        fillPaint.color = Palette.KEY
        canvas.drawRect(grab.left, grab.top + density, grab.right, grab.top + 2 * density, fillPaint)
        val hw = 26 * density
        fillPaint.color = Palette.FG_DIM
        canvas.drawRoundRect(grab.centerX() - hw, cy - 2 * density, grab.centerX() + hw, cy + 2 * density, 2 * density, 2 * density, fillPaint)
        // ⌃ either side of the handle.
        strokePaint.color = Palette.FG_DIM
        val a = 4 * density
        for (x in listOf(grab.centerX() - hw - 16 * density, grab.centerX() + hw + 16 * density)) {
            canvas.drawLine(x - a, cy + a / 2, x, cy - a / 2, strokePaint)
            canvas.drawLine(x, cy - a / 2, x + a, cy + a / 2, strokePaint)
        }
    }

    /** The preset and speed; just the speed when narrow. */
    private fun chipLabel(): String {
        val value = "%.2f".format(sensitivity).trimEnd('0').trimEnd('.')
        return if (compact) "Speed $value× ▾" else "${presetName.ifEmpty { "Pointer speed" }} · $value× ▾"
    }

    private fun drawButton(canvas: Canvas, b: Button, radius: Float) {
        if (b.sticky) return drawKeyButton(canvas, b, radius)
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

    /** Ctrl or Shift: its symbol large, the name under it; latched ones stay tinted. */
    private fun drawKeyButton(canvas: Canvas, b: Button, radius: Float) {
        val r = b.rect
        val touched = b.held > 0 && !b.latched || b.unlatchOnUp
        fillPaint.color = when {
            touched -> Palette.ACCENT
            b.latched -> Palette.KEY_ACCENT
            else -> Palette.KEY_MOD
        }
        canvas.drawRoundRect(r, radius, radius, fillPaint)
        val fg = when {
            touched -> Palette.BG
            b.latched -> Palette.FG_ON_ACCENT
            else -> Palette.FG
        }
        textPaint.color = fg
        textPaint.textSize = minOf(r.height() * 0.32f, r.width() * 0.42f)
        canvas.drawText(b.modifier ?: "", r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2 - r.height() * 0.08f, textPaint)
        textPaint.textSize = 11 * density
        canvas.drawText(if (b.latched && !touched) "${b.label} ●" else b.label, r.centerX(), r.bottom - 8 * density, textPaint)
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
        const val SCROLL = 6 // SCROLL + 0 left strip, + 1 right strip
        const val GRAB = 8 // a finger on the grab bar, not yet swiping
        /** How long a side button waits before pressing, so a swipe up from it never clicks. */
        const val BUTTON_DELAY_MS = 120L
        const val GRAB_DP = 22f
        const val BUTTON = 10 // BUTTON + index into buttons
        const val TAP_MS = 220L
        const val MULTI_TAP_MS = 300L
        const val LONG_PRESS_MS = 450L
        /** How long a tap waits for a second touch that would make it a drag. */
        const val DRAG_WINDOW_MS = 150L
        const val FLING_PX_PER_MS = 0.5f // per density unit
        const val MIN_SENS = 0.3f
        const val MAX_SENS = 3f
        const val NOTCH = 120f
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
    }
}
