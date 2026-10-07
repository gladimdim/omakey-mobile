package com.gladimdim.omakey

import com.gladimdim.omakey.keyboard.UsKeys
import com.gladimdim.omakey.ui.LineDiff
import org.junit.Assert.assertEquals
import org.junit.Test

class LineDiffTest {
    /** The edit as a readable script: ⌫ ← → for keys, the typed text as is. */
    private fun script(old: String, oldCursor: Int, new: String, newCursor: Int): String {
        val out = StringBuilder()
        LineDiff.edit(old, oldCursor, new, newCursor, { code ->
            out.append(
                when (code) {
                    UsKeys.KEY_BACKSPACE -> "⌫"
                    UsKeys.KEY_LEFT -> "←"
                    UsKeys.KEY_RIGHT -> "→"
                    else -> "?"
                },
            )
        }, { out.append(it) })
        return out.toString()
    }

    @Test
    fun typingAppends() {
        assertEquals("o", script("hell", 4, "hello", 5))
    }

    @Test
    fun composingWordGrowsOneLetterAtATime() {
        assertEquals("l", script("hel", 3, "hell", 4))
    }

    @Test
    fun autocorrectReplacesTheWord() {
        // Space after "teh": the keyboard commits "the " instead. Only the
        // differing part goes: delete "eh", type "he ".
        assertEquals("⌫⌫he ", script("teh", 3, "the ", 4))
        // A suggestion picked with the cursor already past the space.
        assertEquals("←⌫⌫he→", script("teh ", 4, "the ", 4))
    }

    @Test
    fun backspaceAtTheEnd() {
        assertEquals("⌫", script("hello", 5, "hell", 4))
    }

    @Test
    fun cursorMovesAreArrows() {
        assertEquals("←←", script("hello", 5, "hello", 3))
        assertEquals("→", script("hello", 3, "hello", 4))
    }

    @Test
    fun editInTheMiddleThenBackToTheEnd() {
        // Cursor after "he", a letter typed there; the cursor follows it.
        assertEquals("y", script("hello", 2, "heyllo", 3))
        assertEquals("→→→", script("heyllo", 3, "heyllo", 6))
    }

    @Test
    fun clearingTheLine() {
        assertEquals("⌫⌫⌫", script("abc", 3, "", 0))
    }
}
