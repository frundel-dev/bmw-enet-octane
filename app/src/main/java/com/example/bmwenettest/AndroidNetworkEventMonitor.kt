package com.example.bmwenettest

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap

/**
 * Passive Android connectivity diagnostics for comparing outgoing calls with ENET drops.
 * Observes Wi-Fi (including unvalidated VXSCAN), Ethernet and the phone's default route.
 * Does not change system network selection or request telephony permissions.
 */
class AndroidNetworkEventMonitor(
    private val connectivity: ConnectivityManager,
    private val log: (event: String, transport: String, detail: String) -> Unit
) {
    @Volatile private var active = false
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    private fun transportName(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        else -> "OTHER"
    }

    private fun capabilitySummary(caps: NetworkCapabilities): String =
        "transport=${transportName(caps)}" +
        ";internet=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)}" +
        ";validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}" +
        ";not_metered=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)}"

    private fun linkSummary(lp: LinkProperties): String {
        val ipv4 = lp.linkAddresses
            .filter { it.address is Inet4Address }
            .joinToString("|") { "${it.address.hostAddress}/${it.prefixLength}" }
        val gateways = lp.routes
            .filter { it.isDefaultRoute }
            .joinToString("|") { it.gateway?.hostAddress ?: "none" }
        return "iface=${lp.interfaceName ?: "-"};ipv4=$ipv4;gateways=$gateways"
    }

    private fun callback(scope: String): ConnectivityManager.NetworkCallback {
        val lastCaps = ConcurrentHashMap<Network, String>()
        val lastLinks = ConcurrentHashMap<Network, String>()

        fun record(event: String, network: Network, detail: String = "") {
            if (active) log(event, scope, "net=$network" + if (detail.isEmpty()) "" else ";$detail")
        }
        return object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                record("AVAILABLE", network)
            }
            override fun onLost(network: Network) {
                lastCaps.remove(network); lastLinks.remove(network)
                record("LOST", network)
            }
            override fun onLosing(network: Network, maxMsToLive: Int) {
                record("LOSING", network, "max_ms=$maxMsToLive")
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val details = capabilitySummary(caps)
                if (lastCaps.put(network, details) != details) {
                    record("CAPABILITIES", network, details)
                }
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val details = linkSummary(properties)
                if (lastLinks.put(network, details) != details) {
                    record("LINK_PROPERTIES", network, details)
                }
            }
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                record("BLOCKED", network, "blocked=$blocked")
            }
            override fun onUnavailable() {
                if (active) log("UNAVAILABLE", scope, "no matching network")
            }
        }
    }

    fun start() {
        if (active) return
        active = true
        log("MONITOR_START", "ANDROID", "default=${connectivity.activeNetwork ?: "none"}")
        fun register(scope: String, operation: (ConnectivityManager.NetworkCallback) -> Unit) {
            val cb = callback(scope)
            try {
                operation(cb)
                callbacks.add(cb)
            } catch (e: Exception) {
                log("MONITOR_ERROR", scope, "${e.javaClass.simpleName}: ${e.message ?: "unknown"}")
            }
        }
        register("WIFI") { cb ->
            // Does not require a validated Internet network: VXSCAN often has no Internet.
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            connectivity.registerNetworkCallback(request, cb)
        }
        register("ETHERNET") { cb ->
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET).build()
            connectivity.registerNetworkCallback(request, cb)
        }
        register("DEFAULT") { cb -> connectivity.registerDefaultNetworkCallback(cb) }
    }

    fun stop() {
        if (!active) return
        active = false
        callbacks.forEach { cb ->
            try { connectivity.unregisterNetworkCallback(cb) } catch (_: Exception) { }
        }
        callbacks.clear()
        log("MONITOR_STOP", "ANDROID", "callbacks unregistered")
    }
}
