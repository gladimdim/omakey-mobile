package com.gladimdim.omakey.keyboard

import com.gladimdim.omakey.layout.KeyRect
import com.gladimdim.omakey.layout.Layout

/**
 * Touch → key logic, free of Android so it can be unit-tested. Each pointer
 * holds at most one key, from touch-down to lift; fingers never slide onto
 * neighbours. The code a finger pressed is the code its lift releases, even
 * when the Fn layer changed in between.
 *
 * With [sticky] on, modifiers and layer keys can be tapped instead of held:
 * a tapped Shift/Ctrl/Alt/Super stays down for the next key, a second tap
 * locks it until a third, and a tapped layer key (Fn) applies to the next
 * key only. Held in a chord, they behave as usual.
 *
 * No allocation on [down]/[up]: the touch path runs on every finger event.
 */
class KeyboardModel(val layout: Layout, private val sink: Sink, private val locks: Locks = Locks()) {
    interface Sink {
        fun keyDown(code: Int)
        fun keyUp(code: Int)
    }

    /**
     * Lock state; it outlives a layout switch. Set from the computer's lock
     * lights when it reports them, otherwise as this phone has sent it.
     */
    class Locks {
        var capsLock = false
    }

    private val keys = layout.keys
    private val pointerKey = IntArray(MAX_POINTERS) { -1 }
    private val pointerCode = IntArray(MAX_POINTERS)
    /** Another key went down while this pointer's key was held: it's a chord, not a tap. */
    private val chorded = BooleanArray(MAX_POINTERS)
    /** The pointer is tapping a modifier that was already latched or locked. */
    private val wasLatched = BooleanArray(MAX_POINTERS)
    private val layerStack = arrayOfNulls<String>(MAX_POINTERS)
    private var layerDepth = 0

    /** How many fingers are on each key, for the pressed highlight. */
    val pressCount = IntArray(keys.size)

    /** Fingers holding a Shift key right now. */
    private var shiftHeld = 0

    var sticky = false
        set(value) {
            if (!value) releaseLatched(all = true)
            field = value
        }

    /** Latched modifiers by code: [LATCHED] (for the next key) or [LOCKED]. */
    private val latch = IntArray(LATCH_CODES)

    /** A tapped layer key: the next key uses this layer. */
    private var oneShotLayer: String? = null

    /** Whether labels show what Shift sends: a Shift key is held or latched. */
    val shifted: Boolean get() = shiftHeld > 0 || latch[KEY_LEFTSHIFT] != 0 || latch[KEY_RIGHTSHIFT] != 0

    var capsLock: Boolean
        get() = locks.capsLock
        set(value) { locks.capsLock = value }

    /** For each key, whether it is a letter (its label is the letter its code types). */
    private val isLetter = BooleanArray(keys.size) { i ->
        val k = keys[i]
        k.label.length == 1 && k.label[0] in 'A'..'Z' && k.codeName == "KEY_${k.label}"
    }
    private val lowerLabel = Array(keys.size) { i -> if (isLetter[i]) keys[i].label.lowercase() else keys[i].label }

    /** The layer of the most recently pressed, still-held layer key, else a one-shot layer. */
    val activeLayer: String? get() = if (layerDepth == 0) oneShotLayer else layerStack[layerDepth - 1]

    // Rectangles for the last stretch [hitTestStretched] saw, so touches don't allocate.
    private var stretchedFor = Float.NaN
    private var stretchedRects: Array<Array<KeyRect>> = emptyArray()

