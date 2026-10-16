package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

/**
 * Deterministic virtual monotonic clock; delays approximate real serialized
 * SLOW OBD/UDS response cost. No ECU socket or fake timestamps claimed as real.
 */
class AutomatedDmeSlowIntegrationTest {
    @Test fun sixStagesCompleteDespiteCoreZeroBudgetAndRealisticSlowResponseDelays() {
        val started=1_000L
        val suite=AutomatedDmeBenchmark(started)
        val slow=SlowPollScheduler()
        var now=started
        var stageId=-1
        var cyclesInStage=0L
        var previousCycleMs=170L
        var safetyReadsWithZeroBudget=0
        var speedQueries=0
        var coolantQueries=0
        var maxSpeedAge=0L
        var maxCoolantAge=0L
        var totalCycles=0
        val observed=mutableSetOf<Int>()
        while(!suite.terminal && totalCycles<14_000) {
            val stage=suite.current ?:fail("missing stage")
            if(stage.id!=stageId) {
                stageId=stage.id
                observed.add(stage.id)
                slow.reset() // real Service resets on EVERY profile boundary
                cyclesInStage=0
                previousCycleMs=170L
            }
            val coreBudget=HighSpeedProfilePolicy.optionalBudget(
                stage.profile,cyclesInStage,previousCycleMs,false)
            val due=slow.dueForBenchmark(now,coreBudget)
            assertTrue("one socket owner: <=2 SLOW exchanges",due.size<=2)
            if(coreBudget==0 && due.any{it==0x0D || it==0x05})
                safetyReadsWithZeroBudget++
            val cycleStart=now
            // One diagnostic request/response at a time; each consumes
            // 65..170ms, including realistic SLOW latency and scheduler jitter.
            for(pid in due) {
                val observedDelay=when(pid) {
                    0x0D -> 95L
                    0x05 -> 145L
                    0x5728 -> 170L
                    else -> 65L+((pid.toLong()+totalCycles)%4L)*25L
                }
                now+=observedDelay
                slow.record(pid,now,true)
                if(pid==0x0D)speedQueries++
                if(pid==0x05)coolantQueries++
            }
            now+=90L+((totalCycles%4)*15L)
            val speedAge=slow.lastValidAgeMs(0x0D,now)
            val coolantAge=slow.lastValidAgeMs(0x05,now)
            assertNotNull("guard 0x0D must be polled before first FAST",speedAge)
            assertNotNull("guard 0x05 must be polled before first FAST",coolantAge)
            maxSpeedAge=maxOf(maxSpeedAge,speedAge!!)
            maxCoolantAge=maxOf(maxCoolantAge,coolantAge!!)
            assertTrue("speed guard must not expire",speedAge<=
                AutomatedDmeBenchmark.MAX_SPEED_AGE_MS)
            assertTrue("coolant guard must not expire",coolantAge<=
                AutomatedDmeBenchmark.MAX_COOLANT_AGE_MS)
            assertTrue(slow.fresh(0x0D,now))
            assertTrue(slow.fresh(0x05,now))
            val cycleMs=now-cycleStart
            val result=suite.onCycle(AutomatedDmeBenchmark.Cycle(
                nowMs=now,wallMs=1_700_000_000_000L+now,
                speedKmh=0.0,speedAgeMs=speedAge,
                coolantC=91.0,coolantAgeMs=coolantAge,rpm=800.0,
                actualMode=stage.pollMode,actualProfile=stage.profile,
                epoch=0,fastComplete=true,hostSkewMs=12L,
                cycleMs=cycleMs,uiVisible=!stage.background,
                csvLost=0L))
            assertNotEquals("unexpected safe-stop in stage ${stage.id}",
                "ABORT",result?.type)
            previousCycleMs=cycleMs
            totalCycles++
            cyclesInStage++
        }
        assertEquals("all 6 stages must finish within bounded fake time",
            AutomatedDmeBenchmark.State.COMPLETED,suite.state)
        assertEquals((1..6).toSet(),observed)
        assertEquals(6,suite.reports.size)
        assertTrue("CORE zero budget must still poll guards",safetyReadsWithZeroBudget>4)
        assertTrue(speedQueries>100)
        assertTrue(coolantQueries>30)
        assertTrue(maxSpeedAge<=5_000L)
        assertTrue(maxCoolantAge<=16_000L)
        assertTrue(suite.reports.all{it.complete==it.cycles && it.cycles>100})
        assertTrue(suite.reports.last().backgroundCycles>0)
        assertTrue("repeat CORE must have comparable complete sets",
            suite.reports[1].completeness==suite.reports[4].completeness)
    }

    @Test fun failedReplyCannotBeInterpretedAsParkedAndReasonRetainsExactAge() {
        val suite=AutomatedDmeBenchmark(1_000L)
        val scheduler=SlowPollScheduler()
        scheduler.dueForBenchmark(1_000L,0)
        scheduler.record(0x0D,1_100L,true)
        scheduler.record(0x05,1_250L,true)
        fun cycle(t:Long,speed:Double?,age:Long?)=AutomatedDmeBenchmark.Cycle(
            t,1_700_000_000_000L+t,speed,age,90.0,
            scheduler.lastValidAgeMs(0x05,t),800.0,"B+",
            HighSpeedProfilePolicy.Profile.LEGACY,0,true,12L,120L,true,0)
        assertEquals("START",suite.onCycle(cycle(1_500L,0.0,400L))?.type)
        // A malformed SPEED response arrives after initial success:
        // fail closed, but preserve the last valid age for the stop log.
        scheduler.record(0x0D,4_100L,false)
        val age=scheduler.lastValidAgeMs(0x0D,4_120L)
        val event=suite.onCycle(cycle(4_120L,null,age))
        assertEquals("ABORT",event?.type)
        assertTrue(event!!.summary.contains("SPEED_MISSING"))
        assertTrue(event.summary.contains("speed_age_ms=3020"))
        assertTrue(event.summary.contains("coolant_age_ms=2870"))
        assertTrue(suite.reportText().contains("SPEED_MISSING"))
        assertTrue(suite.csvRow("ABORT",4_120L,777L,event.summary)
            .contains("speed_age_ms=3020"))
    }

    @Test fun staleGuardAbortsWithRawAgeInsteadOfDroppingToUnknown() {
        val suite=AutomatedDmeBenchmark(1_000L)
        val profile=HighSpeedProfilePolicy.Profile.LEGACY
        val first=AutomatedDmeBenchmark.Cycle(2_000L,2_000L,0.0,100L,90.0,
            100L,800.0,"B+",profile,0,true,10L,100L,true,0L)
        assertEquals("START",suite.onCycle(first)?.type)
        val stale=first.copy(nowMs=7_101L,speedKmh=null,
            speedAgeMs=5_201L,coolantAgeMs=700L)
        val outcome=suite.onCycle(stale)
        assertEquals("ABORT",outcome?.type)
        assertTrue(outcome!!.summary.contains("SPEED_STALE"))
        assertTrue(outcome.summary.contains("speed_age_ms=5201"))
        assertTrue(outcome.summary.contains("coolant_age_ms=700"))
    }
}
