package com.remotelink.core.network

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import javax.net.ssl.SSLContext

/** عميل WebSocket عبر TLS مع تثبيت بصمة الشهادة (يأتي SSLContext جاهزًا من CertManager). */
class SecureClient(
    uri: URI,
    sslContext: SSLContext,
    private val cb: Callbacks
) : WebSocketClient(uri) {

    interface Callbacks {
        fun onText(text: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(e: Exception)
    }

    init {
        setSocketFactory(sslContext.socketFactory)
        connectionLostTimeout = 0 // نستخدم نبضنا الخاص (ping/pong)
    }

    override fun onOpen(handshakedata: ServerHandshake?) {}

    override fun onMessage(message: String?) {
        if (message != null) cb.onText(message)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        cb.onClosed(code, reason ?: "")
    }

    override fun onError(ex: Exception?) {
        if (ex != null) cb.onFailure(ex)
    }
}
