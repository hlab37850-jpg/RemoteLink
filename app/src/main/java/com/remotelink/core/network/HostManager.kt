package com.remotelink.core.network

import android.content.Context
import com.remotelink.core.protocol.Protocol
import com.remotelink.core.security.CertManager
import com.remotelink.core.security.PairingManager
import com.remotelink.core.storage.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.Executors

data class HostUiState(
    val running: Boolean = false,
    val port: Int = Protocol.DEFAULT_PORT,
    val ips: List<String> = emptyList(),
    val connectedController: String? = null,
    val error: String? = null
)

/** يدير دورة حياة الخادم + إعلان NSD. يُستدعى من HostForegroundService. */
class HostManager(
    context: Context,
    private val store: Store,
    private val certManager: CertManager,
    private val pairing: PairingManager
) : SecureSocketServer.Events {

    private val exec = Executors.newSingleThreadExecutor()
    private val lock = Any()
    private val nsd = NsdAdvertiser(context)
    private var server: SecureSocketServer? = null

    val state = MutableStateFlow(HostUiState())
    val stateFlow: StateFlow<HostUiState> get() = state

    fun start() {
        exec.execute {
            synchronized(lock) {
                if (server != null) return@execute
                try {
                    val ctx = certManager.serverSslContext()
                    val port = findFreePort()
                    val s = SecureSocketServer(port, ctx, store, pairing, { store.deviceName() }, store.deviceId(), this)
                    server = s
                    s.start()
                    nsd.start(store.deviceName(), port, store.deviceId(), certManager.fingerprint().take(16))
                    state.value = HostUiState(running = true, port = port, ips = NetUtil.localIpv4())
                } catch (e: Exception) {
                    server = null
                    state.value = HostUiState(running = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    fun stop() {
        exec.execute {
            synchronized(lock) {
                server?.shutdown()
                server = null
                nsd.stop()
                pairing.cancel()
                state.value = HostUiState()
            }
        }
    }

    fun refreshIps() {
        state.update { it.copy(ips = NetUtil.localIpv4()) }
    }

    fun unpair(controllerId: String) {
        store.removeTrusted(controllerId)
        server?.disconnectController(controllerId)
    }

    private fun findFreePort(): Int {
        for (p in Protocol.DEFAULT_PORT..Protocol.DEFAULT_PORT + 10) {
            try {
                ServerSocket(p).use { return p }
            } catch (_: IOException) {
            }
        }
        throw IOException("no free port")
    }

    // ---------- SecureSocketServer.Events ----------
    override fun onServerStarted() {}

    override fun onServerError(e: Exception) {
        state.update { it.copy(error = e.message) }
    }

    override fun onControllerConnected(name: String) {
        state.update { it.copy(connectedController = name) }
    }

    override fun onControllerDisconnected() {
        state.update { it.copy(connectedController = null) }
    }
}
