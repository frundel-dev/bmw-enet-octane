package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class BenchmarkSlowSafetyTest {
    @Test fun speedAndCoolantAreQueriedEvenWhenCoreBudgetIsZero() {
        val scheduler=SlowPollScheduler()
        assertEquals(listOf(0x0D,0x05),scheduler.dueForBenchmark(1_000L,0))
        assertTrue(scheduler.dueForBenchmark(1_100L,0).isEmpty())
        assertEquals(listOf(0x0D),scheduler.dueForBenchmark(2_500L,0))
        assertEquals(listOf(0x0D,0x05),scheduler.dueForBenchmark(7_000L,0))
    }

    @Test fun benchmarkNeverExceedsTwoSerializedSlowRequestsPerCycle() {
        val scheduler=SlowPollScheduler()
        val first=scheduler.dueForBenchmark(1_000L,2)
        assertEquals(listOf(0x0D,0x05),first)
        val second=scheduler.dueForBenchmark(1_100L,2)
        assertEquals(2,second.size)
        assertFalse(second.contains(0x0D))
        assertFalse(second.contains(0x05))
        for(t in 1_200L..30_000L step 120L) {
            val due=scheduler.dueForBenchmark(t,2)
            assertTrue(due.size<=2)
            assertEquals(due.size,due.distinct().size)
        }
    }

    @Test fun invalidGuardNeverCountsAsFreshButAgeExplainsStop() {
        val s=SlowPollScheduler()
        s.dueForBenchmark(1_000L,0)
        s.record(0x0D,1_050L,true)
        assertEquals(4_999L,s.lastValidAgeMs(0x0D,6_049L))
        assertTrue(s.fresh(0x0D,6_050L))
        assertFalse(s.fresh(0x0D,6_051L))
        assertEquals(5_001L,s.lastValidAgeMs(0x0D,6_051L))
        s.record(0x0D,6_100L,false)
        assertFalse(s.fresh(0x0D,6_100L))
        assertNull(s.ageMs(0x0D,6_100L))
        assertEquals(5_050L,s.lastValidAgeMs(0x0D,6_100L))
        s.reset()
        assertNull(s.lastValidAgeMs(0x0D,6_200L))
    }

    @Test fun optionalBudgetCannotStarveCoolantUnderHeavyFastLoad() {
        val s=SlowPollScheduler()
        val observed=mutableSetOf<Int>()
        for(t in 1_000L..31_000L step 100L) {
            val budget=if((t/100L)%2L==0L)0 else 1
            val due=s.dueForBenchmark(t,budget)
            observed.addAll(due)
            for(id in due) s.record(id,t+25L,true)
            assertTrue(due.size<=2)
            if(t>7_100L) {
                assertTrue(s.lastValidAgeMs(0x0D,t)!!<=1_525L)
                assertTrue(s.lastValidAgeMs(0x05,t)!!<=6_025L)
            }
        }
        assertTrue(0x0D in observed && 0x05 in observed)
        assertTrue(observed.any{it!=0x0D && it!=0x05})
    }
}
