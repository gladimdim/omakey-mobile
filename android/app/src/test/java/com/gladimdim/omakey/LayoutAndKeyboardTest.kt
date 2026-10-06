package com.gladimdim.omakey

import com.gladimdim.omakey.keyboard.KeyboardModel
import com.gladimdim.omakey.layout.KeyStyle
import com.gladimdim.omakey.layout.Keycodes
import com.gladimdim.omakey.layout.Layout
import com.gladimdim.omakey.layout.LayoutException
import com.gladimdim.omakey.layout.LayoutLink
import com.gladimdim.omakey.layout.LayoutParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class LayoutAndKeyboardTest {
    // Unit tests run with the module directory as the working directory.
    private val assets = File("src/main/assets")
    private val keycodes = Keycodes.parse(File(assets, "keycodes.json").readText())
    private val qwertyJson = File(assets, "layouts/classic-qwerty.json").readText()
    private val qwerty: Layout = LayoutParser.parse(qwertyJson, keycodes)

    private fun key(id: String) = qwerty.keys.indexOfFirst { it.id == id }.also { assertTrue("no key $id", it >= 0) }

    @Test
    fun everyBundledLayoutParses() {
        val files = File(assets, "layouts").listFiles()!!.filter { it.name.endsWith(".json") }
        assertTrue(files.size >= 10)
        for (f in files) LayoutParser.parse(f.readText(), keycodes)
    }

    @Test
    fun splitQwertyHasTwoSpacesAndACentreThumbCluster() {
        val split = LayoutParser.parse(File(assets, "layouts/omakey-pro.json").readText(), keycodes)
        fun k(id: String) = split.keys.first { it.id == id }
        assertEquals(2, split.keys.count { it.code == 57 })
        // Modifiers and Enter exist only in the islands.
        val modifiers = setOf(28, 29, 42, 54, 56, 97, 100, 125, 126)
        assertEquals("Omakey Pro", split.name)
        assertEquals(setOf("center-enter", "center-shift", "center-rshift", "center-ctrl", "center-rctrl", "center-super",
            "center-alt", "center-rsuper", "center-ralt", "rightalt"),
            split.keys.filter { it.code in modifiers }.map { it.id }.toSet())
        // Mirrored islands: 2x2 Ctrl and Backspace on both, Shift twins.
        val lc = k("center-ctrl"); val rc = k("center-rctrl")
        assertEquals(2f, lc.w); assertEquals(2f, rc.w); assertEquals(lc.h, rc.h); assertEquals(lc.y, rc.y)
        val lb = k("center-backspace"); val rb = k("center-backspace-right")
        assertEquals(14, lb.code); assertEquals(14, rb.code); assertEquals(lb.y, rb.y); assertEquals(lb.h, rb.h)
        val ls = k("center-shift"); val rs = k("center-rshift")
        assertEquals(ls.y, rs.y); assertEquals(ls.w * ls.h, rs.w * rs.h)
        // Super and Alt mirror each other: outer Super, inner Alt on both islands.
        val lsu = k("center-super"); val la = k("center-alt"); val ra = k("center-ralt"); val rsu = k("center-rsuper")
        assertEquals(lsu.y, rsu.y); assertEquals(la.y, ra.y)
        assertTrue(lsu.x < la.x && ra.x < rsu.x)
        assertEquals(56, ra.code) // sends Left Alt: Alt even where Right Alt is AltGr
        // Delete sits between the two Backspaces and bridges the split.
        val del = k("center-delete")
        assertEquals(lb.x + lb.w, del.x, 1e-4f); assertEquals(del.x + del.w, rb.x, 1e-4f)
        assertEquals(lb.y, del.y); assertEquals(lb.h, del.h)
        assertTrue(del.x < split.splitAt!! && del.x + del.w > split.splitAt!!)
        // The one asymmetric key: a regular Backspace in the top right corner.
        val corner = k("corner-backspace")
        assertEquals(14, corner.code); assertEquals(1f, corner.w)
        assertEquals(split.width, corner.x + corner.w, 1e-4f); assertEquals(0f, corner.y)
        assertTrue(k("rightalt").y + k("rightalt").h < k("f1").y)
        // Rarely used keys sit in a top row above the F row, which is set apart from the number row.
        val f1 = k("f1")
        for (id in listOf("home", "end", "pageup", "pagedown", "capslock", "esc", "f6", "f7", "sysrq", "insert", "compose",
                "minus", "leftbrace", "rightbrace", "backslash", "apostrophe"))
            assertTrue(id, k(id).y + k(id).h < f1.y)
        assertTrue(k("1").y > f1.y + f1.h + 0.1f)
        for (id in listOf("home", "end", "pageup", "pagedown")) assertTrue(id, k(id).x + k(id).w <= k("t").x + 1)
        // Enter is |_|: a bar across the split plus a 2x2 block on each side, joined at the bar.
        val enter = k("center-enter")
        val at = split.splitAt!!
        assertEquals(3, enter.rects.size)
        assertTrue(enter.x < at && enter.x + enter.w > at)
        val (left, right) = enter.parts
        assertTrue(left.x + left.w <= at && right.x >= at)
        for (p in enter.parts) {
            assertEquals(2f, p.w); assertEquals(enter.y, p.y + p.h, 1e-4f)
        }
        assertEquals(left.x, enter.x); assertEquals(right.x + right.w, enter.x + enter.w, 1e-4f)
        // Letters touch the outer edges; T is left of the islands, Y right of them.
        for (id in listOf("q", "a", "z")) assertEquals(0f, k(id).x)
        for (id in listOf("p", "semicolon", "slash")) assertEquals(split.width, k(id).x + k(id).w, 1e-4f)
        assertTrue(k("t").x + k("t").w <= lc.x && k("y").x >= rc.x + rc.w)
        for (id in listOf("tab", "grave", "equal")) assertTrue(id, k(id).x >= lc.x && k(id).x < at)
    }

    @Test
    fun splitLayoutsStretchTheirGapAndHitTestAcrossIt() {
        val split = LayoutParser.parse(File(assets, "layouts/omakey-pro.json").readText(), keycodes)
        val at = split.splitAt!!
        assertTrue(at > 0 && at < split.width)
        // Only Enter's bar and Delete cross the split (they stretch with the gap).
        assertEquals(setOf("center-enter", "center-delete"), split.keys.filter { it.x < at && it.x + it.w > at + 1e-4f }.map { it.id }.toSet())
        val m = KeyboardModel(split, object : KeyboardModel.Sink {
            override fun keyDown(code: Int) {}
            override fun keyUp(code: Int) {}
        })
        val enter = split.keys.indexOfFirst { it.id == "center-enter" }
        val shift = split.keys.indexOfFirst { it.id == "center-shift" }
        val bksp = split.keys.indexOfFirst { it.id == "center-rshift" }
        val e = split.keys[enter]
        val sh = split.keys[shift]
        val bk = split.keys[bksp]
        val stretch = 3f
        // The right side moved 3 units right; the left side stayed.
        assertEquals(bksp, m.hitTestStretched(bk.x + stretch + 0.5f, bk.y + 0.5f, stretch))
        assertEquals(shift, m.hitTestStretched(sh.x + 0.5f, sh.y + 0.5f, stretch))
        // Enter's bar crosses the split, so it stretches: the middle of the widened gap is Enter...
        assertEquals(enter, m.hitTestStretched(at + stretch / 2, e.y + 0.5f, stretch))
        // ...and so are both of its 2x2 blocks, the right one moved with the right side.
        assertEquals(enter, m.hitTestStretched(e.parts[0].x + 0.5f, e.parts[0].y + 0.5f, stretch))
        assertEquals(enter, m.hitTestStretched(e.parts[1].x + stretch + 0.5f, e.parts[1].y + 0.5f, stretch))
        // Above the bar the gap hits nothing; no stretch behaves like hitTest.
        assertEquals(-1, m.hitTestStretched(at + stretch / 2, e.parts[0].y + 0.5f, stretch))
        assertEquals(bksp, m.hitTestStretched(bk.x + 0.5f, bk.y + 0.5f, 0f))
        // Every bundled split board declares its split.
        for (id in listOf("corne", "ferris-sweep", "lily58", "ergodox", "kinesis-advantage", "alice", "omakey-pro")) {
            assertTrue(id, LayoutParser.parse(File(assets, "layouts/$id.json").readText(), keycodes).splitAt != null)
        }
    }

    @Test
    fun labelsFollowShiftAndCapsLock() {
        val m = KeyboardModel(qwerty, object : KeyboardModel.Sink {
            override fun keyDown(code: Int) {}
            override fun keyUp(code: Int) {}
        })
        val a = key("a"); val one = key("1"); val minus = key("minus"); val shift = key("leftshift")
        val caps = key("capslock")
        // Lowercase by default; symbols show their own character with the shifted one in the corner.
        assertEquals("a", m.labelFor(a)); assertEquals("1", m.labelFor(one)); assertEquals("!", m.subFor(one))
        // Holding Shift shows what will be sent.
        m.down(0, shift)
        assertEquals("A", m.labelFor(a)); assertEquals("!", m.labelFor(one)); assertEquals("_", m.labelFor(minus))
        assertEquals("1", m.subFor(one))
        assertEquals("Shift", m.labelFor(shift))
        m.up(0)
        assertEquals("a", m.labelFor(a))
        // Caps Lock uppercases letters only; Shift with Caps Lock gives lowercase letters.
        m.down(1, caps); m.up(1)
        assertTrue(m.capsLock)
        assertEquals("A", m.labelFor(a)); assertEquals("1", m.labelFor(one))
        m.down(2, shift)
        assertEquals("a", m.labelFor(a)); assertEquals("!", m.labelFor(one))
        m.up(2)
        m.down(3, caps); m.up(3)
        assertEquals("a", m.labelFor(a))
        // The Fn layer's labels win while it is held.
        m.down(4, key("fn"))
        assertEquals("Mute", m.labelFor(key("f1")))
    }

    @Test
    fun capsLockSurvivesALayoutSwitch() {
        val sink = object : KeyboardModel.Sink {
            override fun keyDown(code: Int) {}
            override fun keyUp(code: Int) {}
        }
        val locks = KeyboardModel.Locks()
        val m1 = KeyboardModel(qwerty, sink, locks)
        m1.down(0, key("capslock")); m1.up(0)
        val m2 = KeyboardModel(qwerty, sink, locks)
        assertEquals("A", m2.labelFor(key("a")))
    }

    @Test
    fun parsesClassicQwerty() {
        assertEquals("classic-qwerty", qwerty.id)
        assertEquals(15f, qwerty.width)
        assertEquals(6f, qwerty.height)
        assertEquals(79, qwerty.keys.size)

        val esc = qwerty.keys[key("esc")]
        assertEquals(1, esc.code)
        assertEquals(KeyStyle.MOD, esc.style)
        assertEquals(125, qwerty.keys[key("leftmeta")].code)
        assertEquals(57, qwerty.keys[key("space")].code)
        assertEquals("fn", qwerty.keys[key("fn")].layer)
        assertEquals(0, qwerty.keys[key("fn")].code)
        assertEquals(113, qwerty.keys[key("f1")].layers["fn"]!!.code) // KEY_MUTE
        assertEquals("!", qwerty.keys[key("1")].sub)

        // Every row is 15 units wide.
        for (y in 0..5) {
            val row = qwerty.keys.filter { it.y == y.toFloat() }
            assertEquals(15f, row.sumOf { it.w.toDouble() }.toFloat(), 1e-4f)
        }
    }

    private class Recorder : KeyboardModel.Sink {
        val log = mutableListOf<String>()
        override fun keyDown(code: Int) { log += "+$code" }
        override fun keyUp(code: Int) { log += "-$code" }
    }

    @Test
    fun fingersHoldIndependentKeys() {
        val r = Recorder()
        val m = KeyboardModel(qwerty, r)
        m.down(0, key("leftmeta"))
        m.down(1, key("space"))
        m.up(1)
        m.up(0)
        assertEquals(listOf("+125", "+57", "-57", "-125"), r.log)
    }

    @Test
    fun fnLayerReleasesTheCodeItPressed() {
        val r = Recorder()
        val m = KeyboardModel(qwerty, r)
        m.down(0, key("fn"))
        assertEquals("fn", m.activeLayer)
        assertEquals("PgUp", m.labelFor(key("up")))
        m.down(1, key("up"))   // Fn + ↑ = PgUp
        m.up(0)                 // let go of Fn first
        assertNull(m.activeLayer)
        m.up(1)                 // still releases PgUp, not ↑
        m.down(2, key("up"))   // plain ↑ now
        m.up(2)
        assertEquals(listOf("+104", "-104", "+103", "-103"), r.log)
    }

    @Test
    fun keysWithoutALayerEntryKeepTheirCode() {
        val r = Recorder()
        val m = KeyboardModel(qwerty, r)
        m.down(0, key("fn"))
        m.down(1, key("q"))
        m.cancelAll()
        assertEquals(listOf("+16", "-16"), r.log)
        assertTrue(m.pressCount.all { it == 0 })
    }

    @Test
    fun hitTestUsesUnitsAndLastKeyWins() {
        val m = KeyboardModel(qwerty, Recorder())
        assertEquals(key("esc"), m.hitTest(0.5f, 0.5f))
        assertEquals(key("space"), m.hitTest(7f, 5.5f))
        assertEquals(-1, m.hitTest(20f, 1f))
    }

    @Test
    fun layoutLinkRoundTrips() {
        val link = LayoutLink.encode(qwertyJson)
        assertTrue(link.startsWith("omakey://layout?d="))
        assertTrue("link should be compressed", link.length < qwertyJson.length)
        assertEquals(qwertyJson, LayoutLink.decode(link))
        try {
            LayoutLink.decode("omakey://layout?d=!!!")
            fail()
        } catch (e: LayoutException) {
        }
    }

    private fun broken(edit: (JSONObject) -> Unit): String {
        val o = JSONObject(qwertyJson)
        edit(o)
        return o.toString()
    }

    @Test
    fun rejectsInvalidLayouts() {
        val cases = mapOf(
            "format" to broken { it.put("format", "other") },
            "version" to broken { it.put("version", 2) },
            "unknown code" to broken { it.getJSONArray("keys").getJSONObject(0).put("code", "KEY_NOPE") },
            "both code and layer" to broken { it.getJSONArray("keys").getJSONObject(0).put("layer", "fn") },
            "dup id" to broken { it.getJSONArray("keys").getJSONObject(1).put("id", "esc") },
            "tiny key" to broken { it.getJSONArray("keys").getJSONObject(0).put("w", 0.1) },
            "long label" to broken { it.getJSONArray("keys").getJSONObject(0).put("label", "x".repeat(17)) },
            "bad style" to broken { it.getJSONArray("keys").getJSONObject(0).put("style", "neon") },
            "not json" to "{",
        )
        for ((name, json) in cases) {
            try {
                LayoutParser.parse(json, keycodes)
                fail("accepted: $name")
            } catch (e: LayoutException) {
                // expected
            }
        }
    }
}
