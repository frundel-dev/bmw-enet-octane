package com.example.bmwenettest

import java.util.Locale

/**
 * Presentation-only Wi-Fi scan result filtering.
 * A Wi-Fi SSID is not proof the AP is an ENET/VXSCAN adapter.
 * Never auto-connect to discovered APs; user must explicitly select one.
 */
internal object VxscanWifiScanList {
    data class AccessPoint(
        val ssid:String,val bssid:String,val dbm:Int,val frequencyMhz:Int,
        val capabilities:String
    )
    fun entries(scan:List<AccessPoint>):List<AccessPoint> =
        scan.asSequence().filter { ap ->
            VxscanLocalOnlyPolicy.validate(ap.ssid,"")==null &&
                VxscanLocalOnlyPolicy.validateBssid(ap.bssid)==null &&
                ap.frequencyMhz>0
        }.distinctBy { it.ssid to it.bssid.uppercase(Locale.US) }
            .sortedWith(compareByDescending<AccessPoint> { it.dbm }
                .thenBy { it.ssid.lowercase(Locale.ROOT) }).take(30).toList()

    fun security(capabilities:String):String {
        val caps=capabilities.uppercase(Locale.US)
        return when {
            "WPA3" in caps || "SAE" in caps -> "WPA3/SAE"
            "WPA2" in caps || "PSK" in caps -> "WPA2/PSK"
            "OWE" in caps -> "OWE"
            "WEP" in caps -> "WEP"
            "EAP" in caps -> "Enterprise"
            else -> "Открытая"
        }
    }
    fun label(ap:AccessPoint):String =
        ap.ssid+" • "+ap.dbm+" dBm • "+ap.frequencyMhz+" MHz • "+
            security(ap.capabilities)+"\n"+ap.bssid
}
