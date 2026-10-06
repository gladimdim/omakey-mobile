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
        assertTrue(files.size >= 2)
        for (f in files) LayoutParser.parse(f.readText(), keycodes)
    }

    @Test
    fun splitQwertyHasTwoSpacesAndACentreThumbCluster() {
        val split = LayoutParser.parse(File(assets, "layouts/split-qwerty.json").readText(), keycodes)
        assertEquals(2, split.keys.count { it.code == 57 })
        val enter = split.keys.first { it.id == "center-enter" }
        val shift = split.keys.first { it.id == "center-shift" }
        val ctrl = split.keys.first { it.id == "center-ctrl" }
        // Modifiers and Enter exist only in the centre cluster.
        val modifiers = setOf(28, 29, 42, 54, 56, 97, 100, 125, 126)
        assertEquals(setOf("center-enter", "center-shift", "center-ctrl", "center-super", "center-alt", "center-altgr"),
            split.keys.filter { it.code in modifiers }.map { it.id }.toSet())
        // Enter, Shift and Ctrl are the biggest keys there.
        val centre = split.keys.filter { it.x >= shift.x && it.x < enter.x + enter.w }
        val big = setOf(enter, shift, ctrl)
        val smallestBig = big.minOf { it.w * it.h }
        assertTrue(centre.filter { it !in big }.all { it.w * it.h < smallestBig })
        // Letters are square.
        assertTrue(split.keys.filter { it.label.length == 1 && it.label[0].isLetter() }.all { it.w == 1f && it.h == 1f })
        // Letters touch the outer edges; T is left of the centre, Y right of it.
        fun k(id: String) = split.keys.first { it.id == id }
        for (id in listOf("q", "a", "z")) assertEquals(0f, k(id).x)
        for (id in listOf("p", "semicolon", "slash")) assertEquals(split.width, k(id).x + k(id).w, 1e-4f)
        assertTrue(k("t").x + k("t").w <= shift.x && k("y").x >= enter.x + enter.w)
        // Tab and Caps are in the centre too.
        for (id in listOf("tab", "capslock")) assertTrue(k(id).x >= shift.x && k(id).x < enter.x + enter.w)
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
