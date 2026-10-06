package com.gladimdim.omakey.layout

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Linux key names ↔ codes, from the studio's keycodes.json. */
class Keycodes(private val byName: Map<String, Int>) {
    fun code(name: String): Int? = byName[name]

    companion object {
        fun parse(json: String): Keycodes {
            val keys = JSONObject(json).getJSONArray("keys")
            val map = HashMap<String, Int>(keys.length() * 2)
            for (i in 0 until keys.length()) {
                val k = keys.getJSONObject(i)
                map[k.getString("name")] = k.getInt("code")
            }
            return Keycodes(map)
        }

        /** Codes the daemon accepts (PROTOCOL.md, "Safety rules"). */
        fun allowed(code: Int) = code in 1..0xFF || code in 0x160..0x2BF
    }
}

enum class KeyStyle { NORMAL, MOD, FKEY, ACCENT, SPACE }

/** A key's behaviour on one layer. [code] 0 means the key does nothing there. */
class LayerOverride(val code: Int, val codeName: String?, val label: String?)

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
)

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
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,63}$")
    private val LAYER = Regex("^[a-z][a-z0-9]{0,15}$")

    fun parse(json: String, keycodes: Keycodes): Layout {
        if (json.length > MAX_BYTES) throw LayoutException("Layout is larger than 256 KB")
        val root = try {
            JSONObject(json)
        } catch (e: JSONException) {
            throw LayoutException("Not valid JSON: ${e.message}")
        }
        try {
            if (root.optString("format") != "omakey-layout") throw LayoutException("Not an Omakey layout file")
            if (root.optInt("version", -1) != 1) {
                throw LayoutException("Layout version ${root.opt("version")} is not supported; update the app")
            }
            val id = root.getString("id")
            if (!ID.matches(id)) throw LayoutException("Bad layout id \"$id\"")
            val name = root.getString("name").also {
                if (it.isEmpty() || it.length > 64) throw LayoutException("Layout name must be 1-64 characters")
            }
            val width = root.getDouble("width").toFloat()
            val height = root.getDouble("height").toFloat()
            if (!(width > 0 && width <= 64 && height > 0 && height <= 32)) throw LayoutException("Bad layout size")
            val splitAt = if (root.has("splitAt")) root.getDouble("splitAt").toFloat() else null
            if (splitAt != null && !(splitAt > 0 && splitAt < width)) throw LayoutException("splitAt must be inside the layout")

            val arr: JSONArray = root.getJSONArray("keys")
            if (arr.length() == 0) throw LayoutException("Layout has no keys")
            if (arr.length() > MAX_KEYS) throw LayoutException("Layout has more than $MAX_KEYS keys")

            val ids = HashSet<String>()
            val keys = List(arr.length()) { i -> parseKey(arr.getJSONObject(i), keycodes, ids) }
            return Layout(
                id, name, root.optStringOrNull("author"), root.optStringOrNull("description"),
                width, height, keys, json, splitAt,
            )
        } catch (e: JSONException) {
            throw LayoutException("Layout is missing a field: ${e.message}")
        }
    }

    private fun parseKey(o: JSONObject, keycodes: Keycodes, ids: MutableSet<String>): LayoutKey {
        val id = o.getString("id")
        if (id.isEmpty() || id.length > 64 || !ids.add(id)) throw LayoutException("Duplicate or bad key id \"$id\"")
        fun num(name: String) = o.getDouble(name).toFloat()
        val x = num("x")
        val y = num("y")
        val w = num("w")
        val h = num("h")
        if (x < 0 || y < 0) throw LayoutException("Key $id has a negative position")
        if (w !in 0.25f..16f || h !in 0.25f..16f) throw LayoutException("Key $id size must be 0.25-16 units")

        val label = label(o.getString("label"), id)
        val sub = o.optStringOrNull("sub")?.let { label(it, id) }
        val codeName = o.optStringOrNull("code")
        val layer = o.optStringOrNull("layer")
        if ((codeName == null) == (layer == null)) throw LayoutException("Key $id needs exactly one of code or layer")
        if (layer != null && !LAYER.matches(layer)) throw LayoutException("Key $id has a bad layer name")
        val code = codeName?.let { resolve(it, keycodes, id) } ?: 0

        val style = when (val s = o.optString("style", "normal")) {
            "normal" -> KeyStyle.NORMAL
            "mod" -> KeyStyle.MOD
            "fkey" -> KeyStyle.FKEY
            "accent" -> KeyStyle.ACCENT
            "space" -> KeyStyle.SPACE
            else -> throw LayoutException("Key $id has unknown style \"$s\"")
        }

        val layers = HashMap<String, LayerOverride>()
        o.optJSONObject("layers")?.let { lo ->
            for (lname in lo.keys()) {
                if (!LAYER.matches(lname)) throw LayoutException("Key $id has a bad layer name")
                val entry = lo.getJSONObject(lname)
                val n = entry.optStringOrNull("code")
                layers[lname] = LayerOverride(
                    n?.let { resolve(it, keycodes, id) } ?: 0, n,
                    entry.optStringOrNull("label")?.let { label(it, id) },
                )
            }
        }
        return LayoutKey(id, x, y, w, h, label, sub, code, codeName, layer, style, layers)
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

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null
}