    /**
     * Like [hitTest], for a split layout drawn with its gap widened by
     * [stretch] units: the right side is moved right and rectangles crossing
     * the split are stretched (see [KeyRect.stretched]). The rest of the gap
     * hits nothing.
     */
    fun hitTestStretched(ux: Float, uy: Float, stretch: Float): Int {
        val split = layout.splitAt
        if (split == null || stretch <= 0f) return hitTest(ux, uy)
        if (stretch != stretchedFor) {
            stretchedRects = Array(keys.size) { i -> keys[i].rects.map { it.stretched(split, stretch) }.toTypedArray() }
            stretchedFor = stretch
        }
        for (i in keys.indices.reversed()) {
            for (r in stretchedRects[i]) if (r.contains(ux, uy)) return i
        }
        return -1
    }

    /** Index of the key at layout-unit coordinates, or -1. Keys drawn later win. */
    fun hitTest(ux: Float, uy: Float): Int {
        for (i in keys.indices.reversed()) {
            for (r in keys[i].rects) if (r.contains(ux, uy)) return i
        }
        return -1
    }

    /** The code a key sends on the current layer; 0 when it sends nothing. */
    fun codeFor(index: Int): Int {
        val k = keys[index]
        if (k.layer != null) return 0
        val layer = activeLayer ?: return k.code
        val o = k.layers[layer] ?: return k.code
        return o.code
    }

    /**
     * The label a key shows: its layer label while a layer is on, otherwise
     * what it types. Letters are lowercase unless Shift or Caps Lock (but not
     * both) is on; with Shift on, symbol keys show their shifted character.
     */
    fun labelFor(index: Int): String {
        val k = keys[index]
        val layer = activeLayer
        if (layer != null) k.layers[layer]?.label?.let { return it }
        if (isLetter[index]) return if (shifted != capsLock) k.label else lowerLabel[index]
        if (shifted && k.sub != null) return k.sub
        return k.label
    }

    /** The small corner legend: the shifted character, or the plain one while Shift shows the shifted. */
    fun subFor(index: Int): String? {
        val k = keys[index]
        val sub = k.sub ?: return null
        return if (shifted) k.label else sub
    }

    /** Whether the key is a latched/locked modifier or the one-shot layer key, for the highlight. */
    fun isLatched(index: Int): Boolean {
        val k = keys[index]
        if (k.layer != null) return k.layer == oneShotLayer
        return k.code in 0 until LATCH_CODES && latch[k.code] != 0
    }

    fun isLocked(index: Int): Boolean {
        val c = keys[index].code
        return keys[index].layer == null && c in 0 until LATCH_CODES && latch[c] == LOCKED
    }

    /**
     * Whether [pointerId]'s key, on its own, only typed a character: [typing]
     * says so of its code, no other finger is down, and no modifier is
     * latched or layer on. Then one Backspace undoes it, and the touch can
     * turn into a swipe.
     */
    fun typedOnly(pointerId: Int, typing: (Int) -> Boolean): Boolean {
        if (pointerId !in 0 until MAX_POINTERS || pointerKey[pointerId] < 0) return false
        if (layerDepth > 0 || oneShotLayer != null || latch.any { it != 0 }) return false
        if ((0 until MAX_POINTERS).any { it != pointerId && pointerKey[it] >= 0 }) return false
        return typing(pointerCode[pointerId])
    }

    /** Lift [pointerId]'s key without it counting as a tap: a swipe took the finger. */
    fun cancel(pointerId: Int): Boolean {
        if (pointerId !in 0 until MAX_POINTERS) return false
        chorded[pointerId] = true
        return up(pointerId)
    }

