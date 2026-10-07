package com.gladimdim.omakey.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.gladimdim.omakey.layout.Keycodes
import com.gladimdim.omakey.layout.Layout
import com.gladimdim.omakey.layout.LayoutException
import com.gladimdim.omakey.layout.LayoutParser
import com.gladimdim.omakey.protocol.Hex
import com.gladimdim.omakey.protocol.HostRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Paired computers, as JSON in app-private SharedPreferences. Each pairing
 * key is encrypted with an AES key that lives in the Android Keystore and
 * never leaves it ([KeyWrap]); backups are disabled in the manifest too.
 * Methods are synchronized: the keyboard saves addresses from a background
 * callback while the UI may save too.
 */
class HostStore(context: Context) {
    private val prefs = context.getSharedPreferences("hosts", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<HostRecord> {
        val raw = prefs.getString("hosts", "[]") ?: "[]"
        cache?.let { (r, list) -> if (r == raw) return list }
        val arr = JSONArray(raw)
        var legacy = false
        val list = (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (!o.has("keyWrapped")) legacy = true
            try {
                fromJson(o)
            } catch (e: Exception) {
                // The Keystore key is gone (it never leaves this phone): this pairing can't be used.
                Log.w("omakey", "can't read the pairing for ${o.optString("name")}", e)
                null
            }
        }
        cache = raw to list
        // Pairings saved before keys were encrypted: encrypt them now.
        if (legacy) save(list)
        return list
    }

    fun get(hostId: String): HostRecord? = all().firstOrNull { it.hostId == hostId }

    /** Adds or replaces the host with the same id (re-pairing). */
    @Synchronized
    fun put(host: HostRecord) = save(listOf(host) + all().filter { it.hostId != host.hostId })

    @Synchronized
    fun remove(hostId: String) = save(all().filter { it.hostId != hostId })

    /** Remember the address that answered, first in line for next time. */
    @Synchronized
    fun rememberAddress(hostId: String, address: String) {
        val list = all()
        if (list.firstOrNull { it.hostId == hostId }?.addresses?.firstOrNull() == address) return
        save(list.map { h ->
            if (h.hostId == hostId) h.copy(addresses = (listOf(address) + h.addresses.filter { it != address }).take(6))
            else h
        })
    }

    /** The computer's Bluetooth address, learned over Wi-Fi, for the Bluetooth fallback. */
    @Synchronized
    fun rememberBtAddress(hostId: String, address: String) =
        save(all().map { h -> if (h.hostId == hostId) h.copy(btAddress = address) else h })

    private fun save(hosts: List<HostRecord>) {
        val arr = JSONArray()
        hosts.forEach { arr.put(toJson(it)) }
        val raw = arr.toString()
        prefs.edit().putString("hosts", raw).apply()
        cache = raw to hosts
    }

    private fun toJson(h: HostRecord): JSONObject {
        val o = JSONObject()
            .put("hostId", h.hostId)
            .put("name", h.name)
            .put("addresses", JSONArray(h.addresses))
            .put("port", h.port)
            .put("deviceId", Hex.encode(h.deviceId))
            .put("btAddress", h.btAddress)
        val wrapped = KeyWrap.wrap(h.key)
        // No Keystore (broken firmware): keep the key as before rather than lose the pairing.
        return if (wrapped != null) o.put("keyWrapped", wrapped) else o.put("key", Base64.getEncoder().encodeToString(h.key))
    }

    private fun fromJson(o: JSONObject): HostRecord {
        val a = o.getJSONArray("addresses")
        val key = if (o.has("keyWrapped")) KeyWrap.unwrap(o.getString("keyWrapped"))
        else Base64.getDecoder().decode(o.getString("key"))
        return HostRecord(
            o.getString("hostId"), o.getString("name"), List(a.length()) { a.getString(it) },
            o.getInt("port"), Hex.decode(o.getString("deviceId")), key,
            o.optString("btAddress").takeIf { it.isNotEmpty() },
        )
    }

    private companion object {
        /** The parsed list for the stored JSON, shared by every instance in the process. */
        @Volatile var cache: Pair<String, List<HostRecord>>? = null
    }
}

/** AES-256-GCM with a non-exportable Android Keystore key, for the pairing keys at rest. */
private object KeyWrap {
    private const val ALIAS = "omakey-pairing-keys"
    private const val IV_LEN = 12

