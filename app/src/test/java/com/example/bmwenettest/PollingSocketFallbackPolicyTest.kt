package com.example.bmwenettest

import com.example.bmwenettest.PollingSocketFallbackPolicy as P
import org.junit.Assert.*
import org.junit.Test

class PollingSocketFallbackPolicyTest {
    @Test fun unexpectedSocketFaultGetsBoundedRetryWithoutParallelReader() {
        val first=P.decide(true,false,1000L,0L,0)
        assertEquals(P.Action.RETRY,first.action)
        assertEquals(1,first.attempt)
        val second=P.decide(true,false,1100L,first.windowStart,first.attempt)
        assertEquals(P.Action.RETRY,second.action)
        assertEquals(2,second.attempt)
        val third=P.decide(true,false,1200L,second.windowStart,second.attempt)
        assertEquals(P.Action.FAIL,third.action)
    }

    @Test fun successfulQuietWindowRestartsAttemptBudget() {
        val decision=P.decide(true,false,301_001L,1000L,2)
        assertEquals(P.Action.RETRY,decision.action)
        assertEquals(1,decision.attempt)
        assertEquals(301_001L,decision.windowStart)
    }

    @Test fun expectedUserStopOrInactiveLoggerCannotReconnect() {
        assertEquals(P.Action.STOP,P.decide(false,false,1000L,0L,0).action)
        assertEquals(P.Action.STOP,P.decide(true,true,1000L,0L,0).action)
    }

    @Test fun monotonicClockResetStartsNewWindowSafely() {
        val decision=P.decide(true,false,100L,1000L,2)
        assertEquals(P.Action.RETRY,decision.action)
        assertEquals(1,decision.attempt)
    }
}
