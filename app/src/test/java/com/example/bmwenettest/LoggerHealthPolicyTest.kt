package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class LoggerHealthPolicyTest {
    private val P=LoggerHealthPolicy
    @Test fun freshDmeRequiresRealValidCompletedCycle() {
        val started=P.start(1000)
        assertFalse(P.freshness(true,started.lastValidAtMs,1100))
        var state=P.connected(started,1500,0)
        state=P.cycleStarted(state,1600)
        state=P.cycleFinished(state,1800,true)
        assertTrue(P.freshness(true,state.lastValidAtMs,6800))
        assertFalse(P.freshness(true,state.lastValidAtMs,6801))
        assertFalse(P.freshness(false,state.lastValidAtMs,1801))
        val noValid=P.cycleFinished(P.cycleStarted(state,1900),2100,false)
        assertEquals(1800,noValid.lastValidAtMs)
    }
    @Test fun watchdogCannotResetDuringNetworkDiscoveryOrShutdown() {
        assertEquals(P.Action.NONE,P.action(P.start(1000),99_000))
        assertEquals(P.Action.NONE,P.action(
            P.Snapshot(P.Phase.OFF,1000),99_000))
    }
    @Test fun stallsDetectedAtBoundariesAndOneSocketResetIsOnlyPolicyAction() {
        val inCycle=P.cycleStarted(P.connected(P.start(1000),1100,3),1200)
        assertEquals(P.Action.NONE,P.action(inCycle,9199))
        assertEquals(P.Action.WARN,P.action(inCycle,9200))
        assertEquals(P.Action.WARN,P.action(inCycle,19199))
        assertEquals(P.Action.CLOSE_SOCKET,P.action(inCycle,19200))
        assertEquals(3,inCycle.epoch)
        val finished=P.cycleFinished(inCycle,19300,true)
        assertEquals(P.Action.NONE,P.action(finished,19301))
        assertEquals(P.Action.CLOSE_SOCKET,P.action(finished,37300))
    }
    @Test fun reconnectDoesNotCarryOldFreshStatusAsCurrent() {
        val state=P.cycleFinished(P.cycleStarted(P.start(1000),1100),1400,true)
        val reconnect=P.reconnecting(state,2500)
        assertEquals(P.Phase.CONNECTING,reconnect.phase)
        assertEquals(P.Action.NONE,P.action(reconnect,200000))
    }
}
