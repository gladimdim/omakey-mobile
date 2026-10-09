package com.gladimdim.omakey

import com.gladimdim.omakey.ui.StripSlots
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class StripSlotsTest {
    private fun row(vararg s: String?) = arrayOf(*s)

    @Test
    fun fromThePagesIntoAnEmptySlot() {
        val slots = row(null, null, null)
        StripSlots.drop(slots, "Esc", -1, 1)
        assertArrayEquals(row(null, "Esc", null), slots)
    }

    @Test
    fun fromThePagesReplacesTheKeyThere() {
        val slots = row("Tab", null, null)
        StripSlots.drop(slots, "Esc", -1, 0)
        assertArrayEquals(row("Esc", null, null), slots)
    }

    @Test
    fun fromThePagesWhenAlreadyInTheRowMovesIt() {
        val slots = row("Esc", "Tab", null)
        StripSlots.drop(slots, "Esc", -1, 1)
        assertArrayEquals(row("Tab", "Esc", null), slots)
        StripSlots.drop(slots, "Esc", -1, 2)
        assertArrayEquals(row("Tab", null, "Esc"), slots)
    }

    @Test
    fun alongTheRowSwaps() {
        val slots = row("Esc", "Tab", "Del")
        StripSlots.drop(slots, "Esc", 0, 2)
        assertArrayEquals(row("Del", "Tab", "Esc"), slots)
        StripSlots.drop(slots, "Tab", 1, 1)
        assertArrayEquals(row("Del", "Tab", "Esc"), slots)
    }

    @Test
    fun offTheRowClearsItsSlot() {
        val slots = row("Esc", "Tab", null)
        StripSlots.drop(slots, "Tab", 1, -1)
        assertArrayEquals(row("Esc", null, null), slots)
        // From the pages and dropped off the row: nothing changes.
        StripSlots.drop(slots, "Del", -1, -1)
        assertArrayEquals(row("Esc", null, null), slots)
    }
}
