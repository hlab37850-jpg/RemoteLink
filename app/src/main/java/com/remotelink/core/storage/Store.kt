package com.remotelink.core.storage

import android.content.Context
import android.os.Build
import com.remotelink.core.security.Crypto
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** جهاز متحكِّم موثوق (يُحفظ على الجوال المتحكَّم به). */
data class TrustedController(val id: String, val name: String, val token: String, val pairedAt: Long)

/** جوال متحكَّم به مقترن (يُحفظ على جوال المتحكِّم). */
data class PairedHost(
    val id: String,
    val name: String,
    val fp: String,
    val token: String,
    val ips: List<String>,
    val port: Int,
    val pairedAt: Long
)

/** تخزين محلي بسيط داخل مساحة التطبيق الخاصة (SharedPreferences). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("remotelink", Context.MODE_PRIVATE)
    private val lock = Any()

    val controllers = MutableStateFlow(loadControllers())
    val hosts = MutableStateFlow(loadHosts())

    fun deviceId(): String = synchronized(lock) {
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    fun deviceName(): String =
        prefs.getString("device_name", null) ?: Build.MODEL.ifBlank { "Android" }

    fun setDeviceName(name: String) {
        prefs.edit().putString("device_name", name.trim()).apply()
    }

    fun keystorePassword(): String = synchronized(lock) {
        prefs.getString("ks_pw", null) ?: Crypto.randomB64(24).also {
            prefs.edit().putString("ks_pw", it).apply()
        }
    }

    // ---------- trusted controllers (host side) ----------
    fun findTrusted(id: String): TrustedController? = controllers.value.firstOrNull { it.id == id }

    fun addTrusted(c: TrustedController) = synchronized(lock) {
        val list = controllers.value.filterNot { it.id == c.id } + c
        saveControllers(list)
        controllers.value = list
    }

    fun removeTrusted(id: String) = synchronized(lock) {
        val list = controllers.value.filterNot { it.id == id }
        saveControllers(list)
        controllers.value = list
    }

    // ---------- paired hosts (controller side) ----------
    fun savePairedHost(h: PairedHost) = synchronized(lock) {
        val list = hosts.value.filterNot { it.id == h.id } + h
        saveHosts(list)
        hosts.value = list
    }

    fun removeHost(id: String) = synchronized(lock) {
        val list = hosts.value.filterNot { it.id == id }
        saveHosts(list)
        hosts.value = list
    }

    // ---------- persistence ----------
    private fun loadControllers(): List<TrustedController> = try {
        val arr = JSONArray(prefs.getString("controllers", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            TrustedController(o.getString("id"), o.getString("name"), o.getString("token"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveControllers(list: List<TrustedController>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("token", it.token).put("at", it.pairedAt))
        }
        prefs.edit().putString("controllers", arr.toString()).apply()
    }

    private fun loadHosts(): List<PairedHost> = try {
        val arr = JSONArray(prefs.getString("hosts", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val ipsArr = o.getJSONArray("ips")
            PairedHost(
                id = o.getString("id"),
                name = o.getString("name"),
                fp = o.getString("fp"),
                token = o.getString("token"),
                ips = (0 until ipsArr.length()).map { i -> ipsArr.getString(i) },
                port = o.getInt("port"),
                pairedAt = o.optLong("at")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveHosts(list: List<PairedHost>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("fp", it.fp)
                    .put("token", it.token).put("ips", JSONArray(it.ips)).put("port", it.port).put("at", it.pairedAt)
            )
        }
        prefs.edit().putString("hosts", arr.toString()).apply()
    }
}
