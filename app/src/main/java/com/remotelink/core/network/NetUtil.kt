package com.remotelink.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class NetInfo(val type: Int = NetUtil.NONE, val signal: Int = -1)

object NetUtil {
    const val NONE = 0
    const val WIFI = 1
    const val ETHERNET = 2
    const val CELLULAR = 3
    const val OTHER = 4

    private val IGNORED_PREFIXES = listOf("rmnet", "ccmni", "dummy", "tun", "lo", "p2p-dev")

    /** عناوين IPv4 المحلية القابلة للاستخدام (Wi-Fi / Hotspot / Wi-Fi Direct). */
    fun localIpv4(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && IGNORED_PREFIXES.none { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    fun info(context: Context): NetInfo {
        return try {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val type = when {
                caps == null -> NONE
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ETHERNET
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> CELLULAR
                else -> OTHER
            }
            var signal = -1
            if (type == WIFI) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val rssi = wm.connectionInfo?.rssi ?: -127
                signal = when {
                    rssi > -55 -> 4
                    rssi > -65 -> 3
                    rssi > -75 -> 2
                    rssi > -85 -> 1
                    else -> 0
                }
            }
            NetInfo(type, signal)
        } catch (e: Exception) {
            NetInfo()
        }
    }
}
