package com.remotelink.core.network

import com.remotelink.core.protocol.Msg
import com.remotelink.core.protocol.MsgType
import com.remotelink.core.protocol.QrPayload
import com.remotelink.core.security.CertManager
import com.remotelink.core.security.Crypto
import com.remotelink.core.storage.PairedHost
import com.remotelink.core.storage.Store
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AuthException(val code: String) : Exception(code)

sealed interface ConnState {
    data object Idle : ConnState
    data class Connecting(val hostName: String, val attempt: Int) : ConnState
    data class Connected(val hostName: String, val ip: String, val rttMs: Long) : ConnState
    data class Failed(val code: String) : ConnState
}

/** اتصال الجوال المتحكِّم بالجوال المتحكَّم به: اقتران، اتصال، نبض، وإعادة اتصال تلقائية. */
class ControllerConnection(private val store: Store, private val certManager: CertManager) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state
    private var job: Job? = null

    private class Link(val client: SecureClient, val incoming: Channel<Msg>) {
        fun send(m: Msg) {
            try {
                client.send(m.encode())
            } catch (_: Exception) {
            }
        }

        fun close() {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    // ---------- low level ----------
    private suspend fun openLink(ip: String, port: Int, fp: String): Link = withContext(Dispatchers.IO) {
        val ch = Channel<Msg>(Channel.UNLIMITED)
        var failure: Exception? = null
        val client = SecureClient(URI("wss://$ip:$port"), certManager.pinnedClientContext(fp), object : SecureClient.Callbacks {
            override fun onText(text: String) {
                Msg.decode(text)?.let { ch.trySend(it) }
            }

            override fun onClosed(code: Int, reason: String) {
                ch.close()
            }

            override fun onFailure(e: Exception) {
                failure = e
            }
        })
        val ok = try {
            client.connectBlocking(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        if (!ok) {
            client.close()
            throw IOException(failure?.message ?: "connect failed")
        }
        Link(client, ch)
    }

    private suspend fun awaitMsg(link: Link, timeoutMs: Long = 6000): Msg =
        withTimeoutOrNull(timeoutMs) { link.incoming.receiveCatching().getOrNull() }
            ?: throw IOException("no response")

    private suspend fun awaitNonce(link: Link): String {
        val ch = awaitMsg(link)
        if (ch.type != MsgType.CHALLENGE) throw IOException("unexpected ${ch.type}")
        return ch.payload.getString("nonce")
    }

    private suspend fun handshakeHello(link: Link, host: PairedHost): String {
        val nonce = awaitNonce(link)
        val cid = store.deviceId()
        link.send(
            Msg(
                MsgType.HELLO,
                payload = JSONObject()
                    .put("controllerId", cid)
                    .put("name", store.deviceName())
                    .put("proof", Crypto.hmacB64(host.token, nonce + cid))
            )
        )
        val r = awaitMsg(link)
        return when (r.type) {
            MsgType.HELLO_OK -> r.payload.optString("hostName", host.name)
            MsgType.ERROR -> throw AuthException(r.payload.optString("code", "error"))
            else -> throw IOException("unexpected ${r.type}")
        }
    }

    // ---------- pairing ----------
    suspend fun pair(qr: QrPayload, pin: String): PairedHost {
        var last: Exception? = null
        for (ip in qr.ips) {
            try {
                val link = openLink(ip, qr.port, qr.fp)
                try {
                    val nonce = awaitNonce(link)
                    val cid = store.deviceId()
                    link.send(
                        Msg(
                            MsgType.PAIR_REQUEST,
                            payload = JSONObject()
                                .put("controllerId", cid)
                                .put("name", store.deviceName())
                                .put("proof", Crypto.hmacB64(qr.key, nonce + pin + cid))
                        )
                    )
                    val r = awaitMsg(link)
                    when (r.type) {
                        MsgType.PAIR_OK -> {
                            val host = PairedHost(
                                id = r.payload.getString("hostId"),
                                name = r.payload.optString("hostName", qr.name),
                                fp = qr.fp,
                                token = r.payload.getString("token"),
                                ips = qr.ips,
                                port = qr.port,
                                pairedAt = System.currentTimeMillis()
                            )
                            store.savePairedHost(host)
                            return host
                        }
                        MsgType.ERROR -> throw AuthException(r.payload.optString("code", "error"))
                        else -> throw IOException("unexpected ${r.type}")
                    }
                } finally {
                    link.close()
                }
            } catch (e: AuthException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("no address")
    }

    // ---------- session ----------
    fun connect(host: PairedHost, extraIps: List<String> = emptyList()) {
        job?.cancel()
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                _state.value = ConnState.Connecting(host.name, attempt)
                var link: Link? = null
                var hostName = host.name
                var usedIp = ""
                for (ip in (extraIps + host.ips).distinct()) {
                    try {
                        val l = openLink(ip, host.port, host.fp)
                        try {
                            hostName = handshakeHello(l, host)
                            link = l
                            usedIp = ip
                        } catch (e: Exception) {
                            l.close()
                            throw e
                        }
                    } catch (e: AuthException) {
                        if (e.code == "not_paired" || e.code == "bad_proof") {
                            _state.value = ConnState.Failed(e.code)
                            return@launch
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // نجرّب العنوان التالي
                    }
                    if (link != null) break
                }
                if (link != null) {
                    attempt = 0
                    runSession(link, host, hostName, usedIp)
                } else {
                    attempt++
                }
                if (isActive) delay(minOf(1000L shl minOf(attempt, 4), 15_000L))
            }
        }
    }

    private suspend fun runSession(link: Link, host: PairedHost, hostName: String, ip: String) {
        store.savePairedHost(host.copy(ips = (listOf(ip) + host.ips).distinct()))
        val lastPong = AtomicLong(System.currentTimeMillis())
        _state.value = ConnState.Connected(hostName, ip, -1)
        try {
            coroutineScope {
                val reader = launch {
                    for (m in link.incoming) {
                        if (m.type == MsgType.PONG) {
                            val now = System.currentTimeMillis()
                            lastPong.set(now)
                            _state.value = ConnState.Connected(hostName, ip, now - m.payload.optLong("t", now))
                        }
                    }
                }
                val beat = launch {
                    while (isActive) {
                        link.send(Msg(MsgType.PING, payload = JSONObject().put("t", System.currentTimeMillis())))
                        delay(3000)
                    }
                }
                val watchdog = launch {
                    while (isActive) {
                        delay(1000)
                        if (System.currentTimeMillis() - lastPong.get() > 9000) {
                            link.close()
                            break
                        }
                    }
                }
                reader.join()
                beat.cancel()
                watchdog.cancel()
            }
        } finally {
            link.close()
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
        _state.value = ConnState.Idle
    }
}
