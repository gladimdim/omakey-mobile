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
class KeyboardModel(val layout: Layout, private val sink: Sink) {
    interface Sink {
        fun keyDown(code: Int)
        fun keyUp(code: Int)
    }

    private val keys = layout.keys
    private val pointerKey = IntArray(MAX_POINTERS) { -1 }
    private val pointerCode = IntArray(MAX_POINTERS)
    private val layerStack = arrayOfNulls<String>(MAX_POINTERS)
    private var layerDepth = 0

    /** How many fingers are on each key, for the pressed highlight. */
    val pressCount = IntArray(keys.size)

    /** The layer of the most recently pressed, still-held layer key. */
    val activeLayer: String? get() = if (layerDepth == 0) null else layerStack[layerDepth - 1]

    /** Index of the key at layout-unit coordinates, or -1. Keys drawn later win. */
    fun hitTest(ux: Float, uy: Float): Int {
        for (i in keys.indices.reversed()) {
            val k = keys[i]
            if (ux >= k.x && ux < k.x + k.w && uy >= k.y && uy < k.y + k.h) return i
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

    /** The label a key shows on the current layer. */
    fun labelFor(index: Int): String {
        val k = keys[index]
        val layer = activeLayer ?: return k.label
        return k.layers[layer]?.label ?: k.label
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
    }
}
