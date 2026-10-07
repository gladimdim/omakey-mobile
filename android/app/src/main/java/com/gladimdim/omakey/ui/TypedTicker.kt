package com.gladimdim.omakey.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import com.gladimdim.omakey.keyboard.UsKeys

/**
 * What was typed, running right to left above the keyboard: each key
 * appears at the right edge and drifts off to the left. Printable keys show
 * as the US layout would type them, others as a symbol (⏎ ⌫ ⇥ ← …), and
 * shortcuts as Ctrl+C, Alt+X, Super+Space. Typing faster than it runs speeds it up, so
 * the newest key is always on screen. Purely local: it reads key codes, not
 * what the computer did with them.
 */
class TypedTicker(context: Context) : View(context) {
    /** Base speed in dp per second. */
    var speedDp = 70f

    /** The computer's Caps Lock, as it reports it. */
    var capsLock = false

    /**
     * What the next printable key really types, when the phone knows better
     * than the US layout (a Ukrainian letter from the phone's keyboard).
     */
    var nextChar: Char? = null

    private val density = resources.displayMetrics.density

    private class Token(val text: String, val color: Int, val x: Float, val width: Float)

    private val tokens = ArrayDeque<Token>()
    /** Tape position of the view's right edge; the tape only ever moves forwards. */
    private var scroll = 0f
    /** Where the next token goes. */
    private var head = 0f
    private var lastFrame = 0L

    private val held = HashSet<Int>()

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textSize = 13 * density
    }
    private val fade = Paint()

    init {
        setBackgroundColor(Palette.BG)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun keyDown(code: Int) {
        if (code in MODIFIERS) {
            held += code
            return
        }
        if (code == KEY_CAPSLOCK) capsLock = !capsLock
        val token = describe(code) ?: return
        val (text, color) = token
        val w = paint.measureText(text)
        // Caught up with the text: start again at the right edge.
        val x = maxOf(head, scroll)
        tokens.addLast(Token(text, color, x, w))
        head = x + w + if (text.length > 1 || color != Palette.FG) GAP * density else 0f
        if (lastFrame == 0L) lastFrame = System.nanoTime()
        postInvalidateOnAnimation()
    }

    fun keyUp(code: Int) {
        held -= code
    }

    /** Clear the tape, e.g. when the keyboard goes away. */
    fun clear() {
        tokens.clear()
        held.clear()
        head = scroll
        invalidate()
    }

    private fun describe(code: Int): Pair<String, Int>? {
        val ctrl = KEY_LEFTCTRL in held || KEY_RIGHTCTRL in held
        val alt = KEY_LEFTALT in held || KEY_RIGHTALT in held
        val meta = KEY_LEFTMETA in held || KEY_RIGHTMETA in held
        val shift = KEY_LEFTSHIFT in held || KEY_RIGHTSHIFT in held
        val printable = UsKeys.PRINTABLE[code]
        // Meant for this key only, whatever it turns out to be.
        val really = nextChar
        nextChar = null
        if (ctrl || alt || meta) {
            val name = printable?.first?.uppercase()?.takeIf { code != KEY_SPACE } ?: SYMBOLS[code] ?: return null
            val mods = (if (meta) "Super+" else "") + (if (ctrl) "Ctrl+" else "") + (if (alt) "Alt+" else "") + (if (shift) "Shift+" else "")
            return mods + name to Palette.LAYER
        }
        if (really != null && really != '\n' && really != '\t') return really.toString() to Palette.FG
        if (printable != null) {
            val (plain, shifted) = printable
            val upper = if (plain in 'a'..'z') shift != capsLock else shift
            return (if (upper) shifted else plain).toString() to Palette.FG
        }
        return SYMBOLS[code]?.let { it to Palette.ACCENT }
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        // A backlog past the right edge runs faster, so typing never outpaces it.
        val backlog = (head - scroll).coerceAtLeast(0f)
        scroll += (speedDp * density + backlog * CATCH_UP) * dt
        val w = width.toFloat()
        val left = scroll - w
        while (tokens.isNotEmpty() && tokens.first().x + tokens.first().width < left) tokens.removeFirst()

        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2
        for (t in tokens) {
            val x = t.x - left
            if (x > w) break
            paint.color = t.color
            canvas.drawText(t.text, x, baseline, paint)
        }
        // Fade out towards the left edge.
        val fw = minOf(w * 0.15f, 48 * density)
        fade.shader = LinearGradient(0f, 0f, fw, 0f, Palette.BG, Palette.BG and 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, fw, height.toFloat(), fade)

        if (tokens.isNotEmpty()) postInvalidateOnAnimation() else lastFrame = 0L
    }

    private companion object {
        const val GAP = 4f
        /** Extra speed per px of backlog, per second. */
        const val CATCH_UP = 3f

        const val KEY_LEFTCTRL = 29
        const val KEY_RIGHTCTRL = 97
        const val KEY_LEFTSHIFT = 42
        const val KEY_RIGHTSHIFT = 54
        const val KEY_LEFTALT = 56
        const val KEY_RIGHTALT = 100
        const val KEY_LEFTMETA = 125
        const val KEY_RIGHTMETA = 126
        const val KEY_CAPSLOCK = 58
        const val KEY_SPACE = 57
        val MODIFIERS = setOf(KEY_LEFTCTRL, KEY_RIGHTCTRL, KEY_LEFTSHIFT, KEY_RIGHTSHIFT, KEY_LEFTALT, KEY_RIGHTALT, KEY_LEFTMETA, KEY_RIGHTMETA)

        val SYMBOLS: Map<Int, String> = buildMap {
            put(1, "⎋"); put(14, "⌫"); put(15, "⇥"); put(28, "⏎"); put(KEY_SPACE, "Space")
            put(103, "↑"); put(108, "↓"); put(105, "←"); put(106, "→")
            put(102, "Home"); put(107, "End"); put(104, "PgUp"); put(109, "PgDn"); put(110, "Ins"); put(111, "Del")
            for (i in 0 until 10) put(59 + i, "F${i + 1}")
            put(87, "F11"); put(88, "F12")
        }
    }
}
