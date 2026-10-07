package com.gladimdim.omakey

import com.gladimdim.omakey.keyboard.KeyLayouts
import com.gladimdim.omakey.keyboard.UsKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyLayoutsTest {
    private val ukFirst = KeyLayouts.preferred("uk-UA")
    private val enFirst = KeyLayouts.preferred("en-US")

    @Test
    fun ukrainianLettersGoInUaOnTheirKeys() {
        val (layout, key) = KeyLayouts.pick('й', null, enFirst)!!
        assertEquals("ua", layout.xkb)
        assertEquals(16 to false, key) // the Q key
        assertEquals(43 to true, KeyLayouts.pick('Ґ', "ua", ukFirst)!!.second)
    }

    @Test
    fun latinGoesInUs() {
        val (layout, key) = KeyLayouts.pick('q', "ua", ukFirst)!!
        assertEquals("us", layout.xkb)
        assertEquals(16 to false, key)
    }

    @Test
    fun punctuationStaysInTheCurrentLayout() {
        // "привіт, світ": the comma is Shift + the dot key on ua, no switch.
        assertEquals("ua" to (53 to true), KeyLayouts.pick(',', "ua", ukFirst)!!.let { it.first.xkb to it.second })
        assertEquals("us" to (51 to false), KeyLayouts.pick(',', "us", ukFirst)!!.let { it.first.xkb to it.second })
        // Nothing asked yet: the phone keyboard's language decides.
        assertEquals("ua", KeyLayouts.pick('1', null, ukFirst)!!.first.xkb)
        assertEquals("us", KeyLayouts.pick('1', null, enFirst)!!.first.xkb)
        assertEquals(UsKeys.KEY_SPACE to false, KeyLayouts.pick(' ', "ua", ukFirst)!!.second)
    }

    @Test
    fun unknownCharactersAreSkipped() {
        assertNull(KeyLayouts.pick('€', "us", enFirst))
        assertNull(KeyLayouts.pick('ы', "ua", ukFirst))
    }
}
