package com.gladimdim.omakey.keyboard

import com.gladimdim.omakey.layout.Layout

/**
 * Touch → key logic, free of Android so it can be unit-tested. Each pointer
 * holds at most one key, from touch-down to lift; fingers never slide onto
 * neighbours. The code a finger pressed is the code its lift releases, even
 * when the Fn layer changed in between.
 *
 * No allocation on [down]/[up]: the touch path runs on every finger event.
 */
class KeyboardModel(val layout: Layout, private val sink: Sink, private val locks: Locks = Locks()) {
    interface Sink {
        fun keyDown(code: Int)
        fun keyUp(code: Int)
    }

    /**
     * Lock state as this phone has sent it; it outlives a layout switch.
     * The desktop doesn't report its Caps Lock, so a Caps Lock toggled on
     * another keyboard isn't seen here.
     */
    class Locks {
        var capsLock = false
    }

    private val keys = layout.keys
    private val pointerKey = IntArray(MAX_POINTERS) { -1 }
    private val pointerCode = IntArray(MAX_POINTERS)
    private val layerStack = arrayOfNulls<String>(MAX_POINTERS)
    private var layerDepth = 0

    /** How many fingers are on each key, for the pressed highlight. */
    val pressCount = IntArray(keys.size)

    /** Fingers holding a Shift key right now. */
    private var shiftHeld = 0

    /** Whether labels show what Shift sends: a Shift key is held. */
    val shifted: Boolean get() = shiftHeld > 0

    val capsLock: Boolean get() = locks.capsLock

    /** For each key, whether it is a letter (its label is the letter its code types). */
    private val isLetter = BooleanArray(keys.size) { i ->
        val k = keys[i]
        k.label.length == 1 && k.label[0] in 'A'..'Z' && k.codeName == "KEY_${k.label}"
    }
    private val lowerLabel = Array(keys.size) { i -> if (isLetter[i]) keys[i].label.lowercase() else keys[i].label }

    /** The layer of the most recently pressed, still-held layer key. */
    val activeLayer: String? get() = if (layerDepth == 0) null else layerStack[layerDepth - 1]

    /**
     * Like [hitTest], for a split layout drawn with its gap widened by
     * [stretch] units: the right side is moved right and rectangles crossing
     * the split are stretched (see [com.gladimdim.omakey.layout.KeyRect.stretched]).
     * The rest of the gap hits nothing.
     */
    fun hitTestStretched(ux: Float, uy: Float, stretch: Float): Int {
        val split = layout.splitAt
        if (split == null || stretch <= 0f) return hitTest(ux, uy)
        for (i in keys.indices.reversed()) {
            if (keys[i].rects.any { it.stretched(split, stretch).contains(ux, uy) }) return i
        }
        return -1
    }

    /** Index of the key at layout-unit coordinates, or -1. Keys drawn later win. */
    fun hitTest(ux: Float, uy: Float): Int {
        for (i in keys.indices.reversed()) {
            if (keys[i].rects.any { it.contains(ux, uy) }) return i
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
     * The label a key shows: its layer label while a layer is held, otherwise
     * what it types. Letters are lowercase unless Shift or Caps Lock (but not
     * both) is on; with Shift held, symbol keys show their shifted character.
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

    /** Returns true when the view should redraw. */
    fun down(pointerId: Int, keyIndex: Int): Boolean {
        if (pointerId !in 0 until MAX_POINTERS || keyIndex < 0) return false
        if (pointerKey[pointerId] >= 0) up(pointerId)
        val k = keys[keyIndex]
        pointerKey[pointerId] = keyIndex
        pressCount[keyIndex]++
        if (k.layer != null) {
            layerStack[layerDepth++] = k.layer
            pointerCode[pointerId] = 0
        } else {
            val code = codeFor(keyIndex)
            pointerCode[pointerId] = code
            if (code == KEY_LEFTSHIFT || code == KEY_RIGHTSHIFT) shiftHeld++
            if (code == KEY_CAPSLOCK) locks.capsLock = !locks.capsLock
            if (code != 0) sink.keyDown(code)
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
        } else {
            val code = pointerCode[pointerId]
            pointerCode[pointerId] = 0
            if (code == KEY_LEFTSHIFT || code == KEY_RIGHTSHIFT) shiftHeld--
            if (code != 0) sink.keyUp(code)
        }
        return true
    }

    /** Lift every finger (ACTION_CANCEL, pause, layout switch). */
    fun cancelAll(): Boolean {
        var changed = false
        for (p in 0 until MAX_POINTERS) if (up(p)) changed = true
        return changed
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
    }
}
