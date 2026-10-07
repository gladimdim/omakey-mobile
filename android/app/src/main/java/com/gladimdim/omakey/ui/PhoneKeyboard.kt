package com.gladimdim.omakey.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.gladimdim.omakey.keyboard.KeyLayout
import com.gladimdim.omakey.keyboard.KeyLayouts
import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.keyboard.UsKeys

/** Which layout the computer reads Omakey's keys with, and changing it. */
interface LayoutGate {
    /** The layout asked for now; null when none has been. */
    fun current(): String?

    /** Nothing typed is still unacknowledged, so a new layout can't reach keys typed before it. */
    fun canSwitch(): Boolean

    fun switch(layout: String)
}

/**
 * Key strokes for the computer, paced: the phone's keyboard can hand over a
 * whole word at once (a suggestion, an autocorrection), and omakeyd keeps
 * only so many unacknowledged events, a Bluetooth keyboard one report at a
 * time. One stroke goes out every [STROKE_MS] while [busy] says there's room.
 * A character goes with the layout that types it ([KeyLayouts]); a change
 * of layout waits until everything before it is acknowledged.
 */
class Typist(
    private val sink: KeyboardModel.Sink,
    private val gate: LayoutGate,
    /** The phone keyboard's layouts, its own language first. */
    private val preferred: () -> List<KeyLayout>,
    /** The character a stroke types, just before it goes (for the typed text strip). */
    private val onChar: (Char?) -> Unit = {},
    private val busy: () -> Boolean,
) {
    private class Stroke(val codes: IntArray, val layout: String?, val char: Char?)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Stroke>()
    private var pumping = false
    /** The layout of the last stroke queued, for picking the next one. */
    private var queuedLayout: String? = null

    /** Tap [code] in any layout, with Shift held around it when [shift]. */
    fun key(code: Int, shift: Boolean = false) = add(code, shift, null, null)

    /** Type [s]; characters no known layout has are skipped. */
    fun text(s: String) {
        val prefs = preferred()
        for (c in s) {
            val current = if (queue.isEmpty()) gate.current() else queuedLayout ?: gate.current()
            val (layout, key) = KeyLayouts.pick(c, current, prefs) ?: continue
            add(key.first, key.second, layout.xkb, c)
        }
    }

    private fun add(code: Int, shift: Boolean, layout: String?, char: Char?) {
        queue.addLast(Stroke(if (shift) intArrayOf(UsKeys.KEY_LEFTSHIFT, code) else intArrayOf(code), layout, char))
        if (layout != null) queuedLayout = layout
        pump()
    }

    /** Drop what hasn't gone out yet, e.g. when the screen goes away. */
    fun clear() {
        queue.clear()
        queuedLayout = null
    }

    private val tick = Runnable {
        pumping = false
        pump()
    }

    private fun pump() {
        if (pumping) return
        val stroke = queue.firstOrNull() ?: return
        val switching = stroke.layout != null && stroke.layout != gate.current()
        if (!busy() && (!switching || gate.canSwitch())) {
            if (switching) gate.switch(stroke.layout!!)
            queue.removeFirst()
            onChar(stroke.char)
            for (c in stroke.codes) sink.keyDown(c)
            for (i in stroke.codes.indices.reversed()) sink.keyUp(stroke.codes[i])
        }
        pumping = true
        main.postDelayed(tick, STROKE_MS)
    }

    private companion object {
        const val STROKE_MS = 8L
    }
}

