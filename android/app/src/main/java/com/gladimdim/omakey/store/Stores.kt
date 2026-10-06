package com.gladimdim.omakey.store

import android.content.Context
import com.gladimdim.omakey.layout.Keycodes
import com.gladimdim.omakey.layout.Layout
import com.gladimdim.omakey.layout.LayoutException
import com.gladimdim.omakey.layout.LayoutParser
import com.gladimdim.omakey.protocol.Hex
import com.gladimdim.omakey.protocol.HostRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Paired computers, as JSON in app-private SharedPreferences. The keys are
 * only readable by this app; backups are disabled in the manifest so they
 * never leave the phone.
 */
class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    fun all(): List<HostRecord> {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        return List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
    }

    fun get(hostId: String): HostRecord? = all().firstOrNull { it.hostId == hostId }

    /** Adds or replaces the host with the same id (re-pairing). */
    fun put(host: HostRecord) = save(listOf(host) + all().filter { it.hostId != host.hostId })

    fun remove(hostId: String) = save(all().filter { it.hostId != hostId })

    /** Remember the address that answered, first in line for next time. */
    fun rememberAddress(hostId: String, address: String) {
        val list = all()
        save(list.map { h ->
            if (h.hostId == hostId) h.copy(addresses = (listOf(address) + h.addresses.filter { it != address }).take(6))
            else h
        })
    }

    private fun save(hosts: List<HostRecord>) {
        val arr = JSONArray()
        hosts.forEach { arr.put(toJson(it)) }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }

    private fun toJson(h: HostRecord) = JSONObject()
        .put("hostId", h.hostId)
        .put("name", h.name)
        .put("addresses", JSONArray(h.addresses))
        .put("port", h.port)
        .put("deviceId", Hex.encode(h.deviceId))
        .put("key", Base64.getEncoder().encodeToString(h.key))

    private fun fromJson(o: JSONObject): HostRecord {
        val a = o.getJSONArray("addresses")
        return HostRecord(
            o.getString("hostId"), o.getString("name"), List(a.length()) { a.getString(it) },
            o.getInt("port"), Hex.decode(o.getString("deviceId")), Base64.getDecoder().decode(o.getString("key")),
        )
    }
}

/** Built-in layouts from assets plus imported ones in files/layouts. */
class LayoutStore(private val context: Context) {
    private val dir = File(context.filesDir, "layouts").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("layouts", Context.MODE_PRIVATE)

    val keycodes: Keycodes by lazy {
        Keycodes.parse(context.assets.open("keycodes.json").bufferedReader().use { it.readText() })
    }

    class Entry(val layout: Layout, val builtIn: Boolean)

    fun all(): List<Entry> {
        val builtIn = (context.assets.list("layouts") ?: emptyArray()).sorted().mapNotNull { f ->
            parseOrNull(context.assets.open("layouts/$f").bufferedReader().use { it.readText() })?.let { Entry(it, true) }
        }
        val ids = builtIn.map { it.layout.id }.toSet()
        val imported = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            parseOrNull(f.readText())?.takeIf { it.id !in ids }?.let { Entry(it, false) }
        }
        return builtIn + imported
    }

    var selectedId: String
        get() = prefs.getString("selected", DEFAULT_ID) ?: DEFAULT_ID
        set(value) = prefs.edit().putString("selected", value).apply()

    fun selected(): Layout = all().let { list ->
        (list.firstOrNull { it.layout.id == selectedId } ?: list.first()).layout
    }

    /** Validates and saves an imported layout; it becomes the selected one. */
    fun import(json: String): Layout {
        val layout = LayoutParser.parse(json, keycodes)
        if (all().any { it.builtIn && it.layout.id == layout.id }) {
            throw LayoutException("\"${layout.id}\" is a built-in layout id; give your layout another id")
        }
        File(dir, "${layout.id}.json").writeText(json)
        selectedId = layout.id
        return layout
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        if (selectedId == id) selectedId = DEFAULT_ID
    }

    private fun parseOrNull(json: String): Layout? = try {
        LayoutParser.parse(json, keycodes)
    } catch (e: LayoutException) {
        null
    }

    companion object {
        const val DEFAULT_ID = "classic-qwerty"
    }
}
