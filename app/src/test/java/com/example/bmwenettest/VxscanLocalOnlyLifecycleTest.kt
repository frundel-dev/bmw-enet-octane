package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class VxscanLocalOnlyLifecycleTest {
    @Test fun serviceStopKeepsReservationOnlyWhenUiIsVisible() {
        assertFalse(VxscanLocalOnlyPolicy.releaseAfterStop(true))
        assertTrue(VxscanLocalOnlyPolicy.releaseAfterStop(false))
    }
    @Test fun savedProfileNeverFallsBackToUnrelatedWifiBetweenStopAndStart() {
        assertTrue(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(true,false,false))
        assertFalse(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(true,false,true))
        assertTrue(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(false,true,false))
        assertFalse(VxscanLocalOnlyPolicy.shouldBlockWithoutReady(false,false,false))
    }
}
