package com.gladimdim.omakey.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import com.gladimdim.omakey.keyboard.Haptics
import com.gladimdim.omakey.keyboard.KeyboardModel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The keys the phone's own keyboard lacks, above it in portrait mode, as
 * pages swiped sideways: digits, F1–F12, navigation, and system keys
 * (Super and Alt, Print Screen, media). The pages follow the finger and
 * settle with an animation drawn every frame, asking the display for its
 * highest refresh rate while they move.
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
    private var startOffset = 0f
    private var swiping = false
    private var pressed: Key? = null
    private var holding: Key? = null
    private val velocity = VelocityTracker.obtain()
    private var settle: ValueAnimator? = null

    private val hold = Runnable {
        val k = pressed ?: return@Runnable
        // Held still: down on the computer, which repeats it until lifted.
        holding = k
        sink.keyDown(k.code)
    }

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val r = RectF()

    init {
        setBackgroundColor(Palette.BG)
    }

    /** A key went to the computer, or a click: latched modifiers were for it; held ones now let go when lifted. */
    fun modifiersUsed() {
        for (p in pages) for (k in p) {
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
        for (p in pages) for (k in p) {
            k.latched = false
            k.down = false
            k.unlatchOnUp = false
        }
        pressed = null
        holding = null
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
                startOffset = offset
                swiping = false
                velocity.clear()
                velocity.addMovement(ev)
                val k = keyAt(ev.x, ev.y)
                pressed = k
                if (k != null) {
                    haptics?.key(down = true)
                    when {
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
                // Another finger: a key while the first holds a modifier (Ctrl + →).
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
                if (i < 0) return true
                val x = ev.getX(i)
                val dx = x - downX
                val k = pressed
                if (!swiping && holding == null && (k?.down != true) && abs(dx) > slop) {
                    swiping = true
                    removeCallbacks(hold)
                    removeCallbacks(modifierDown)
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
                holding?.let { sink.keyUp(it.code) }
                pressed?.let { if (it.down) letGo(it) }
                holding = null
                pressed = null
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
            k.sticky -> liftModifier(k, ev.eventTime)
            else -> tap(k)
        }
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

    /** The key at a point, on the page under it. */
    private fun keyAt(x: Float, y: Float): Key? {
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
        val keyTop = pad * 0.5f
        val keyBottom = height - dotsH
        val radius = 7 * density
        for ((p, keys) in pages.withIndex()) {
            val pageLeft = p * w - offset
            if (pageLeft > w || pageLeft + w < 0) continue
            val keyW = (w - pad * 2) / keys.size
            for ((i, k) in keys.withIndex()) {
                val left = pageLeft + pad + i * keyW
                r.set(left + gap / 2, keyTop, left + keyW - gap / 2, keyBottom)
                val down = k === pressed || k === holding || k.down || k in others.values
                keyPaint.color = when {
                    down -> Palette.ACCENT
                    k.latched -> Palette.KEY_ACCENT
                    k.sticky -> Palette.KEY_MOD
                    else -> Palette.KEY
                }
                canvas.drawRoundRect(r, radius, radius, keyPaint)
                textPaint.color = when {
                    down -> Palette.BG
                    k.latched -> Palette.FG_ON_ACCENT
                    else -> Palette.FG
                }
                // As large as fits: "PgDn" on a narrow key, "5" big.
                textPaint.textSize = 14 * density
                val fit = (r.width() - 6 * density) / textPaint.measureText(k.label)
                if (fit < 1f) textPaint.textSize *= fit
                canvas.drawText(k.label, r.centerX(), r.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            }
        }
        // Page dots; the current one a pill that slides with the pages.
        val n = pages.size
        val step = 12 * density
        val cy = height - dotsH / 2
        val startX = w / 2 - step * (n - 1) / 2
        keyPaint.color = Palette.FG_DIM
        for (i in 0 until n) canvas.drawCircle(startX + i * step, cy, 2 * density, keyPaint)
        val at = (offset / w).coerceIn(0f, (n - 1).toFloat())
        val x = startX + at * step
        keyPaint.color = Palette.ACCENT
        r.set(x - 5 * density, cy - 2 * density, x + 5 * density, cy + 2 * density)
        canvas.drawRoundRect(r, 2 * density, 2 * density, keyPaint)
    }

    private companion object {
        const val KEY_LEFTMETA = 125
        const val KEY_LEFTALT = 56
        const val KEY_LEFTCTRL = 29
        const val KEY_LEFTSHIFT = 42
        const val HOLD_MS = 350L
        const val MODIFIER_DELAY_MS = 100L
        const val TAP_MS = 300L
        const val FLICK_DP_PER_S = 350f
    }
}
