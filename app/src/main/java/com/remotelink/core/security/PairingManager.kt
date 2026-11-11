package com.remotelink.core.security

import com.remotelink.core.protocol.QrPayload
import com.remotelink.core.storage.Store
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** جلسة اقتران واحدة: صالحة 5 دقائق، تُستهلك عند أول نجاح، وتُلغى بعد 3 محاولات فاشلة. */
class PairingManager(private val certManager: CertManager, private val store: Store) {

    data class Session(val key: String, val pin: String, val expiresAt: Long, val failures: Int = 0)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    fun begin(): Session {
        val s = Session(Crypto.randomB64(24), Crypto.pin6(), System.currentTimeMillis() + VALID_MS)
        _session.value = s
        return s
    }

    fun current(): Session? =
        _session.value?.takeIf { it.expiresAt > System.currentTimeMillis() && it.failures < MAX_FAILURES }

    fun registerFailure() {
        val s = _session.value ?: return
        _session.value = if (s.failures + 1 >= MAX_FAILURES) null else s.copy(failures = s.failures + 1)
    }

    fun consume() {
        _session.value = null
    }

    fun cancel() {
        _session.value = null
    }

    fun qrPayload(port: Int, ips: List<String>, s: Session): String =
        QrPayload(store.deviceId(), store.deviceName(), ips, port, certManager.fingerprint(), s.key).toJson()

    private companion object {
        const val VALID_MS = 5 * 60_000L
        const val MAX_FAILURES = 3
    }
}
