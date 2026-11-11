package com.remotelink.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.remotelink.AppContainer
import com.remotelink.core.network.AuthException
import com.remotelink.core.network.HostUiState
import com.remotelink.core.protocol.QrPayload
import com.remotelink.core.security.PairingManager
import com.remotelink.core.storage.PairedHost
import com.remotelink.service.HostForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AppViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(HostViewModel::class.java) -> HostViewModel(c)
        modelClass.isAssignableFrom(ControllerViewModel::class.java) -> ControllerViewModel(c)
        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    } as T
}

class HostViewModel(private val c: AppContainer) : ViewModel() {
    val state = c.host.state
    val session = c.pairing.session
    val trusted = c.store.controllers

    fun startHost() = HostForegroundService.start(c.context)
    fun stopHost() = HostForegroundService.stop(c.context)

    fun beginPairing() {
        c.host.refreshIps()
        c.pairing.begin()
    }

    fun cancelPairing() = c.pairing.cancel()

    fun qrPayload(s: PairingManager.Session, st: HostUiState): String =
        c.pairing.qrPayload(st.port, st.ips, s)

    fun unpair(id: String) = c.host.unpair(id)
}

sealed interface PairUi {
    data object None : PairUi
    data object Busy : PairUi
    data class Error(val code: String) : PairUi
}

class ControllerViewModel(private val c: AppContainer) : ViewModel() {
    val hosts = c.store.hosts
    val discovered = c.discovery.found
    val conn = c.connection.state

    private val _pending = MutableStateFlow<QrPayload?>(null)
    val pendingQr: StateFlow<QrPayload?> = _pending
    private val _pairUi = MutableStateFlow<PairUi>(PairUi.None)
    val pairUi: StateFlow<PairUi> = _pairUi

    fun startDiscovery() = c.discovery.start()
    fun stopDiscovery() = c.discovery.stop()

    /** يرجع false إذا كان الرمز غير صالح. */
    fun onQr(text: String): Boolean {
        val q = QrPayload.parse(text) ?: return false
        _pairUi.value = PairUi.None
        _pending.value = q
        return true
    }

    fun cancelPair() {
        _pending.value = null
        _pairUi.value = PairUi.None
    }

    fun submitPin(pin: String) {
        val q = _pending.value ?: return
        _pairUi.value = PairUi.Busy
        viewModelScope.launch {
            try {
                val h = c.connection.pair(q, pin)
                _pending.value = null
                _pairUi.value = PairUi.None
                c.connection.connect(h)
            } catch (e: AuthException) {
                _pairUi.value = PairUi.Error(e.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _pairUi.value = PairUi.Error("network")
            }
        }
    }

    fun connect(h: PairedHost, manualIp: String? = null) {
        val nsdIp = discovered.value.firstOrNull { it.hostId == h.id }?.ip
        c.connection.connect(h, listOfNotNull(manualIp, nsdIp))
    }

    fun removeHost(h: PairedHost) = c.store.removeHost(h.id)

    fun disconnect() = c.connection.disconnect()
}
