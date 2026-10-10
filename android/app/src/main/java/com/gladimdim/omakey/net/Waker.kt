package com.gladimdim.omakey.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.gladimdim.omakey.protocol.WakeOnLan
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Wakes a sleeping computer: a Wake-on-LAN magic packet to 255.255.255.255
 * and to the Wi-Fi subnet's broadcast address, over Wi-Fi even when the
 * phone's default network is mobile data. Sends from a thread of its own.
 */
object Waker {
    fun wake(context: Context, mac: String) {
        val bytes = WakeOnLan.macBytes(mac) ?: return
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
        Thread({ send(cm, bytes) }, "omakey-wake").start()
    }

    private fun send(cm: ConnectivityManager?, mac: ByteArray) {
        val packet = WakeOnLan.magicPacket(mac)
        val wifi = cm?.let(::wifiNetwork)
        val targets = linkedSetOf(InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1)))
        wifi?.let { cm.getLinkProperties(it) }?.linkAddresses?.forEach { la ->
            val a = la.address as? Inet4Address ?: return@forEach
            if (la.prefixLength < 31) targets += InetAddress.getByAddress(WakeOnLan.broadcast(a.address, la.prefixLength))
        }
        try {
            DatagramSocket().use { s ->
                s.broadcast = true
                wifi?.bindSocket(s)
                for (t in targets) s.send(DatagramPacket(packet, packet.size, t, WakeOnLan.PORT))
            }
            Log.i("omakey", "Wake-on-LAN to ${WakeOnLan.macText(mac)} via ${targets.joinToString { it.hostAddress ?: "?" }}")
        } catch (e: IOException) {
            Log.w("omakey", "can't send Wake-on-LAN", e)
        }
    }

    /** The Wi-Fi network itself: a VPN over Wi-Fi says Wi-Fi too, but its broadcasts go into the tunnel. */
    private fun wifiNetwork(cm: ConnectivityManager): Network? {
        fun isWifi(n: Network) = cm.getNetworkCapabilities(n)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
        cm.activeNetwork?.takeIf(::isWifi)?.let { return it }
        // Wi-Fi without internet: Android keeps mobile data as the default.
        @Suppress("DEPRECATION")
        return cm.allNetworks.firstOrNull(::isWifi)
    }
}