    /** Returns true when the view should redraw. */
    fun down(pointerId: Int, keyIndex: Int): Boolean {
        if (pointerId !in 0 until MAX_POINTERS || keyIndex < 0) return false
        if (pointerKey[pointerId] >= 0) up(pointerId)
        val k = keys[keyIndex]
        pointerKey[pointerId] = keyIndex
        pressCount[keyIndex]++
        chorded[pointerId] = false
        wasLatched[pointerId] = false
        if (k.layer != null) {
            layerStack[layerDepth++] = k.layer
            pointerCode[pointerId] = 0
            return true
        }
        val code = codeFor(keyIndex)
        pointerCode[pointerId] = code
        if (!isModifier(code)) {
            // A chord: the modifiers and layer keys held now aren't taps.
            for (p in 0 until MAX_POINTERS) if (p != pointerId && pointerKey[p] >= 0) chorded[p] = true
            // A one-shot layer applies to this one key.
            if (layerDepth == 0) oneShotLayer = null
        }
        if (code == KEY_LEFTSHIFT || code == KEY_RIGHTSHIFT) shiftHeld++
        if (code == KEY_CAPSLOCK) locks.capsLock = !locks.capsLock
        if (sticky && isModifier(code) && latch[code] != 0) {
            wasLatched[pointerId] = true // already down on the computer
        } else if (code != 0) {
            sink.keyDown(code)
        }
        return true
    }

    fun up(pointerId: Int): Boolean {
        if (pointerId !in 0 until MAX_POINTERS) return false
        val keyIndex = pointerKey[pointerId]
        if (keyIndex < 0) return false
        pointerKey[pointerId] = -1
        pressCount[keyIndex]--
        val layer = keys[keyIndex].layer
        if (layer != null) {
            removeLayer(layer)
            if (sticky && !chorded[pointerId]) oneShotLayer = if (oneShotLayer == layer) null else layer
            return true
        }
        val code = pointerCode[pointerId]
        pointerCode[pointerId] = 0
        if (code == KEY_LEFTSHIFT || code == KEY_RIGHTSHIFT) shiftHeld--
        when {
            sticky && isModifier(code) && wasLatched[pointerId] -> when {
                // Already let go by the key it modified.
                latch[code] == 0 -> {}
                // Tapping a latched modifier locks it; tapping a locked one lets go.
                latch[code] == LATCHED && !chorded[pointerId] -> latch[code] = LOCKED
                else -> {
                    latch[code] = 0
                    sink.keyUp(code)
                }
            }
            sticky && isModifier(code) && !chorded[pointerId] -> latch[code] = LATCHED // stays down
            else -> {
                if (code != 0) sink.keyUp(code)
                if (code != 0 && !isModifier(code)) releaseLatched(all = false)
            }
        }
        return true
    }

    /** Lift every finger and let go of latched keys (ACTION_CANCEL, pause, layout switch). */
    fun cancelAll(): Boolean {
        var changed = false
        for (p in 0 until MAX_POINTERS) {
            if (pointerKey[p] >= 0) chorded[p] = true // a cancel is never a tap
            if (up(p)) changed = true
        }
        if (releaseLatched(all = true)) changed = true
        if (oneShotLayer != null) {
            oneShotLayer = null
            changed = true
        }
        return changed
    }

    /** Release latched modifiers; locked ones too when [all]. */
    private fun releaseLatched(all: Boolean): Boolean {
        var any = false
        for (c in MODIFIERS) {
            if (latch[c] == LATCHED || (all && latch[c] == LOCKED)) {
                latch[c] = 0
                sink.keyUp(c)
                any = true
            }
        }
        return any
    }

    private fun removeLayer(layer: String) {
        for (i in layerDepth - 1 downTo 0) {
            if (layerStack[i] == layer) {
                System.arraycopy(layerStack, i + 1, layerStack, i, layerDepth - i - 1)
                layerStack[--layerDepth] = null
                return
            }
        }
    }

    companion object {
        const val MAX_POINTERS = 32
        private const val KEY_LEFTSHIFT = 42
        private const val KEY_RIGHTSHIFT = 54
        private const val KEY_CAPSLOCK = 58
        private const val LATCHED = 1
        private const val LOCKED = 2
        /** Ctrl, Shift, Alt and Super, left and right. */
        private val MODIFIERS = intArrayOf(29, 42, 56, 125, 97, 54, 100, 126)
        private const val LATCH_CODES = 128

        fun isModifier(code: Int) = code in MODIFIERS
    }
}
