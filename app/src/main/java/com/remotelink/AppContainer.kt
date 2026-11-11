package com.remotelink

import android.content.Context
import com.remotelink.core.network.ControllerConnection
import com.remotelink.core.network.HostManager
import com.remotelink.core.network.NsdDiscovery
import com.remotelink.core.security.CertManager
import com.remotelink.core.security.PairingManager
import com.remotelink.core.storage.Store

/** حاوية تبعيات بسيطة (بديل مؤقت لـ Hilt في المرحلة 1). */
class AppContainer(val context: Context) {
    val store = Store(context)
    val certManager = CertManager(context, store)
    val pairing = PairingManager(certManager, store)
    val host = HostManager(context, store, certManager, pairing)
    val discovery = NsdDiscovery(context)
    val connection = ControllerConnection(store, certManager)
}
