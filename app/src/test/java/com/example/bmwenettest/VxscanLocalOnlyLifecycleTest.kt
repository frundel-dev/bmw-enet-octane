package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class VxscanLocalOnlyLifecycleTest {
    @Test fun serviceStopKeepsReservationOnlyWhenUiIsVisible() {
        assertFalse(VxscanLocalOnlyPolicy.releaseAfterStop(true))
        assertTrue(VxscanLocalOnlyPolicy.releaseAfterStop(false))
    }
    @Test fun noFastFallbackUntilConnectionHasEnteredActivePolling() {
        for(stage in listOf("NETWORK","FAST_TCP","DISCOVERY","TCP"))
            assertFalse(VxscanLocalOnlyPolicy.countsAsPollFailure(stage))
        assertTrue(VxscanLocalOnlyPolicy.countsAsPollFailure("POLL"))
    }

    @Test fun savedProfileNeverFallsBackToUnrelatedWifiBetweenStopAndStart() {
        assertTrue(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(true,false,false))
        assertFalse(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(true,false,true))
        assertTrue(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(false,true,false))
        assertFalse(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(false,false,false))
    }
    @Test fun foregroundReRequestOnlyIfApprovedWifiRequestIsTerminal() {
        val terminal=listOf(
            VxscanLocalOnlyWifi.Phase.LOST,
            VxscanLocalOnlyWifi.Phase.UNAVAILABLE,
            VxscanLocalOnlyWifi.Phase.ERROR
        )
        val ongoing=listOf(
            VxscanLocalOnlyWifi.Phase.OFF,
            VxscanLocalOnlyWifi.Phase.REQUESTING,
            VxscanLocalOnlyWifi.Phase.WAITING_IP,
            VxscanLocalOnlyWifi.Phase.READY
        )
        terminal.forEach {
            assertTrue(VxscanLocalOnlyPolicy.needsForegroundRerequest(it))
        }
        ongoing.forEach {
            assertFalse(VxscanLocalOnlyPolicy.needsForegroundRerequest(it))
        }
    }

}