    private fun secret(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** base64(iv ‖ ciphertext), or null when the Keystore isn't usable. */
    fun wrap(plain: ByteArray): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, secret())
        Base64.getEncoder().encodeToString(c.iv + c.doFinal(plain))
    } catch (e: Exception) {
        Log.w("omakey", "Keystore unavailable; pairing key stored unencrypted", e)
        null
    }

    fun unwrap(s: String): ByteArray {
        val b = Base64.getDecoder().decode(s)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, b, 0, IV_LEN))
        return c.doFinal(b, IV_LEN, b.size - IV_LEN)
    }
}

/** A computer the phone has been a Bluetooth keyboard for. */
data class BtHost(val address: String, val name: String)

/** Computers that used this phone as a Bluetooth keyboard, most recent first. */
class BtHostStore(context: Context) {
    private val prefs = context.getSharedPreferences("bt_hosts", Context.MODE_PRIVATE)

    fun all(): List<BtHost> {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        return List(arr.length()) { i -> arr.getJSONObject(i).let { BtHost(it.getString("address"), it.getString("name")) } }
    }

    fun get(address: String): BtHost? = all().firstOrNull { it.address.equals(address, ignoreCase = true) }

    fun put(host: BtHost) = save(listOf(host) + all().filter { !it.address.equals(host.address, ignoreCase = true) })

    fun remove(address: String) = save(all().filter { !it.address.equals(address, ignoreCase = true) })

    private fun save(hosts: List<BtHost>) {
        val arr = JSONArray()
        hosts.forEach { arr.put(JSONObject().put("address", it.address).put("name", it.name)) }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }
}

/** Built-in layouts from assets plus imported ones in files/layouts. */
class LayoutStore(private val context: Context) {
    private val dir = File(context.filesDir, "layouts").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("layouts", Context.MODE_PRIVATE)

    val keycodes: Keycodes
        get() = keycodesCache ?: Keycodes.parse(context.assets.open("keycodes.json").bufferedReader().use { it.readText() })
            .also { keycodesCache = it }

    class Entry(val layout: Layout, val builtIn: Boolean)

    /**
     * Built-in layouts are parsed once per process, imported ones again only
     * when their file changes: the connect screen asks on every redraw.
     */
    fun all(): List<Entry> {
        val builtIn = builtInCache ?: (context.assets.list("layouts") ?: emptyArray()).sorted().mapNotNull { f ->
            parseOrNull(context.assets.open("layouts/$f").bufferedReader().use { it.readText() })?.let { Entry(it, true) }
        }.also { builtInCache = it }
        val ids = builtIn.map { it.layout.id }.toSet()
        val imported = (dir.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { f ->
            val stamp = f.lastModified() to f.length()
            val cached = importedCache[f.name]?.takeIf { it.first == stamp }?.second
            val layout = cached ?: parseOrNull(f.readText()).also { importedCache[f.name] = stamp to it }
            layout?.takeIf { it.id !in ids }?.let { Entry(it, false) }
        }
        return builtIn + imported
    }

    /** Parse and validate without saving, to preview an import. */
    fun preview(json: String): Layout = LayoutParser.parse(json, keycodes)

    /** The imported layout an import of [id] would replace, if any. */
    fun importedWithId(id: String): Layout? = all().firstOrNull { !it.builtIn && it.layout.id == id }?.layout

    var selectedId: String
        get() = (prefs.getString("selected", DEFAULT_ID) ?: DEFAULT_ID).let { RENAMED[it] ?: it }
        set(value) = prefs.edit().putString("selected", value).apply()

    fun selected(): Layout = all().let { list ->
        (list.firstOrNull { it.layout.id == selectedId }
            ?: list.firstOrNull { it.layout.id == DEFAULT_ID }
            ?: list.first()).layout
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
        @Volatile private var keycodesCache: Keycodes? = null
        @Volatile private var builtInCache: List<Entry>? = null
        private val importedCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Pair<Long, Long>, Layout?>>()

        const val DEFAULT_ID = "classic-qwerty"
        /** Built-in layouts that were renamed, old id to new, so a selection survives the update. */
        private val RENAMED = mapOf("split-qwerty" to "omakey-pro")
    }
}
