package com.gladimdim.omakey.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.gladimdim.omakey.protocol.Wire
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress

/** A computer advertising `_omakey._udp`. */
data class Found(val serviceName: String, val hostId: String?, val name: String, val address: InetSocketAddress)

/**
 * Browses mDNS for omakeyd and resolves what it finds, one at a time
 * (NsdManager rejects parallel resolves on older Android). Results are
 * delivered on the main thread.
 */
class Discovery(context: Context, private val onChange: (List<Found>) -> Unit) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, Found>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        if (listener != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) = main.post { enqueue(info) }.let {}
            override fun onServiceLost(info: NsdServiceInfo) = main.post {
                if (found.remove(info.serviceName) != null) onChange(found.values.toList())
            }.let {}
        }
        listener = l
        try {
            nsd.discoverServices(Wire.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            Log.w(TAG, "mDNS unavailable", e)
            listener = null
        }
    }

    fun stop() {
        listener?.let {
            try { nsd.stopServiceDiscovery(it) } catch (e: Exception) {}
        }
        listener = null
        queue.clear()
    }

    private fun enqueue(info: NsdServiceInfo) {
        queue.addLast(info)
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = main.post {
                resolving = false
                next()
            }.let {}

            override fun onServiceResolved(r: NsdServiceInfo) = main.post {
                resolving = false
                address(r)?.let { addr ->
                    val txt = r.attributes
                    val id = txt["id"]?.toString(Charsets.UTF_8)?.lowercase()
                    val name = txt["n"]?.toString(Charsets.UTF_8) ?: r.serviceName
                    found[r.serviceName] = Found(r.serviceName, id, name, InetSocketAddress(addr, r.port))
                    onChange(found.values.toList())
                }
                next()
            }.let {}
        })
    }

    @Suppress("DEPRECATION")
    private fun address(r: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) {
            r.hostAddresses.firstOrNull { it is Inet4Address } ?: r.hostAddresses.firstOrNull()
        } else {
            r.host
        }

    companion object {
        private const val TAG = "omakey"
    }
}
