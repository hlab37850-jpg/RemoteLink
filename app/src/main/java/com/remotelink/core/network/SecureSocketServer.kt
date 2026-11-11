package com.remotelink.core.network

import com.remotelink.core.protocol.Msg
import com.remotelink.core.protocol.MsgType
import com.remotelink.core.protocol.Protocol
import com.remotelink.core.security.Crypto
import com.remotelink.core.security.PairingManager
import com.remotelink.core.storage.Store
import com.remotelink.core.storage.TrustedController
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext

/**
 * خادم WebSocket عبر TLS على الجوال المتحكَّم به.
 * كل اتصال يبدأ بـ challenge ثم إما hello (جهاز مقترن) أو pair_request (اقتران جديد).
 */
class SecureSocketServer(
    port: Int,
    sslContext: SSLContext,
    private val store: Store,
    private val pairing: PairingManager,
    private val hostName: () -> String,
    private val hostId: String,
    private val events: Events
) : WebSocketServer(InetSocketAddress(port)) {

    interface Events {
        fun onServerStarted()
        fun onServerError(e: Exception)
        fun onControllerConnected(name: String)
        fun onControllerDisconnected()
    }

    private class Session(val nonce: String) {
        @Volatile var authed = false
        @Volatile var controllerId: String? = null
    }

    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()
    @Volatile private var active: WebSocket? = null

    init {
        isReuseAddr = true
        connectionLostTimeout = 10
        setWebSocketFactory(DefaultSSLWebSocketServerFactory(sslContext))
    }

    override fun onStart() = events.onServerStarted()

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val ip = remoteIp(conn)
        if (isBlocked(ip)) {
            conn.close(1008, "blocked")
            return
        }
        val s = Session(Crypto.randomB64(16))
        conn.setAttachment(s)
        conn.send(
            Msg(
                MsgType.CHALLENGE,
                payload = JSONObject().put("nonce", s.nonce).put("v", Protocol.VERSION).put("hostId", hostId)
            ).encode()
        )
        timer.schedule(Runnable {
            if (!s.authed && conn.isOpen) conn.close(1008, "auth timeout")
        }, 10, TimeUnit.SECONDS)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        try {
            val s = conn.getAttachment<Session>() ?: return
            val m = Msg.decode(message)
            if (m == null) {
                conn.close(1003, "bad message")
                return
            }
            if (!s.authed) {
                when (m.type) {
                    MsgType.HELLO -> handleHello(conn, s, m)
                    MsgType.PAIR_REQUEST -> handlePair(conn, s, m)
                    else -> reject(conn, "unauthenticated")
                }
                return
            }
            when (m.type) {
                MsgType.PING -> conn.send(Msg(MsgType.PONG, id = m.id, payload = m.payload).encode())
                else -> conn.send(Msg.error("unsupported", m.type).encode())
            }
        } catch (e: Exception) {
            try {
                conn.close(1011, "internal")
            } catch (_: Exception) {
            }
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        if (active === conn) {
            active = null
            events.onControllerDisconnected()
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception?) {
        if (conn == null && ex != null) events.onServerError(ex)
    }

    // ---------- handlers ----------
    private fun handleHello(conn: WebSocket, s: Session, m: Msg) {
        val cid = m.payload.optString("controllerId")
        val proof = m.payload.optString("proof")
        val tc = store.findTrusted(cid)
        if (tc == null) {
            reject(conn, "not_paired")
            return
        }
        val expected = Crypto.hmacB64(tc.token, s.nonce + cid)
        if (!Crypto.constantTimeEquals(expected, proof)) {
            reject(conn, "bad_proof")
            return
        }
        if (!accept(conn, s, tc.id, tc.name)) return
        conn.send(Msg(MsgType.HELLO_OK, payload = JSONObject().put("hostName", hostName())).encode())
    }

    private fun handlePair(conn: WebSocket, s: Session, m: Msg) {
        val cid = m.payload.optString("controllerId")
        val name = m.payload.optString("name", "Android")
        val proof = m.payload.optString("proof")
        val ses = pairing.current()
        if (ses == null || cid.isBlank()) {
            reject(conn, "pairing_inactive")
            return
        }
        val expected = Crypto.hmacB64(ses.key, s.nonce + ses.pin + cid)
        if (!Crypto.constantTimeEquals(expected, proof)) {
            pairing.registerFailure()
            reject(conn, "bad_pin")
            return
        }
        val token = Crypto.randomB64(32)
        pairing.consume()
        store.addTrusted(TrustedController(cid, name, token, System.currentTimeMillis()))
        if (!accept(conn, s, cid, name)) return
        conn.send(
            Msg(
                MsgType.PAIR_OK,
                payload = JSONObject().put("hostId", hostId).put("hostName", hostName()).put("token", token)
            ).encode()
        )
    }

    private fun accept(conn: WebSocket, s: Session, cid: String, name: String): Boolean {
        synchronized(this) {
            val cur = active
            if (cur != null && cur !== conn && cur.isOpen) {
                val curSession = cur.getAttachment<Session>()
                if (curSession?.controllerId == cid) {
                    cur.close(1000, "replaced") // نفس الجهاز أعاد الاتصال
                } else {
                    reject(conn, "busy", countFailure = false)
                    return false
                }
            }
            active = conn
            s.authed = true
            s.controllerId = cid
        }
        events.onControllerConnected(name)
        return true
    }

    private fun reject(conn: WebSocket, code: String, countFailure: Boolean = true) {
        if (countFailure) recordFailure(remoteIp(conn))
        try {
            conn.send(Msg.error(code).encode())
        } catch (_: Exception) {
        }
        conn.close(1008, code)
    }

    // ---------- brute-force protection ----------
    private fun remoteIp(conn: WebSocket): String = conn.remoteSocketAddress?.address?.hostAddress ?: "?"

    private fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val list = failures.getOrPut(ip) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > 60_000 }
            list.add(now)
        }
    }

    private fun isBlocked(ip: String): Boolean {
        val list = failures[ip] ?: return false
        val now = System.currentTimeMillis()
        synchronized(list) {
            list.removeAll { now - it > 60_000 }
            return list.size >= 5
        }
    }

    // ---------- control ----------
    fun disconnectController(id: String) {
        val cur = active ?: return
        if (cur.getAttachment<Session>()?.controllerId == id) cur.close(1008, "unpaired")
    }

    fun shutdown() {
        try {
            stop(500)
        } catch (_: Exception) {
        }
        timer.shutdownNow()
    }
}
