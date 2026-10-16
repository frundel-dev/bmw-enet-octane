package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FastGuardPreflightTest {
    @Test fun stationaryBenchmarkRequiresFreshBothGuardSignalsBeforeFast() {
        val s=SlowPollScheduler()
        assertFalse(FastGuardPreflight.required(false,s,1000))
        assertTrue(FastGuardPreflight.required(true,s,1000))
        s.record(0x0D,1000,true)
        assertTrue(FastGuardPreflight.required(true,s,1000))
        s.record(0x05,1000,true)
        assertFalse(FastGuardPreflight.required(true,s,1000))
        assertTrue(FastGuardPreflight.required(true,s,6001))
    }

    @Test fun invalidReplyAndStageResetNeverReuseOldProtectiveSample() {
        val s=SlowPollScheduler()
        s.record(0x0D,1000,true)
        s.record(0x05,1000,true)
        s.record(0x0D,1100,false)
        assertTrue(FastGuardPreflight.required(true,s,1200))
        s.record(0x0D,1300,true)
        assertFalse(FastGuardPreflight.required(true,s,1300))
        s.reset()
        assertTrue(FastGuardPreflight.required(true,s,1300))
    }
}
