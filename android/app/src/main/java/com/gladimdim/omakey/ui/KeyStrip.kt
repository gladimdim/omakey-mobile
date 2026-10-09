package com.gladimdim.omakey.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import com.gladimdim.omakey.keyboard.Haptics
import com.gladimdim.omakey.keyboard.KeyboardModel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The keys the phone's own keyboard lacks, above it in portrait mode, in
 * two rows.
 *
 * The lower row is pages swiped sideways: digits, F1–F12, navigation, and
 * system keys (Super and Alt, Print Screen, media). The pages follow the
 * finger and settle with an animation drawn every frame, asking the display
 * for its highest refresh rate while they move.
 *
 * The upper row stays put and holds the keys you put there. Its pencil
 * button starts editing, and the keys tremble like icons on an iPhone's home
 * screen: drag a key up from the pages into a slot (or tap it for the first
 * empty one), drag one along the row to move it (a key in the way swaps
 * places with it), and tap one, or drag it down off the row, to clear its
 * slot. Empty slots show as dashed outlines. The tick button is done.
 *
 * A key types when lifted, so a swipe that starts on one types nothing;
 * held still, it goes down on the computer and repeats there until lifted.
 *
 * Super, Ctrl, Alt and Shift are for shortcuts with the phone's keyboard:
 * hold one and type (on the phone's keyboard, or another key here with a
 * second finger), or tap it to keep it down for the next key or click, and
 * tap again to let go. Several can be down at once: Ctrl + Alt + T.
 */
