package com.remotelink.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.remotelink.core.protocol.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class DiscoveredHost(
    val serviceName: String,
    val hostId: String?,
    val name: String,
    val ip: String,
    val port: Int
)

/** اكتشاف أجهزة RemoteLink على الشبكة المحلية. التحليل (resolve) يتم بالتسلسل لتفادي FAILURE_ALREADY_ACTIVE. */
class NsdDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val _found = MutableStateFlow<List<DiscoveredHost>>(emptyList())
    val found: StateFlow<List<DiscoveredHost>> = _found

    private val lock = Any()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    fun start() {
        synchronized(lock) {
            if (listener != null) return
            val l = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    synchronized(lock) { listener = null }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    if (serviceInfo.serviceType.contains("_remotelink")) {
                        synchronized(lock) { queue.addLast(serviceInfo) }
                        pump()
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                    _found.update { list -> list.filterNot { it.serviceName == serviceInfo.serviceName } }
                }
            }
            listener = l
            try {
                nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: Exception) {
                listener = null
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            listener?.let {
                try {
                    nsd.stopServiceDiscovery(it)
                } catch (_: Exception) {
                }
            }
            listener = null
            queue.clear()
            resolving = false
        }
        _found.value = emptyList()
    }

    @Suppress("DEPRECATION")
    private fun pump() {
        val next = synchronized(lock) {
            if (resolving) return
            val n = queue.removeFirstOrNull() ?: return
            resolving = true
            n
        }
        try {
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    done()
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    handle(serviceInfo)
                    done()
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    private fun done() {
        synchronized(lock) { resolving = false }
        pump()
    }

    @Suppress("DEPRECATION")
    private fun handle(r: NsdServiceInfo) {
        val ip = r.host?.hostAddress ?: return
        if (ip.contains(':')) return // IPv4 فقط
        val id = r.attributes["id"]?.toString(Charsets.UTF_8)
        val item = DiscoveredHost(
            serviceName = r.serviceName,
            hostId = id,
            name = r.serviceName.removePrefix("RemoteLink-"),
            ip = ip,
            port = r.port
        )
        _found.update { list -> list.filterNot { it.serviceName == item.serviceName } + item }
    }
}
