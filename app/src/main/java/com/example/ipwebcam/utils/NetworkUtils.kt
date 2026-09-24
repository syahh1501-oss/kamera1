package com.example.ipwebcam.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    /**
     * Mendapatkan alamat IPv4 lokal perangkat (Wi-Fi, Hotspot / AP, Ethernet).
     */
    fun getLocalIpAddress(context: Context): String {
        try {
            // Coba periksa antarmuka jaringan aktif terlebih dahulu
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var wifiIp: String? = null
            var apIp: String? = null
            var ethIp: String? = null
            var fallbackIp: String? = null

            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue
                        val name = networkInterface.name.lowercase()

                        when {
                            name.startsWith("wlan") -> wifiIp = hostAddress
                            name.startsWith("ap") || name.startsWith("rndis") || name.startsWith("softap") -> apIp = hostAddress
                            name.startsWith("eth") -> ethIp = hostAddress
                            else -> fallbackIp = hostAddress
                        }
                    }
                }
            }

            return wifiIp ?: apIp ?: ethIp ?: fallbackIp ?: "127.0.0.1"
        } catch (e: Exception) {
            e.printStackTrace()
            return "127.0.0.1"
        }
    }

    /**
     * Memeriksa apakah perangkat terhubung ke jaringan lokal (Wi-Fi / Ethernet).
     */
    fun isConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
