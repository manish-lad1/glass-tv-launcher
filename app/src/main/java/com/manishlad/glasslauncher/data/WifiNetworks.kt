package com.manishlad.glasslauncher.data

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager

data class WifiNetwork(val ssid: String, val level: Int, val secured: Boolean, val connected: Boolean)

data class WifiSnapshot(
    /** Name of the connected Wi-Fi network, or null (Ethernet, offline, or no location permission). */
    val connectedSsid: String?,
    val onEthernet: Boolean,
    val online: Boolean,
    /** Nearby networks, connected first, then by signal. Empty without the location permission. */
    val networks: List<WifiNetwork>,
    val hasPermission: Boolean,
)

/**
 * Nearby Wi-Fi networks and the connected one. Android 10+ needs ACCESS_FINE_LOCATION (granted
 * via adb, see README) even for the connected network's name. Apps can't switch networks, so
 * choosing one opens the TV's own Wi-Fi settings.
 */
object WifiNetworks {
    fun requestScan(context: Context) {
        @Suppress("DEPRECATION")
        runCatching { context.applicationContext.getSystemService(WifiManager::class.java)?.startScan() }
    }

    @SuppressLint("MissingPermission")
    fun snapshot(context: Context): WifiSnapshot {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val onEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val connected = if (onWifi && granted) wm?.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } else null
        val networks = if (!granted || wm == null) emptyList() else runCatching {
            wm.scanResults
                .filter { !it.SSID.isNullOrBlank() }
                .groupBy { it.SSID }
                .map { (ssid, results) ->
                    val best = results.maxBy { it.level }
                    @Suppress("DEPRECATION")
                    WifiNetwork(
                        ssid = ssid,
                        level = WifiManager.calculateSignalLevel(best.level, 4),
                        secured = listOf("WEP", "WPA", "SAE", "EAP").any { best.capabilities.contains(it) },
                        connected = ssid == connected,
                    )
                }
                .sortedWith(compareBy({ !it.connected }, { -it.level }, { it.ssid.lowercase() }))
        }.getOrDefault(emptyList())
        // The connected network may be missing from a stale scan; make sure it's listed.
        val withConnected = if (connected != null && networks.none { it.connected }) listOf(WifiNetwork(connected, 3, true, true)) + networks else networks
        return WifiSnapshot(connected, onEthernet, online, withConnected, granted)
    }
}
