package com.example.bmwenettest

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.MacAddress
import android.net.wifi.WifiInfo
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import java.net.Inet4Address

/**
 * Opt-in Android local-only Wi-Fi network reservation for VXSCAN.
 *
 * Never binds the process to Wi-Fi, never changes default routing, never
 * places raw credentials in state. Only the diagnostic owner uses this Network.
 * The lifetime is explicitly ended at LOGGER STOP / Service destruction.
 */
internal object VxscanLocalOnlyWifi {
    const val ACTION_STATUS="com.example.bmwenettest.VXSCAN_LOCAL_ONLY_STATUS"
    enum class Phase { OFF, REQUESTING, WAITING_IP, READY, UNAVAILABLE, LOST, ERROR }
    data class Snapshot(
        val phase:Phase,
        val ssid:String?=null,
        val network:Network?=null,
        val detail:String=""
    ) {
        val enabled:Boolean get()=phase!=Phase.OFF
    }
    private val lock=Any()
    @Volatile private var state=Snapshot(Phase.OFF)
    private var requestOwner:ConnectivityManager?=null
    private var requestCallback:ConnectivityManager.NetworkCallback?=null
    private var generation=0

    @Volatile private var uiVisible=false

    fun setUiVisible(visible:Boolean) { uiVisible=visible }
    fun releaseUnlessUiVisible(context:Context) {
        if(VxscanLocalOnlyPolicy.releaseAfterStop(uiVisible))stop(context)
    }
    // Prevent fallback to ordinary Wi-Fi during a STOP/START transition,
    // even when the request callback has temporarily been released.
    fun reservationRequired(context:Context):Boolean =
        VxscanWifiCredentials.enabled(context) || state.enabled

    fun snapshot():Snapshot=state

    /** Immutable for the purpose of fail-closed routing within one transaction. */
    fun reservedNetwork():Network?=state.takeIf { it.phase==Phase.READY }?.network

    private fun publish(context:Context,token:Int,next:Snapshot) {
        val valid=synchronized(lock) {
            if(generation!=token)false else {
                state=next
                true
            }
        }
        if(valid) notifyChanged(context)
    }

    private fun notifyChanged(context:Context) {
        context.sendBroadcast(Intent(ACTION_STATUS).setPackage(context.packageName))
    }