class KeyStrip(
    context: Context,
    private val typist: Typist,
    private val sink: KeyboardModel.Sink,
) : View(context) {
    var haptics: Haptics? = null

    /** The page settled on, to come back to it next time. */
    var onPageChanged: ((Int) -> Unit)? = null

    /** The upper row was rearranged: its key codes, 0 for an empty slot, to keep. */
    var onSlotsChanged: ((List<Int>) -> Unit)? = null

    private class Key(val label: String, val code: Int, val sticky: Boolean = false) {
        /** Kept down after a tap, until the next key or another tap. */
        var latched = false
        /** Down on the computer because a finger holds it. */
        var down = false
        /** A key or click happened while held: lifting lets go instead of latching. */
        var used = false
        var downAt = 0L
        /** Touched while latched: lifting lets go. */
        var unlatchOnUp = false
    }

    private val pages: List<List<Key>> = listOf(
        "1234567890".mapIndexed { i, c -> Key(c.toString(), 2 + i) },
        (1..12).map { Key("F$it", if (it <= 10) 58 + it else 76 + it) },
        listOf(
            Key("Esc", 1), Key("Tab", 15), Key("Home", 102), Key("End", 107), Key("PgUp", 104), Key("PgDn", 109),
            Key("Del", 111), Key("←", 105), Key("↑", 103), Key("↓", 108), Key("→", 106),
        ),
        listOf(
            Key("Super", KEY_LEFTMETA, sticky = true), Key("Ctrl", KEY_LEFTCTRL, sticky = true),
            Key("Alt", KEY_LEFTALT, sticky = true), Key("Shift", KEY_LEFTSHIFT, sticky = true),
            Key("PrtSc", 99), Key("Menu", 127), Key("Mute", 113), Key("Vol−", 114), Key("Vol+", 115), Key("⏯", 164),
        ),
    )

    /** Every key once. The upper row holds the same keys as the pages, so a latched one shows latched in both. */
    private val catalog = pages.flatten()

    /** The upper row; null is an empty slot. */
    private val slots = arrayOfNulls<Key>(SLOTS)

    /** The upper row as key codes, 0 for an empty slot; setting it fills the row. */
    var slotCodes: List<Int>
        get() = slots.map { it?.code ?: 0 }
        set(value) {
            for (i in slots.indices) slots[i] = catalog.find { it.code == value.getOrNull(i) }
            invalidate()
        }

    /** Arranging the upper row: keys tremble, move instead of typing. */
    private var editing = false

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    /** How far the pages are scrolled, in px; page n rests at n × width. */
    private var offset = 0f
    private var current = 0

    /** The page showing; setting it jumps there. */
    var page: Int
        get() = current
        set(value) {
            current = value.coerceIn(0, pages.size - 1)
            offset = current * width.toFloat()
            invalidate()
        }

    // The finger.
    private var downX = 0f
    private var downY = 0f
    private var startOffset = 0f
    private var swiping = false
    private var pressed: Key? = null
    /** The upper row's slot under the finger, or -1 on the pages. */
    private var pressedSlot = -1
    /** The finger went down on the pencil (or tick) button. */
    private var pressedEdit = false
    private var holding: Key? = null
    private val velocity = VelocityTracker.obtain()
    private var settle: ValueAnimator? = null

    // A key picked up while editing, following the finger.
    private var dragged: Key? = null
    /** Where it came from: an upper row slot, or -1 for the pages. */
    private var dragFrom = -1
    /** The slot it would land in, or -1 for none (off the row). */
    private var dragTo = -1
    private var dragX = 0f
    private var dragY = 0f

    private val hold = Runnable {
        val k = pressed ?: return@Runnable
        // Held still: down on the computer, which repeats it until lifted.
        holding = k
        sink.keyDown(k.code)
    }

    /** While editing, a key on the pages held still is picked up too, not only one dragged upwards. */
    private val pickUp = Runnable {
        val k = pressed ?: return@Runnable
        if (editing && !swiping) startDrag(k, -1)
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val slotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(4 * density, 3 * density), 0f)
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val icon = Path()
    private val r = RectF()

    init {
        setBackgroundColor(Palette.BG)
    }

    /** A key went to the computer, or a click: latched modifiers were for it; held ones now let go when lifted. */
    fun modifiersUsed() {
        for (k in catalog) {
            if (k.latched && !k.unlatchOnUp) {
                k.latched = false
                sink.keyUp(k.code)
                invalidate()
            } else if (k.down) {
                k.used = true
            }
        }
    }

    /** Forget everything held, e.g. when the screen goes away (the computer is told separately). */
    fun reset() {
        removeCallbacks(hold)
        removeCallbacks(modifierDown)
        removeCallbacks(pickUp)
        for (k in catalog) {
            k.latched = false
            k.down = false
            k.unlatchOnUp = false
        }
        pressed = null
        holding = null
        dragged = null
        editing = false
        others.clear()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        offset = current * w.toFloat()
    }

    /** Keys under fingers other than the first, by pointer id: tapped when lifted. */
    private val others = HashMap<Int, Key>()
    private var primaryId = -1

    /** A modifier held still goes down on the computer: not at once, so a swipe's start never presses it. */
    private val modifierDown = Runnable {
        val k = pressed ?: return@Runnable
        if (!k.sticky || k.latched || swiping) return@Runnable
        k.down = true
        k.used = false
        sink.keyDown(k.code)
        invalidate()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocity.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                settle?.cancel()
                fast(true)
                primaryId = ev.getPointerId(0)
                downX = ev.x
                downY = ev.y
                startOffset = offset
                swiping = false
                velocity.clear()
                velocity.addMovement(ev)
                val top = ev.y < topRowBottom
                pressedEdit = top && ev.x >= slotsRight
                if (pressedEdit) {
                    haptics?.key(down = true)
                    invalidate()
                    return true
                }
                pressedSlot = if (top) slotAt(ev.x) else -1
                val k = keyAt(ev.x, ev.y)
                pressed = k
                if (k != null) {
                    haptics?.key(down = true)
                    when {
                        editing -> if (!top) postDelayed(pickUp, PICK_UP_MS)
                        k.latched -> k.unlatchOnUp = true
                        k.sticky -> {
                            k.downAt = ev.eventTime
                            postDelayed(modifierDown, MODIFIER_DELAY_MS)
                        }
                        else -> postDelayed(hold, HOLD_MS)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Another finger: a key while the first holds a modifier (Ctrl + →). Not while editing.
                if (editing || pressedEdit) return true
                val i = ev.actionIndex
                keyAt(ev.getX(i), ev.getY(i))?.let {
                    others[ev.getPointerId(i)] = it
                    haptics?.key(down = true)
                    invalidate()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == primaryId) {
                    // The first finger lifted first: done with it, as if it were the last.
                    liftPrimary(ev)
                    primaryId = -1
                } else {
                    others.remove(id)?.let(::tap)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val i = ev.findPointerIndex(primaryId)
                if (i < 0 || pressedEdit) return true
                val x = ev.getX(i)
                val y = ev.getY(i)
                if (dragged != null) {
                    moveDrag(x, y)
                    return true
                }
                val k = pressed
                val dx = x - downX
                val dy = y - downY
                if (pressedSlot >= 0) {
                    // The upper row doesn't swipe; while editing, a key slid along it moves.
                    if (editing && k != null && hypot(dx, dy) > slop) {
                        startDrag(k, pressedSlot)
                        moveDrag(x, y)
                    }
                    return true
                }
                if (editing && !swiping && k != null && abs(dy) > slop && abs(dy) > abs(dx)) {
                    // Editing: up (or down) picks a key off the page, sideways still swipes.
                    startDrag(k, -1)
                    moveDrag(x, y)
                    return true
                }
                if (!swiping && holding == null && (k?.down != true) && abs(dx) > slop) {
                    swiping = true
                    removeCallbacks(hold)
                    removeCallbacks(modifierDown)
                    removeCallbacks(pickUp)
                    if (k != null) k.unlatchOnUp = false
                    pressed = null
                    // Pick up from here, so the page doesn't jump by the slop.
                    downX = x
                    startOffset = offset
                }
                if (swiping) {
                    offset = rubber(startOffset - (x - downX))
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                val id = ev.getPointerId(ev.actionIndex)
                if (id == primaryId) liftPrimary(ev) else others.remove(id)?.let(::tap)
                primaryId = -1
                others.clear()
                if (!swiping) fast(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(hold)
                removeCallbacks(modifierDown)
                removeCallbacks(pickUp)
                holding?.let { sink.keyUp(it.code) }
                pressed?.let { if (it.down) letGo(it) }
                holding = null
                pressed = null
                pressedEdit = false
                dragged = null
                others.clear()
                if (swiping) snap(0f) else fast(false)
                invalidate()
            }
        }
        return true
    }

    private fun liftPrimary(ev: MotionEvent) {
        removeCallbacks(hold)
        removeCallbacks(modifierDown)
        removeCallbacks(pickUp)
        if (pressedEdit) {
            pressedEdit = false
            haptics?.key(down = false)
            // Lifted off the button: changed their mind.
            val i = ev.actionIndex
            if (ev.getY(i) < topRowBottom && ev.getX(i) >= slotsRight - slop) setEditing(!editing)
            return
        }
        if (dragged != null) return drop()
        val held = holding
        val k = pressed
        holding = null
        pressed = null
        when {
            swiping -> {
                velocity.computeCurrentVelocity(1000)
                snap(velocity.xVelocity)
            }
            held != null -> {
                sink.keyUp(held.code)
                haptics?.key(down = false)
            }
            k == null -> {}
            editing -> {
                haptics?.key(down = false)
                // A tap while editing: a key on the row leaves it, one on the pages takes the first empty slot.
                val before = slotCodes
                if (pressedSlot >= 0) StripSlots.drop(slots, k, pressedSlot, -1)
                else slots.indexOfFirst { it == null }.takeIf { it >= 0 && k !in slots }?.let { slots[it] = k }
                if (slotCodes != before) onSlotsChanged?.invoke(slotCodes)
            }
            k.sticky -> liftModifier(k, ev.eventTime)
            else -> tap(k)
        }
    }

    private fun setEditing(on: Boolean) {
        editing = on
        haptics?.notch()
        invalidate()
    }

    /** A modifier lifted: let go, or stay down for the next key after a quick tap. */
    private fun liftModifier(k: Key, at: Long) {
        haptics?.key(down = false)
        when {
            k.unlatchOnUp -> {
                k.unlatchOnUp = false
                k.latched = false
                sink.keyUp(k.code)
            }
            // Held, and something was typed with it: done.
            k.down && k.used -> letGo(k)
            // A quick tap (or held but nothing typed yet, briefly): keep it down for the next key.
            at - k.downAt < TAP_MS -> {
                if (!k.down) sink.keyDown(k.code)
                k.down = false
                k.latched = true
            }
            k.down -> letGo(k)
        }
    }

    private fun letGo(k: Key) {
        k.down = false
        sink.keyUp(k.code)
    }

    private fun tap(k: Key) {
        haptics?.key(down = false)
        if (!k.sticky) return typist.key(k.code)
        // A modifier under a second finger: toggled.
        k.latched = !k.latched
        if (k.latched) sink.keyDown(k.code) else sink.keyUp(k.code)
    }

    private fun startDrag(k: Key, from: Int) {
        removeCallbacks(pickUp)
        pressed = null
        dragged = k
        dragFrom = from
        dragTo = from
        dragX = downX
        dragY = downY
        parent?.requestDisallowInterceptTouchEvent(true)
        haptics?.force()
        invalidate()
    }

    private fun moveDrag(x: Float, y: Float) {
        dragX = x
        dragY = y
        val to = dropSlot()
        if (to != dragTo) {
            dragTo = to
            haptics?.notch()
        }
        invalidate()
    }

    private fun drop() {
        val k = dragged ?: return
        dragged = null
        val before = slotCodes
        StripSlots.drop(slots, k, dragFrom, dropSlot())
        haptics?.key(down = false)
        fast(false)
        if (slotCodes != before) onSlotsChanged?.invoke(slotCodes)
    }

    /** Where the dragged key would land: a slot while it's over the upper row (or above it), else -1. */
    private fun dropSlot() = if (dragY - liftBy < topRowBottom) slotAt(dragX) else -1

    /** Past the first or last page the pages move a third as far: they resist. */
    private fun rubber(o: Float): Float {
        val max = (pages.size - 1) * width.toFloat()
        return when {
            o < 0 -> o / 3
            o > max -> max + (o - max) / 3
            else -> o
        }
    }

    /** Settle on a page: the next one after a flick, else the nearest. */
    private fun snap(vx: Float) {
        val w = width.toFloat()
        val flick = FLICK_DP_PER_S * density
        val nearest = (offset / w).roundToInt()
        val target = when {
            vx < -flick -> (startOffset / w).roundToInt() + 1
            vx > flick -> (startOffset / w).roundToInt() - 1
            else -> nearest
        }.coerceIn(0, pages.size - 1)
        val from = offset
        val to = target * w
        val distance = abs(to - from) / w
        settle = ValueAnimator.ofFloat(from, to).apply {
            duration = (180 + 160 * distance).toLong().coerceAtMost(340)
            // Starts at the finger's speed and eases in, like a sheet let go.
            interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
            addUpdateListener {
                offset = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    fast(false)
                }
            })
            start()
        }
        if (target != current) {
            current = target
            onPageChanged?.invoke(target)
            haptics?.notch()
        }
    }

    /** Ask for the display's highest refresh rate while moving (Android 15+). */
    private fun fast(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) {
            requestedFrameRate = if (on) REQUESTED_FRAME_RATE_CATEGORY_HIGH else REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
    }

    private val dotsH get() = 10 * density
    private val pad get() = 6 * density
    private val gap get() = 4 * density
    /** The upper row is the view's top [topRowBottom] px; the pages and their dots fill the rest. */
    private val topRowBottom get() = height * TOP_SHARE
    /** The upper row's slots end here; the pencil (or tick) button is to the right. */
    private val slotsRight get() = width - pad - EDIT_DP * density
    /** A dragged key is drawn this far above the finger, so the finger doesn't hide it. */
    private val liftBy get() = 18 * density

    /** The upper row's slot at x, the nearest one past either end. */
    private fun slotAt(x: Float): Int {
        val slotW = (slotsRight - pad) / SLOTS
        return ((x - pad) / slotW).toInt().coerceIn(0, SLOTS - 1)
    }

    /** The key at a point: in the upper row's slot, or on the page under it. */
    private fun keyAt(x: Float, y: Float): Key? {
        if (y < topRowBottom) return if (x < slotsRight) slots[slotAt(x)] else null
        if (y > height - dotsH) return null
        val w = width.toFloat()
        val contentX = x + offset
        val p = (contentX / w).toInt()
        val keys = pages.getOrNull(p) ?: return null
        val local = contentX - p * w - pad
        val keyW = (w - pad * 2) / keys.size
        val i = (local / keyW).toInt()
        return keys.getOrNull(i)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0) return
        val radius = 7 * density
        val now = SystemClock.uptimeMillis()
        var n = 0

        // The upper row: while a key is dragged, as it would be if dropped here.
        val k0 = dragged
        val shown = slots.copyOf()
        if (k0 != null) StripSlots.drop(shown, k0, dragFrom, dragTo)
        val slotW = (slotsRight - pad) / SLOTS
        val rowTop = pad * 0.5f
        val rowBottom = topRowBottom - pad * 0.5f
        for ((i, k) in shown.withIndex()) {
            val left = pad + i * slotW
            r.set(left + gap / 2, rowTop, left + slotW - gap / 2, rowBottom)
            if (k == null || k === k0) {
                // Empty, or where the dragged key would land: a dashed outline.
                val landing = k != null
                val inset = slotPaint.strokeWidth / 2
                r.inset(inset, inset)
                slotPaint.color = if (landing) Palette.ACCENT else Palette.FG_DIM
                slotPaint.alpha = if (landing) 255 else 110
                canvas.drawRoundRect(r, radius, radius, slotPaint)
            } else {
                trembling(canvas, n++, now) {
                    drawKey(canvas, k, radius)
                    if (editing) removeBadge(canvas)
                }
            }
        }
        if (k0 == null && shown.all { it == null }) {
            // A first look: say how to fill it.
            hint(canvas, if (editing) "Drag keys up here" else "Tap the pencil to choose keys for this row", (rowTop + rowBottom) / 2)
        }
        drawEditButton(canvas, rowTop, rowBottom)

        // The pages.
        val keyTop = topRowBottom + pad * 0.5f
        val keyBottom = height - dotsH
        canvas.save()
        canvas.clipRect(0f, topRowBottom, w, height.toFloat())
        for ((p, keys) in pages.withIndex()) {
            val pageLeft = p * w - offset
            if (pageLeft > w || pageLeft + w < 0) {
                n += keys.size
                continue
            }
            val keyW = (w - pad * 2) / keys.size
            for ((i, k) in keys.withIndex()) {
                val left = pageLeft + pad + i * keyW
                r.set(left + gap / 2, keyTop, left + keyW - gap / 2, keyBottom)
                trembling(canvas, n++, now) { drawKey(canvas, k, radius) }
            }
        }
        canvas.restore()
        // Page dots; the current one a pill that slides with the pages.
        val pageCount = pages.size
        val step = 12 * density
        val cy = height - dotsH / 2
        val startX = w / 2 - step * (pageCount - 1) / 2
        keyPaint.color = Palette.FG_DIM
        for (i in 0 until pageCount) canvas.drawCircle(startX + i * step, cy, 2 * density, keyPaint)
        val at = (offset / w).coerceIn(0f, (pageCount - 1).toFloat())
        val x = startX + at * step
        keyPaint.color = Palette.ACCENT
        r.set(x - 5 * density, cy - 2 * density, x + 5 * density, cy + 2 * density)
        canvas.drawRoundRect(r, 2 * density, 2 * density, keyPaint)

        // The dragged key, raised above the finger and a little larger; faded where dropping clears it.
        if (k0 != null) {
            val kw = slotW * 1.15f
            val kh = (rowBottom - rowTop) * 1.15f
            val cx = dragX.coerceIn(kw / 2, w - kw / 2)
            val cy2 = (dragY - liftBy).coerceIn(kh / 2, height - kh / 2)
            r.set(cx - kw / 2, cy2 - kh / 2, cx + kw / 2, cy2 + kh / 2)
            keyPaint.color = Palette.ACCENT
            keyPaint.alpha = if (dragTo < 0) 140 else 255
            canvas.drawRoundRect(r, radius, radius, keyPaint)
            keyPaint.alpha = 255
            drawLabel(canvas, k0.label, Palette.BG)
        }

        // Trembling is drawn frame by frame.
        if (editing) postInvalidateOnAnimation()
    }

    /** Draw with the key in [r] rocking while editing, each a little out of step with its neighbours. */
    private inline fun trembling(canvas: Canvas, i: Int, now: Long, draw: () -> Unit) {
        if (!editing) return draw()
        val phase = (now % TREMBLE_MS) / TREMBLE_MS.toFloat() * 2 * PI + i * 1.7
        val angle = sin(phase).toFloat() * TREMBLE_DEG
        canvas.save()
        canvas.rotate(angle, r.centerX(), r.centerY())
        draw()
        canvas.restore()
    }

    /** A key in [r]. */
    private fun drawKey(canvas: Canvas, k: Key, radius: Float) {
        val down = k === pressed || k === holding || k.down || k in others.values
        keyPaint.color = when {
            down -> Palette.ACCENT
            k.latched -> Palette.KEY_ACCENT
            k.sticky -> Palette.KEY_MOD
            else -> Palette.KEY
        }
        canvas.drawRoundRect(r, radius, radius, keyPaint)
        drawLabel(canvas, k.label, when {
            down -> Palette.BG
            k.latched -> Palette.FG_ON_ACCENT
            else -> Palette.FG
        })
    }

    /** A key's label, centred in [r] and as large as fits: "PgDn" on a narrow key, "5" big. */
    private fun drawLabel(canvas: Canvas, label: String, color: Int) {
        textPaint.color = color
        textPaint.textSize = 14 * density
        val fit = (r.width() - 6 * density) / textPaint.measureText(label)
        if (fit < 1f) textPaint.textSize *= fit
        canvas.drawText(label, r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    /** The small × in the corner of a key in [r] while editing: tapping takes it off the row. */
    private fun removeBadge(canvas: Canvas) {
        val d = density
        val cx = r.left + 3 * d
        val cy = r.top + 3 * d
        keyPaint.color = Palette.FG_DIM
        canvas.drawCircle(cx, cy, 6 * d, keyPaint)
        iconPaint.color = Palette.BG
        iconPaint.strokeWidth = 1.5f * d
        canvas.drawLine(cx - 2.2f * d, cy - 2.2f * d, cx + 2.2f * d, cy + 2.2f * d, iconPaint)
        canvas.drawLine(cx - 2.2f * d, cy + 2.2f * d, cx + 2.2f * d, cy - 2.2f * d, iconPaint)
    }

    /** Right of the slots: a pencil to start arranging, a tick on an accent circle to finish. */
    private fun drawEditButton(canvas: Canvas, top: Float, bottom: Float) {
        val d = density
        val cx = (slotsRight + width - pad) / 2
        val cy = (top + bottom) / 2
        iconPaint.strokeWidth = 2f * d
        if (editing) {
            keyPaint.color = if (pressedEdit) Palette.KEY_ACCENT else Palette.ACCENT
            canvas.drawCircle(cx, cy, 13 * d, keyPaint)
            iconPaint.color = Palette.BG
            icon.reset()
            icon.moveTo(cx - 5.5f * d, cy + 0.5f * d)
            icon.lineTo(cx - 1.5f * d, cy + 4.5f * d)
            icon.lineTo(cx + 6f * d, cy - 4f * d)
            canvas.drawPath(icon, iconPaint)
        } else {
            if (pressedEdit) {
                keyPaint.color = Palette.KEY
                canvas.drawCircle(cx, cy, 13 * d, keyPaint)
            }
            iconPaint.color = Palette.FG_DIM
            // A pencil leaning right: the shaft, the tip, the end.
            canvas.save()
            canvas.rotate(45f, cx, cy)
            r.set(cx - 2.5f * d, cy - 7f * d, cx + 2.5f * d, cy + 4f * d)
            canvas.drawRect(r, iconPaint)
            icon.reset()
            icon.moveTo(cx - 2.5f * d, cy + 4f * d)
            icon.lineTo(cx, cy + 8f * d)
            icon.lineTo(cx + 2.5f * d, cy + 4f * d)
            canvas.drawPath(icon, iconPaint)
            canvas.drawLine(cx - 2.5f * d, cy - 4.5f * d, cx + 2.5f * d, cy - 4.5f * d, iconPaint)
            canvas.restore()
        }
    }

    /** A line of help over the upper row's slots, on the background so the outlines don't cross it. */
    private fun hint(canvas: Canvas, text: String, cy: Float) {
        textPaint.textSize = 11 * density
        textPaint.color = Palette.FG_DIM
        val cx = (pad + slotsRight) / 2
        val tw = textPaint.measureText(text) + 16 * density
        r.set(cx - tw / 2, cy - 9 * density, cx + tw / 2, cy + 9 * density)
        keyPaint.color = Palette.BG
        canvas.drawRoundRect(r, 9 * density, 9 * density, keyPaint)
        canvas.drawText(text, r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
    }

    private companion object {
        const val KEY_LEFTMETA = 125
        const val KEY_LEFTALT = 56
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
        const val HOLD_MS = 350L
        const val PICK_UP_MS = 300L
        const val MODIFIER_DELAY_MS = 100L
        const val TAP_MS = 300L
        const val FLICK_DP_PER_S = 350f
        const val SLOTS = 10
        /** The pencil button's width, right of the slots. */
        const val EDIT_DP = 34f
        /** The upper row's share of the height; the pages' row also holds the dots. */
        const val TOP_SHARE = 0.46f
        const val TREMBLE_MS = 260L
        const val TREMBLE_DEG = 2.2f
    }
}

/** How the upper row changes when a key is dropped on it. */
object StripSlots {
    /**
     * Drop [key], dragged from slot [from] (or -1 for the pages), on slot [to]
     * (or -1 for off the row). A key in the way swaps places with it, so the
     * row never holds a key twice; one dragged off the row leaves its slot empty.
     */
    fun <T : Any> drop(slots: Array<T?>, key: T, from: Int, to: Int) {
        if (to < 0) {
            if (from >= 0) slots[from] = null
            return
        }
        // From the pages but already in the row: moved from where it is.
        val src = if (from >= 0) from else slots.indexOf(key)
        val displaced = slots[to]
        slots[to] = key
        if (src >= 0 && src != to) slots[src] = displaced
    }
}
