package com.example.ipwebcam.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

data class NetworkIpInfo(
    val type: String,
    val ip: String,
    val interfaceName: String
)

object NetworkUtils {

    /**
     * Mendapatkan semua alamat IPv4 lokal aktif beserta jenis koneksinya (Wi-Fi, Hotspot HP, USB Tethering, Ethernet).
     */
    fun getAllIpAddresses(): List<NetworkIpInfo> {
        val list = mutableListOf<NetworkIpInfo>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (networkInterface in interfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val hostAddress = address.hostAddress ?: continue
                        val name = networkInterface.name.lowercase()

                        val type = when {
                            name.startsWith("wlan") -> "Wi-Fi"
                            name.startsWith("ap") || name.startsWith("softap") -> "Hotspot HP"
                            name.startsWith("rndis") || name.startsWith("usb") -> "USB Tethering"
                            name.startsWith("eth") -> "Ethernet"
                            else -> "Network (${networkInterface.name})"
                        }

                        list.add(NetworkIpInfo(type, hostAddress, networkInterface.name))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    /**
     * Mendapatkan alamat IPv4 lokal utama perangkat.
     */
    fun getLocalIpAddress(context: Context): String {
        val ips = getAllIpAddresses()
        if (ips.isEmpty()) return "127.0.0.1"

        // Prioritaskan Wi-Fi, lalu Hotspot HP, lalu USB
        val wifi = ips.firstOrNull { it.type == "Wi-Fi" }
        if (wifi != null) return wifi.ip

        val hotspot = ips.firstOrNull { it.type == "Hotspot HP" }
        if (hotspot != null) return hotspot.ip

        val usb = ips.firstOrNull { it.type == "USB Tethering" }
        if (usb != null) return usb.ip

        return ips.first().ip
    }

    /**
     * Memeriksa apakah perangkat terhubung ke jaringan lokal.
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
