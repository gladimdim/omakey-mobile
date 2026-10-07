package com.gladimdim.omakey.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HidTest {
    private class Sent : (Int, ByteArray) -> Boolean {
        val reports = mutableListOf<Pair<Int, List<Int>>>()
        /** False: the next sends fail, as when the HID channel is congested. */
        var ok = true
        override fun invoke(id: Int, data: ByteArray): Boolean {
            reports += id to data.map { it.toInt() and 0xFF }
            return ok
        }
        fun take() = reports.toList().also { reports.clear() }
    }

    @Test
    fun linuxCodesMapToTheUsagesLinuxMapsBack() {
        assertEquals(0x04, Hid.keyboardUsage(30)) // A
        assertEquals(0x1E, Hid.keyboardUsage(2)) // 1
        assertEquals(0x28, Hid.keyboardUsage(28)) // ENTER
        assertEquals(0x2C, Hid.keyboardUsage(57)) // SPACE
        assertEquals(0x31, Hid.keyboardUsage(43)) // BACKSLASH, not the non-US 0x32
        assertEquals(0x4C, Hid.keyboardUsage(111)) // DELETE, not 0x9C
        assertEquals(0x46, Hid.keyboardUsage(99)) // SYSRQ / Print Screen
        assertEquals(0x45, Hid.keyboardUsage(88)) // F12
        assertEquals(0x65, Hid.keyboardUsage(127)) // COMPOSE / Menu
        assertEquals(3, Hid.modifierBit(125)) // LEFTMETA = Left GUI
        assertEquals(0xE9, Hid.consumerUsage(115)) // VOLUMEUP on the consumer page
        assertEquals(0, Hid.keyboardUsage(115)) // … and not on the keyboard page
        assertFalse(Hid.isSendable(248)) // MICMUTE: no HID usage here
        assertTrue(Hid.isSendable(Wire.BTN_LEFT))
    }

    @Test
    fun superSpaceIsModifierThenKey() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(), null, s)
        assertEquals(listOf(Hid.ID_KEYBOARD, Hid.ID_CONSUMER, Hid.ID_MOUSE), s.take().map { it.first })

        r.build(intArrayOf(125), null, s)
        assertEquals(listOf(Hid.ID_KEYBOARD to listOf(0x08, 0, 0, 0, 0, 0, 0, 0)), s.take())
        r.build(intArrayOf(57, 125), null, s)
        assertEquals(listOf(Hid.ID_KEYBOARD to listOf(0x08, 0, 0x2C, 0, 0, 0, 0, 0)), s.take())
        // Nothing changed: nothing sent.
        r.build(intArrayOf(57, 125), null, s)
        assertEquals(emptyList<Any>(), s.take())
    }

    @Test
    fun sevenKeysReportRollover() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(16, 17, 18, 19, 20, 21, 22, 42), null, s)
        assertEquals(Hid.ID_KEYBOARD to listOf(0x02, 0, 1, 1, 1, 1, 1, 1), s.take()[0])
    }

    @Test
    fun mediaKeysUseTheConsumerReport() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(), null, s)
        s.take()
        r.build(intArrayOf(164), null, s) // PLAYPAUSE
        assertEquals(listOf(Hid.ID_CONSUMER to listOf(0xCD, 0x00)), s.take())
        r.build(intArrayOf(172), null, s) // HOMEPAGE: AC Home 0x223
        assertEquals(listOf(Hid.ID_CONSUMER to listOf(0x23, 0x02)), s.take())
    }

    @Test
    fun mouseCarriesButtonsMotionAndWholeNotches() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(), null, s)
        s.take()
        r.build(intArrayOf(Wire.BTN_LEFT), Pointer(-2, 300, 60, 0), s)
        assertEquals(listOf(Hid.ID_MOUSE to listOf(0x01, 0xFE, 0xFF, 0x2C, 0x01, 0, 0)), s.take())
        // The half notch from before plus this one makes a notch up.
        r.build(intArrayOf(Wire.BTN_LEFT), Pointer(0, 0, 60, -120), s)
        assertEquals(listOf(Hid.ID_MOUSE to listOf(0x01, 0, 0, 0, 0, 1, 0xFF)), s.take())
        r.build(intArrayOf(), null, s)
        assertEquals(listOf(Hid.ID_MOUSE to listOf(0, 0, 0, 0, 0, 0, 0)), s.take())
    }

    @Test
    fun resetResendsEverything() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(30), null, s)
        s.take()
        r.reset()
        r.build(intArrayOf(30), null, s)
        assertEquals(3, s.take().size)
    }

    @Test
    fun descriptorCollectionsBalance() {
        val d = Hid.DESCRIPTOR.map { it.toInt() and 0xFF }
        // Walk short items: the prefix's low two bits give the data size.
        var depth = 0
        var i = 0
        while (i < d.size) {
            val prefix = d[i]
            val size = intArrayOf(0, 1, 2, 4)[prefix and 3]
            if (prefix and 0xFC == 0xA0) depth++
            if (prefix == 0xC0) depth--
            assertTrue(depth >= 0)
            i += 1 + size
        }
        assertEquals(d.size, i)
        assertEquals(0, depth)
        assertArrayEquals(byteArrayOf(0x05, 0x01, 0x09, 0x06), Hid.DESCRIPTOR.copyOf(4))
    }

    @Test
    fun aFailedReleaseIsSentAgain() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(30), null, s)
        s.take()
        s.ok = false
        assertTrue(r.build(intArrayOf(), null, s)) // the release doesn't go out
        assertTrue(r.behind)
        s.take()
        s.ok = true
        // Nothing changed since, but the host still has A down: send it again.
        assertFalse(r.build(intArrayOf(), null, s))
        assertEquals(listOf(Hid.ID_KEYBOARD to listOf(0, 0, 0, 0, 0, 0, 0, 0)), s.take())
        assertFalse(r.behind)
    }

    @Test
    fun repeatResendsTheCurrentKeysAndCurrentAnswersGetReport() {
        val r = Hid.Reports()
        val s = Sent()
        r.build(intArrayOf(42, 30), null, s)
        s.take()
        r.repeat(s)
        assertEquals(listOf(Hid.ID_KEYBOARD to listOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), Hid.ID_CONSUMER to listOf(0, 0)), s.take())
        assertArrayEquals(byteArrayOf(0x02, 0, 0x04, 0, 0, 0, 0, 0), r.current(Hid.ID_KEYBOARD))
        assertEquals(null, r.current(9))
    }
}
