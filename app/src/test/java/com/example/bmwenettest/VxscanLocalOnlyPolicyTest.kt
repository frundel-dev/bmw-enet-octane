package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class VxscanLocalOnlyPolicyTest {
    @Test fun ssidMustNotBeHardcodedOrConstrainedToVxscan() {
        for(ssid in listOf("VXSCAN_01","BMW-ENET","OBD-WIFI-7","Car diagnostic",
            "Scan_7A2B","BMW_AP")) {
            assertNull(VxscanLocalOnlyPolicy.validate(ssid,"12345678"))
        }
        assertNull(VxscanLocalOnlyPolicy.validate("Любой_адаптер","12345678"))
    }

    @Test fun rejectsInvalidNetworkNamesAndWeakWpa2() {
        assertNotNull(VxscanLocalOnlyPolicy.validate("","12345678"))
        assertNotNull(VxscanLocalOnlyPolicy.validate("a".repeat(33),"12345678"))
        assertNotNull(VxscanLocalOnlyPolicy.validate("Bad\nName","12345678"))
        assertNotNull(VxscanLocalOnlyPolicy.validate("BMW-ENET","1234567"))
        assertNotNull(VxscanLocalOnlyPolicy.validate("BMW-ENET","x".repeat(64)))
        assertNull(VxscanLocalOnlyPolicy.validate("BMW-ENET","")) // open AP
        assertNull(VxscanLocalOnlyPolicy.validate("BMW-ENET","x".repeat(63)))
    }

    @Test fun optionalBssidIsExactMacAndRejectsRedactedOrMalformed() {
        assertNull(VxscanLocalOnlyPolicy.validateBssid(null))
        assertNull(VxscanLocalOnlyPolicy.validateBssid("A2:BC:34:0D:56:EF"))
        assertNotNull(VxscanLocalOnlyPolicy.validateBssid("02:00:00:00:00:00"))
        assertNotNull(VxscanLocalOnlyPolicy.validateBssid("00:00:00:00:00:00"))
        assertNotNull(VxscanLocalOnlyPolicy.validateBssid("12:34:56"))
    }

    @Test fun autoRequestOnlyRunsWhenUserOptedInAndNoRequestActive() {
        assertTrue(VxscanLocalOnlyPolicy.shouldAutoRequest(true,false))
        assertFalse(VxscanLocalOnlyPolicy.shouldAutoRequest(true,true))
        assertFalse(VxscanLocalOnlyPolicy.shouldAutoRequest(false,false))
        assertFalse(VxscanLocalOnlyPolicy.shouldAutoRequest(false,true))
    }

    @Test fun reservedModeNeverFallsBackToDefaultWifiDuringHandshakeOrLoss() {
        assertEquals(VxscanLocalOnlyPolicy.Choice.NORMAL,
            VxscanLocalOnlyPolicy.choice(false,false))
        assertEquals(VxscanLocalOnlyPolicy.Choice.NORMAL,
            VxscanLocalOnlyPolicy.choice(false,true))
        assertEquals(VxscanLocalOnlyPolicy.Choice.BLOCKED,
            VxscanLocalOnlyPolicy.choice(true,false))
        assertEquals(VxscanLocalOnlyPolicy.Choice.RESERVED,
            VxscanLocalOnlyPolicy.choice(true,true))
    }
}
