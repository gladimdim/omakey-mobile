package com.gladimdim.omakey.layout

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Linux key names ↔ codes and default labels, from the studio's keycodes.json. */
class Keycodes(private val byName: Map<String, Int>, private val labels: Map<String, String> = emptyMap()) {
    fun code(name: String): Int? = byName[name]

    /** The label a key with this code shows by default: keycodes.json's, else the name without `KEY_`. */
    fun label(name: String): String = labels[name] ?: name.removePrefix("KEY_")

    companion object {
        fun parse(json: String): Keycodes {
            val keys = JSONObject(json).getJSONArray("keys")
            val map = HashMap<String, Int>(keys.length() * 2)
            val labels = HashMap<String, String>(keys.length() * 2)
            for (i in 0 until keys.length()) {
                val k = keys.getJSONObject(i)
                val name = k.getString("name")
                map[name] = k.getInt("code")
                k.optString("label").takeIf { it.isNotEmpty() }?.let { labels[name] = it }
            }
            return Keycodes(map, labels)
        }

        /** Codes the daemon accepts (PROTOCOL.md, "Safety rules"). */
        fun allowed(code: Int) = code in 1..0xFF || code in 0x160..0x2BF
    }
}

enum class KeyStyle { NORMAL, MOD, FKEY, ACCENT, SPACE }

/**
 * A key's behaviour on one layer. [code] 0 means the key does nothing there
 * (and shows no label); otherwise [label] is the override's own label or its
 * code's default one (LAYOUT.md, `layers`).
 */
class LayerOverride(
    val code: Int,
    val codeName: String?,
    val label: String,
    /** The entry's own `label`, for the printed Fn legend; null when it relies on the default. */
    val ownLabel: String? = label,
)

/** A rectangle in layout units. */
class KeyRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun contains(ux: Float, uy: Float) = ux >= x && ux < x + w && uy >= y && uy < y + h

    /**
     * This rectangle with a split layout's gap widened by [stretch] units:
     * rectangles right of [splitAt] move right, ones crossing it get wider.
     */
    fun stretched(splitAt: Float?, stretch: Float): KeyRect {
        if (splitAt == null || stretch <= 0f) return this
        val left = if (x >= splitAt) x + stretch else x
        val right = if (x + w > splitAt) x + w + stretch else x + w
        return KeyRect(left, y, right - left, h)
    }
}

class LayoutKey(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val label: String,
    val sub: String?,
    /** Base key code, or 0 for a layer key. */
    val code: Int,
    val codeName: String?,
    /** Layer this key switches to while held, or null. */
    val layer: String?,
    val style: KeyStyle,
    val layers: Map<String, LayerOverride>,
    /** Extra rectangles of a shaped key (a U-shaped Enter, say); empty for most keys. */
    val parts: List<KeyRect> = emptyList(),
) {
    /** The main rectangle (where the label goes) first, then the parts. */
    val rects: List<KeyRect> = listOf(KeyRect(x, y, w, h)) + parts
}

class Layout(
    val id: String,
    val name: String,
    val author: String?,
    val description: String?,
    val width: Float,
    val height: Float,
    val keys: List<LayoutKey>,
    /** The original JSON, so imports can be saved as-is. */
    val source: String,
    /**
     * Split point in units, or null. Keys with x >= splitAt are the right
     * side; the view pins each side to its screen edge.
     */
    val splitAt: Float? = null,
)

class LayoutException(message: String) : Exception(message)

/** Parses and validates a layout file against LAYOUT.md. */
object LayoutParser {
    const val MAX_BYTES = 256 * 1024
    private const val MAX_KEYS = 256
    private const val MAX_LABEL = 16
    private const val MAX_PARTS = 8
    /** Deeper JSON is refused before parsing: org.json recurses, and a tiny link could overflow the stack. */
    private const val MAX_DEPTH = 32
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val LAYER = Regex("^[a-z][a-z0-9]{0,15}$")

