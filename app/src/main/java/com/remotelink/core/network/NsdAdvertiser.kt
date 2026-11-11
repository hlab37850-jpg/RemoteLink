package com.remotelink.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.remotelink.core.protocol.Protocol

/** إعلان الخدمة على الشبكة المحلية (mDNS) ليجدها جوال المتحكِّم تلقائيًا. */
class NsdAdvertiser(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    @Synchronized
    fun start(name: String, port: Int, hostId: String, fpPrefix: String) {
        stop()
        val info = NsdServiceInfo().apply {
            serviceName = "RemoteLink-$name"
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
            setAttribute("id", hostId)
            setAttribute("v", Protocol.VERSION.toString())
            setAttribute("fp", fpPrefix)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
        }
        listener = l
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    @Synchronized
    fun stop() {
        listener?.let {
            try {
                nsd.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        listener = null
    }
}
