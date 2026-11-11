package com.remotelink.core.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object Protocol {
    const val VERSION = 1
    const val DEFAULT_PORT = 8765
    const val SERVICE_TYPE = "_remotelink._tcp."
}

object MsgType {
    const val CHALLENGE = "challenge"
    const val HELLO = "hello"
    const val HELLO_OK = "hello_ok"
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_OK = "pair_ok"
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
}

/** رسالة قناة التحكم: type + id + timestamp + payload */
data class Msg(
    val type: String,
    val id: String = UUID.randomUUID().toString(),
    val ts: Long = System.currentTimeMillis(),
    val payload: JSONObject = JSONObject()
) {
    fun encode(): String = JSONObject()
        .put("type", type)
        .put("id", id)
        .put("timestamp", ts)
        .put("payload", payload)
        .toString()

    companion object {
        fun decode(text: String): Msg? = try {
            val o = JSONObject(text)
            Msg(
                type = o.getString("type"),
                id = o.optString("id"),
                ts = o.optLong("timestamp"),
                payload = o.optJSONObject("payload") ?: JSONObject()
            )
        } catch (e: Exception) {
            null
        }

        fun error(code: String, message: String = ""): Msg =
            Msg(MsgType.ERROR, payload = JSONObject().put("code", code).put("message", message))
    }
}

/** محتوى رمز QR المعروض على الجوال المتحكَّم به. لا يحتوي على الرقم السري (PIN). */
data class QrPayload(
    val hostId: String,
    val name: String,
    val ips: List<String>,
    val port: Int,
    val fp: String,
    val key: String
) {
    fun toJson(): String = JSONObject()
        .put("app", "remotelink")
        .put("v", Protocol.VERSION)
        .put("id", hostId)
        .put("name", name)
        .put("ips", JSONArray(ips))
        .put("port", port)
        .put("fp", fp)
        .put("key", key)
        .toString()

    companion object {
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
        private val HEX64 = Regex("^[0-9a-f]{64}$")

        fun parse(text: String): QrPayload? = try {
            val o = JSONObject(text)
            if (o.optString("app") != "remotelink" || o.optInt("v") != Protocol.VERSION) {
                null
            } else {
                val arr = o.getJSONArray("ips")
                val ips = (0 until arr.length()).map { arr.getString(it) }.filter { IPV4.matches(it) }
                val port = o.getInt("port")
                val fp = o.getString("fp").lowercase()
                val key = o.getString("key")
                if (ips.isEmpty() || port !in 1..65535 || !HEX64.matches(fp) || key.length < 16) null
                else QrPayload(o.getString("id"), o.optString("name", "?"), ips, port, fp, key)
            }
        } catch (e: Exception) {
            null
        }
    }
}
