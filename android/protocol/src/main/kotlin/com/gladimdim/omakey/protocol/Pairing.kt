package com.gladimdim.omakey.protocol

import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/** Everything a phone keeps about one paired computer. */
data class HostRecord(
    val hostId: String,
    val name: String,
    val addresses: List<String>,
    val port: Int,
    val deviceId: ByteArray,
    val key: ByteArray,
) {
    val deviceIdHex: String get() = Hex.encode(deviceId)

    override fun equals(other: Any?): Boolean =
        other is HostRecord && hostId == other.hostId && name == other.name &&
            addresses == other.addresses && port == other.port &&
            deviceId.contentEquals(other.deviceId) && key.contentEquals(other.key)

    override fun hashCode(): Int = hostId.hashCode()
}

class PairingException(message: String) : Exception(message)

/**
 * Parses `omakey://pair?v=1&h=<host id>&n=<name>&a=<ip>,<ip>&p=<port>&d=<device id>&k=<key>`.
 */
object PairingUri {
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

    fun parse(text: String): HostRecord {
        val uri = try {
            URI(text.trim())
        } catch (e: Exception) {
            throw PairingException("Not a pairing link")
        }
        if (uri.scheme != "omakey" || uri.host != "pair") throw PairingException("Not an omakey pairing link")
        val q = query(uri.rawQuery ?: throw PairingException("Pairing link has no data"))

        if (q["v"] != "1") throw PairingException("Unsupported pairing version ${q["v"]}; update the app")
        val hostId = q["h"]?.lowercase()?.takeIf { Hex.isHex(it, 8) }
            ?: throw PairingException("Bad host id")
        val deviceId = q["d"]?.takeIf { Hex.isHex(it, 8) }?.let(Hex::decode)
            ?: throw PairingException("Bad device id")
        val key = try {
            Base64.getUrlDecoder().decode(q["k"] ?: "")
        } catch (e: IllegalArgumentException) {
            null
        }?.takeIf { it.size == 32 } ?: throw PairingException("Bad key")
        val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: throw PairingException("Bad port")
        val addresses = (q["a"] ?: "").split(',').map { it.trim() }.filter { IPV4.matches(it) }
        if (addresses.isEmpty()) throw PairingException("Pairing link has no addresses")
        val name = q["n"]?.takeIf { it.isNotBlank() } ?: hostId

        return HostRecord(hostId, name, addresses, port, deviceId, key)
    }

    private fun query(raw: String): Map<String, String> =
        raw.split('&').filter { it.isNotEmpty() }.associate { part ->
            val i = part.indexOf('=')
            val k = if (i < 0) part else part.substring(0, i)
            val v = if (i < 0) "" else part.substring(i + 1)
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
}

object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(b: ByteArray): String = buildString(b.size * 2) {
        for (x in b) {
            append(DIGITS[(x.toInt() shr 4) and 0xF])
            append(DIGITS[x.toInt() and 0xF])
        }
    }

    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0)
        return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun isHex(s: String, bytes: Int): Boolean =
        s.length == bytes * 2 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
