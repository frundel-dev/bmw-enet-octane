package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class SlowPollSchedulerTest {
    @Test fun capsTwoSlowRequestsPerFastCycleAndPrioritizesUrgentOnStartup() {
        val s=SlowPollScheduler()
        assertEquals(listOf(0x0D,0x5728),s.due(1000))
        assertEquals(listOf(0x11,0x06),s.due(1010))
        assertEquals(listOf(0x0F,0x44),s.due(1020))
        assertEquals(listOf(0x0E,0x05),s.due(1030))
        assertEquals(listOf(0x5C,0x07),s.due(1040))
        assertTrue(s.due(1100).isEmpty())
    }

    @Test fun longIntervalParametersNeverDisappear() {
        val s=SlowPollScheduler()
        repeat(5) { i-> s.due(1000L+i*10L) }
        assertEquals(listOf(0x0D,0x5728),s.due(2500L))
        val requested=mutableSetOf<Int>()
        for(time in 3000L..31000L step 500L)requested.addAll(s.due(time))
        assertTrue("LTFT must be polled again",requested.contains(0x07))
        assertTrue("Oil must be polled again",requested.contains(0x5C))
    }

    @Test fun missingOrFailedReadBecomesStale() {
        val s=SlowPollScheduler()
        assertFalse(s.fresh(0x0F,2000))
        s.record(0x0F,2000,true)
        assertTrue(s.fresh(0x0F,11000))
        assertFalse(s.fresh(0x0F,11001))
        s.record(0x0F,11500,false)
        assertFalse(s.fresh(0x0F,11500))
        s.record(0x07,1000,true)
        assertTrue(s.fresh(0x07,56000))
        assertFalse(s.fresh(0x07,56001))
        s.reset()
        assertFalse(s.fresh(0x07,1200))
    }

    @Test fun rejectedReadIsNotRetriedEveryCycle() {
        val s=SlowPollScheduler()
        assertEquals(listOf(0x0D,0x5728),s.due(1000))
        s.record(0x5728,1200,false)
        assertFalse(s.due(1100).contains(0x5728))
        assertTrue(s.due(2500).contains(0x5728))
    }
}