    fun parse(json: String, keycodes: Keycodes): Layout {
        if (json.length > MAX_BYTES) throw LayoutException("Layout is larger than 256 KB")
        if (nesting(json) > MAX_DEPTH) throw LayoutException("Layout is nested too deeply")
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw LayoutException("Not valid JSON: ${e.message}")
        } catch (e: StackOverflowError) {
            throw LayoutException("Layout is nested too deeply")
        }
        try {
            if (root.opt("format") != "omakey-layout") throw LayoutException("Not an Omakey layout file")
            val version = root.opt("version")
            if (!(version is Number && version.toDouble() == 1.0)) {
                throw LayoutException("Layout version $version is not supported; update the app")
            }
            val id = root.str("id")
            if (!ID.matches(id)) throw LayoutException("Bad layout id \"$id\"")
            val name = root.str("name").also {
                if (it.isEmpty() || it.codePointCount(0, it.length) > 64) throw LayoutException("Layout name must be 1-64 characters")
            }
            val width = root.num("width")
            val height = root.num("height")
            if (!(width > 0 && width <= 64 && height > 0 && height <= 32)) throw LayoutException("Bad layout size")
            val splitAt = if (root.has("splitAt")) root.num("splitAt") else null
            if (splitAt != null && !(splitAt > 0 && splitAt < width)) throw LayoutException("splitAt must be inside the layout")

            val arr: JSONArray = root.getJSONArray("keys")
            if (arr.length() == 0) throw LayoutException("Layout has no keys")
            if (arr.length() > MAX_KEYS) throw LayoutException("Layout has more than $MAX_KEYS keys")

            val ids = HashSet<String>()
            val keys = List(arr.length()) { i -> parseKey(arr.getJSONObject(i), keycodes, ids) }
            return Layout(
                id, name, root.optStr("author"), root.optStr("description"),
                width, height, keys, json, splitAt,
            )
        } catch (e: JSONException) {
            throw LayoutException("Layout is missing a field: ${e.message}")
        }
    }

    private fun parseKey(o: JSONObject, keycodes: Keycodes, ids: MutableSet<String>): LayoutKey {
        val id = o.str("id")
        if (id.isEmpty() || id.length > 64 || !ids.add(id)) throw LayoutException("Duplicate or bad key id \"$id\"")
        val x = o.num("x")
        val y = o.num("y")
        val w = o.num("w")
        val h = o.num("h")
        if (x < 0 || y < 0) throw LayoutException("Key $id has a negative position")
        if (w !in 0.25f..16f || h !in 0.25f..16f) throw LayoutException("Key $id size must be 0.25-16 units")

        val label = label(o.str("label"), id)
        val sub = o.optStr("sub")?.let { label(it, id) }
        val codeName = o.optStr("code")
        val layer = o.optStr("layer")
        if ((codeName == null) == (layer == null)) throw LayoutException("Key $id needs exactly one of code or layer")
        if (layer != null && !LAYER.matches(layer)) throw LayoutException("Key $id has a bad layer name")
        val code = codeName?.let { resolve(it, keycodes, id) } ?: 0

        val style = when (val s = o.optStr("style") ?: "normal") {
            "normal" -> KeyStyle.NORMAL
            "mod" -> KeyStyle.MOD
            "fkey" -> KeyStyle.FKEY
            "accent" -> KeyStyle.ACCENT
            "space" -> KeyStyle.SPACE
            else -> throw LayoutException("Key $id has unknown style \"$s\"")
        }

        val layers = HashMap<String, LayerOverride>()
        o.obj("layers")?.let { lo ->
            for (lname in lo.keys()) {
                if (!LAYER.matches(lname)) throw LayoutException("Key $id has a bad layer name")
                val entry = lo.obj(lname) ?: throw LayoutException("Key $id layer $lname must be an object")
                val n = entry.optStr("code")
                // LAYOUT.md, "Layers": its own label, else its code's default;
                // no code turns the key off there, blank unless labelled.
                val own = entry.optStr("label")?.let { label(it, id) }
                val shown = own ?: if (n == null) "" else keycodes.label(n)
                layers[lname] = LayerOverride(n?.let { resolve(it, keycodes, id) } ?: 0, n, shown, own)
            }
        }
        val parts = o.opt("parts")?.let { a ->
            val arr = a as? JSONArray ?: throw LayoutException("Key $id parts must be a list")
            if (arr.length() !in 1..MAX_PARTS) throw LayoutException("Key $id must have 1-$MAX_PARTS parts")
            List(arr.length()) { n ->
                val p = arr.opt(n) as? JSONObject ?: throw LayoutException("Key $id part ${n + 1} must be an object")
                val r = KeyRect(p.num("x"), p.num("y"), p.num("w"), p.num("h"))
                if (r.x < 0 || r.y < 0 || r.w !in 0.25f..16f || r.h !in 0.25f..16f) {
                    throw LayoutException("Key $id part ${n + 1} has a bad position or size")
                }
                r
            }
        } ?: emptyList()
        return LayoutKey(id, x, y, w, h, label, sub, code, codeName, layer, style, layers, parts)
    }

    private fun resolve(name: String, keycodes: Keycodes, id: String): Int {
        val code = keycodes.code(name) ?: throw LayoutException("Key $id uses unknown key code $name")
        if (!Keycodes.allowed(code)) throw LayoutException("Key $id uses $name, which can't be sent")
        return code
    }

    private fun label(s: String, id: String): String {
        if (s.codePointCount(0, s.length) > MAX_LABEL) throw LayoutException("Key $id label is over $MAX_LABEL characters")
        return s
    }

    // Typed reads: the schema's types, no coercion ("1" is not a number, 7 is not a label).

    private fun JSONObject.str(name: String): String =
        opt(name) as? String ?: throw LayoutException("\"$name\" must be text")

    private fun JSONObject.optStr(name: String): String? = when (val v = opt(name)) {
        null, JSONObject.NULL -> null
        is String -> v
        else -> throw LayoutException("\"$name\" must be text")
    }

    private fun JSONObject.num(name: String): Float {
        val v = opt(name) as? Number ?: throw LayoutException("\"$name\" must be a number")
        val f = v.toFloat()
        if (!f.isFinite()) throw LayoutException("\"$name\" must be a number")
        return f
    }

    private fun JSONObject.obj(name: String): JSONObject? = when (val v = opt(name)) {
        null, JSONObject.NULL -> null
        is JSONObject -> v
        else -> throw LayoutException("\"$name\" must be an object")
    }

    /** Deepest nesting of objects and arrays, ignoring brackets inside strings. */
    internal fun nesting(json: String): Int {
        var depth = 0
        var max = 0
        var inString = false
        var i = 0
        while (i < json.length) {
            val c = json[i]
            if (inString) {
                if (c == '\\') i++ else if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> { depth++; if (depth > max) max = depth }
                '}', ']' -> depth--
            }
            i++
        }
        return max
    }
}
