package com.gladimdim.omakey.protocol

/**
 * The Wake-on-LAN magic packet (PROTOCOL.md, Wake-on-LAN). It isn't part
 * of the Omakey protocol: any computer set to wake on LAN takes it, so it
 * goes out unencrypted, to the network's broadcast address.
 */
object WakeOnLan {
    const val PORT = 9

    /** "e8:8d:a6:e0:89:93", as the phone stores it. */
    fun macText(mac: ByteArray): String = Hex.encode(mac).chunked(2).joinToString(":")

    fun macBytes(text: String): ByteArray? = text.replace(":", "").takeIf { Hex.isHex(it, 6) }?.let(Hex::decode)

    /** Six 0xFF bytes, then the MAC address 16 times. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        val out = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) mac.copyInto(out, 6 + i * 6)
        return out
    }

    /** The broadcast address of the IPv4 network [address]/[prefix]: its host bits all set. */
    fun broadcast(address: ByteArray, prefix: Int): ByteArray {
        require(address.size == 4 && prefix in 0..32)
        val host = if (prefix == 0) -1 else (1 shl (32 - prefix)) - 1
        return ByteArray(4) { i -> (address[i].toInt() or (host ushr (24 - 8 * i))).toByte() }
    }
}