    /**
     * Called from an explicit user gesture or an opt-in stored profile after
     * the user launches the application. This object never persists secrets.
     */
    fun start(context:Context,ssid:String,password:String,bssid:String?=null):String? {
        if(Build.VERSION.SDK_INT<Build.VERSION_CODES.Q)
            return "Local-only Wi-Fi требует Android 10 или новее"
        val name=ssid.trim()
        val error=VxscanLocalOnlyPolicy.validate(name,password)
        if(error!=null)return error
        val bssidError=VxscanLocalOnlyPolicy.validateBssid(bssid)
        if(bssidError!=null)return bssidError
        val app=context.applicationContext
        val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return "ConnectivityManager недоступен"
        val request=try {
            val builder=WifiNetworkSpecifier.Builder().setSsid(name)
            if(bssid!=null) builder.setBssid(MacAddress.fromString(bssid))
            if(password.isNotEmpty()) builder.setWpa2Passphrase(password)
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(builder.build())
                .build()
        }catch(e:IllegalArgumentException) {
            return "Неверное имя Wi-Fi или пароль"
        }
        val token:Int
        val callback:ConnectivityManager.NetworkCallback
        synchronized(lock) {
            if(state.enabled)return "Отключи предыдущий local-only запрос перед новым"
            generation++
            token=generation
            state=Snapshot(Phase.REQUESTING,name,null,
                "Ожидание подтверждения Android для локальной сети")
            callback=object:ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network:Network) {
                    publish(app,token,Snapshot(Phase.WAITING_IP,name,null,
                        "Wi-Fi найден, ожидается IPv4 адрес"))
                }
                override fun onCapabilitiesChanged(network:Network,caps:NetworkCapabilities) {
                    val info=caps.transportInfo as? WifiInfo
                    val observed=info?.bssid
                    if(observed!=null &&
                        VxscanLocalOnlyPolicy.validateBssid(observed)==null)
                        VxscanWifiCredentials.rememberBssid(app,name,observed)
                    if(!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        publish(app,token,Snapshot(Phase.ERROR,name,null,
                            "Android не предоставил отдельную local-only Wi-Fi сеть"))
                    }
                }
                override fun onLinkPropertiesChanged(network:Network,properties:LinkProperties) {
                    val hasIpv4=properties.linkAddresses.any {
                        it.address is Inet4Address && !it.address.isLoopbackAddress
                    }
                    if(hasIpv4) {
                        publish(app,token,Snapshot(Phase.READY,name,network,
                            "Локальная Wi-Fi сеть готова; интернет других приложений требует проверки"))
                    } else {
                        publish(app,token,Snapshot(Phase.WAITING_IP,name,null,
                            "Подключено без IPv4 адреса; LOGGER заблокирован"))
                    }
                }
                override fun onLost(network:Network) {
                    publish(app,token,Snapshot(Phase.LOST,name,null,
                        "VXSCAN Wi-Fi потерян; другой Wi-Fi не подставляется"))
                }
                override fun onUnavailable() {
                    publish(app,token,Snapshot(Phase.UNAVAILABLE,name,null,
                        "Android отклонил или не установил local-only соединение"))
                }
            }
            requestOwner=manager
            requestCallback=callback
        }
        notifyChanged(app)
        try {
            // Expiration covers unanswered prompts; user can disable manually.
            manager.requestNetwork(request,callback,45_000)
        }catch(e:Exception) {
            publish(app,token,Snapshot(Phase.ERROR,name,null,
                "Ошибка Android local-only запроса: "+e.javaClass.simpleName))
            // Keep the blocked state; user can explicitly return to normal Wi-Fi.
        }
        return null
    }

    fun stop(context:Context) {
        val owner:ConnectivityManager?
        val callback:ConnectivityManager.NetworkCallback?
        synchronized(lock) {
            generation++ // Ignore all queued callbacks from previous requests.
            owner=requestOwner
            callback=requestCallback
            requestOwner=null
            requestCallback=null
            state=Snapshot(Phase.OFF)
        }
        if(owner!=null && callback!=null) {
            try { owner.unregisterNetworkCallback(callback) } catch(_:Exception) {}
        }
        notifyChanged(context.applicationContext)
    }
}

/** Deterministic validation and fail-closed routing decision, JVM-testable. */
internal object VxscanLocalOnlyPolicy {
    fun validateBssid(bssid:String?):String? {
        if(bssid==null)return null
        val known=Regex("(?i)^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
        if(!known.matches(bssid) || bssid.equals("02:00:00:00:00:00",true) ||
            bssid.equals("00:00:00:00:00:00",true))
            return "BSSID должен быть точным MAC-адресом точки доступа (XX:XX:XX:XX:XX:XX)"
        return null
    }
    fun shouldAutoRequest(optedIn:Boolean,activeRequest:Boolean):Boolean =
        optedIn && !activeRequest
    fun validate(ssid:String,password:String):String? {
        if(ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size>32 ||
            ssid.any { it.isISOControl() })
            return "Укажи точный SSID VXSCAN (1–32 байта)"
        if(password.isNotEmpty() && (password.length !in 8..63 ||
            password.any { it.isISOControl() }))
            return "Для WPA2 нужен пароль длиной 8–63 символа; пустой — открытая сеть"
        return null
    }

    fun releaseAfterStop(uiVisible:Boolean):Boolean = !uiVisible
    /** Missing Wi-Fi/UDP discovery must never downgrade B+ as a TCP polling fault. */
    fun countsAsPollFailure(stage:String):Boolean=stage=="POLL"
    fun shouldBlockWithoutReady(autoProfile:Boolean,requestActive:Boolean,ready:Boolean):Boolean =
        (autoProfile || requestActive) && !ready

    enum class Choice { NORMAL, RESERVED, BLOCKED }
    fun choice(enabled:Boolean,ready:Boolean):Choice=when {
        !enabled -> Choice.NORMAL
        ready -> Choice.RESERVED
        else -> Choice.BLOCKED
    }
}
