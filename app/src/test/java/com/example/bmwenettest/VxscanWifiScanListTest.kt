package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class VxscanWifiScanListTest {
    private fun ap(ssid:String,bssid:String,dbm:Int,cap:String="[WPA2-PSK-CCMP][ESS]")=
        VxscanWifiScanList.AccessPoint(ssid,bssid,dbm,2412,cap)

    @Test fun arbitrarySsidNamesAreAcceptedWithoutVxscanPrefixes() {
        val entries=VxscanWifiScanList.entries(listOf(
            ap("MHD ENET_5143","11:22:33:44:55:66",-40),
            ap("BMW_OBD","11:22:33:44:55:77",-70),
            ap("Home AP","12:34:56:78:90:AB",-65)
        ))
        assertEquals(3,entries.size)
        assertEquals("MHD ENET_5143",entries.first().ssid)
    }
    @Test fun separateBssidsWithSameSsidRemainIndividuallySelectable() {
        val entries=VxscanWifiScanList.entries(listOf(
            ap("MHD ENET_5143","11:22:33:44:55:66",-40),
            ap("MHD ENET_5143","11:22:33:44:55:77",-55),
            ap("MHD ENET_5143","11:22:33:44:55:66",-90)
        ))
        assertEquals(2,entries.size)
    }
    @Test fun malformedHiddenAndRedactedNetworksAreDiscarded() {
        assertEquals(1,VxscanWifiScanList.entries(listOf(
            ap("","11:22:33:44:55:66",-30),
            ap("Valid","02:00:00:00:00:00",-40),
            ap("Correct","11:22:33:44:55:66",-60)
        )).size)
    }
    @Test fun scanClassifiesSecurityWithoutPretendingEveryNetworkIsAnAdapter() {
        assertEquals("WPA3/SAE",VxscanWifiScanList.security("[SAE][ESS]"))
        assertEquals("WPA2/PSK",VxscanWifiScanList.security("[WPA2-PSK-CCMP][ESS]"))
        assertEquals("Открытая",VxscanWifiScanList.security("[ESS]"))
        assertTrue(VxscanWifiScanList.label(ap("AnyWifi","11:22:33:44:55:66",-52))
            .contains("AnyWifi"))
    }
}