/** The keys that turn one line into another, with the cursor where it was and where it ends. */
internal object LineDiff {
    /**
     * Replaces the changed middle of [old] (what's equal at both ends is
     * kept): moves to its end, deletes back over it, types the new middle,
     * then moves to [newCursor]. [key] gets arrows and Backspace, [text] the
     * characters.
     */
    fun edit(old: String, oldCursor: Int, new: String, newCursor: Int, key: (Int) -> Unit, text: (String) -> Unit) {
        var p = 0
        while (p < old.length && p < new.length && old[p] == new[p]) p++
        var s = 0
        while (s < old.length - p && s < new.length - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
        val oldEnd = old.length - s
        val newEnd = new.length - s
        var at = oldCursor
        fun moveTo(to: Int) {
            repeat(at - to) { key(UsKeys.KEY_LEFT) }
            repeat(to - at) { key(UsKeys.KEY_RIGHT) }
            at = to
        }
        if (oldEnd > p || newEnd > p) {
            moveTo(oldEnd)
            repeat(oldEnd - p) { key(UsKeys.KEY_BACKSPACE) }
            if (newEnd > p) text(new.substring(p, newEnd))
            at = newEnd
        }
        moveTo(newCursor)
    }
}

/**
 * An invisible editor for the phone's own keyboard (Gboard, SwiftKey, any).
 * Whatever the keyboard does to its text is mirrored on the computer: the
 * change between the text before and after (a typed letter, a composing
 * word growing, an autocorrection, a delete, the cursor moved with the
 * space bar) goes out as arrows, backspaces and characters. After Enter the
 * text starts empty again, so it stays one short line.
 */
class ImeCapture(
    context: Context,
    private val typist: Typist,
    /** Ctrl, Alt or Super is down on the computer: what's typed now is a shortcut, not text. */
    private val shortcut: () -> Boolean = { false },
) : View(context) {
    private val text: Editable = SpannableStringBuilder()
    /** The text and cursor as the computer has them. */
    private var sent = ""
    private var sentCursor = 0
    private var batch = 0
    private val imm = context.getSystemService(InputMethodManager::class.java)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(out: EditorInfo): InputConnection {
        out.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // No extract screen, no Done key, and nothing typed here is learned:
        // it may be a password for the computer.
        out.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        out.initialSelStart = Selection.getSelectionStart(text)
        out.initialSelEnd = Selection.getSelectionEnd(text)
        return Connection()
    }

    /** Bring the phone's keyboard up. */
    fun show() {
        requestFocus()
        imm.showSoftInput(this, 0)
    }

    /** Forget the line: a new computer, or the keyboard is back after a pause. */
    fun reset() {
        text.clear()
        Selection.setSelection(text, 0)
        sent = ""
        sentCursor = 0
        imm.restartInput(this)
    }

    private fun cursor() = Selection.getSelectionEnd(text).coerceAtLeast(0)

    /** Send the change from [sent] to [text]. */
    private fun sync() {
        if (batch > 0) return
        val now = text.toString()
        val cursor = cursor()
        if (now == sent && cursor == sentCursor) return
        LineDiff.edit(sent, sentCursor, now, cursor, { typist.key(it) }, typist::text)
        sent = now
        sentCursor = cursor
        // A shortcut (Ctrl from the touchpad + C) typed nothing on the computer: start the line again.
        if (shortcut()) post { if (sent == now) reset() }
        // A new line, or a long one: start empty (outside the keyboard's call).
        if ('\n' in now || now.length > MAX_LINE) post { if (sent == now) reset() }
    }

    private inner class Connection : BaseInputConnection(this@ImeCapture, true) {
        override fun getEditable(): Editable = text

        override fun beginBatchEdit(): Boolean {
            batch++
            return true
        }

        override fun endBatchEdit(): Boolean {
            if (batch > 0) batch--
            sync()
            return batch > 0
        }

        override fun commitText(t: CharSequence?, newCursorPosition: Int) =
            super.commitText(t, newCursorPosition).also { sync() }

        override fun setComposingText(t: CharSequence?, newCursorPosition: Int) =
            super.setComposingText(t, newCursorPosition).also { sync() }

        override fun setComposingRegion(start: Int, end: Int) =
            super.setComposingRegion(start, end).also { sync() }

        override fun finishComposingText() = super.finishComposingText().also { sync() }

        override fun setSelection(start: Int, end: Int) = super.setSelection(start, end).also { sync() }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // More than the line holds: the rest is on the computer, before
            // or after what was typed here.
            val start = Selection.getSelectionStart(text).coerceAtLeast(0)
            val end = Selection.getSelectionEnd(text).coerceAtLeast(0)
            val extraBefore = beforeLength - minOf(start, end)
            val extraAfter = afterLength - (text.length - maxOf(start, end))
            val r = super.deleteSurroundingText(beforeLength, afterLength)
            sync()
            repeat(extraBefore) { typist.key(UsKeys.KEY_BACKSPACE) }
            repeat(extraAfter) { typist.key(UsKeys.KEY_DELETE) }
            return r
        }

        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            deleteSurroundingText(beforeLength, afterLength)

        override fun performEditorAction(actionCode: Int): Boolean {
            typist.key(UsKeys.KEY_ENTER)
            post { reset() }
            return true
        }

        /** Keys the keyboard sends as key events instead of text. */
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val cursor = cursor()
            when (event.keyCode) {
                // On the line: edit it, and sync sends the change.
                KeyEvent.KEYCODE_DEL -> if (cursor > 0) {
                    text.delete(cursor - 1, cursor)
                    sync()
                } else typist.key(UsKeys.KEY_BACKSPACE)
                KeyEvent.KEYCODE_FORWARD_DEL -> if (cursor < text.length) {
                    text.delete(cursor, cursor + 1)
                    sync()
                } else typist.key(UsKeys.KEY_DELETE)
                KeyEvent.KEYCODE_DPAD_LEFT -> if (cursor > 0) {
                    Selection.setSelection(text, cursor - 1)
                    sync()
                } else typist.key(UsKeys.KEY_LEFT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (cursor < text.length) {
                    Selection.setSelection(text, cursor + 1)
                    sync()
                } else typist.key(UsKeys.KEY_RIGHT)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    typist.key(UsKeys.KEY_ENTER)
                    post { reset() }
                }
                KeyEvent.KEYCODE_DPAD_UP -> typist.key(UsKeys.KEY_UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> typist.key(UsKeys.KEY_DOWN)
                KeyEvent.KEYCODE_TAB -> typist.key(UsKeys.KEY_TAB)
                KeyEvent.KEYCODE_ESCAPE -> typist.key(UsKeys.KEY_ESC)
                else -> {
                    // A character key (hardware-style keyboards): type it on the line.
                    val c = event.unicodeChar
                    if (c != 0) commitText(c.toChar().toString(), 1)
                }
            }
            return true
        }
    }

    private companion object {
        const val MAX_LINE = 400
    }
}
