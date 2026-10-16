package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class AutomatedDmeBenchmarkTest {
    private fun cycle(t:Long, speed:Double?=0.0,coolant:Double?=90.0,
        speedAge:Long?=100L,coolantAge:Long?=100L,
        mode:String="B+",profile:HighSpeedProfilePolicy.Profile=
            HighSpeedProfilePolicy.Profile.LEGACY,
        visible:Boolean=true,epoch:Int=0,complete:Boolean=true,
        csvLost:Long=0L
    ) = AutomatedDmeBenchmark.Cycle(t,1_700_000_000_000L+t,
        speed,speedAge,coolant,coolantAge,800.0,mode,profile,epoch,complete,30L,
        80L,visible,csvLost)

    @Test fun scheduleHasSixExactStagesAndRepeatedCore() {
        val s=AutomatedDmeBenchmark.stages
        assertEquals(6,s.size)
        assertEquals(listOf(120_000L,120_000L,120_000L,
            120_000L,120_000L,300_000L),s.map{it.durationMs})
        assertEquals("B+",s[0].pollMode)
        assertEquals("B",s[3].pollMode)
        assertEquals(s[1].profile,s[4].profile)
        assertTrue(s[5].background)
        assertEquals(900_000L,s.sumOf{it.durationMs})
    }

    @Test fun noFreshSpeedOrCoolantPreventsStart() {
        val b=AutomatedDmeBenchmark(1_000L)
        assertNull(b.onCycle(cycle(2_000L,speed=null)))
        assertNull(b.onCycle(cycle(3_000L,speedAge=5_001L)))
        assertNull(b.onCycle(cycle(4_000L,coolant=50.0)))
        assertEquals(AutomatedDmeBenchmark.State.WAITING,b.state)
        assertEquals("START",b.onCycle(cycle(5_000L))?.type)
    }

    @Test fun movingVehicleAbortsImmediatelyAndPreservesPartial() {
        val b=AutomatedDmeBenchmark(1_000L)
        b.onCycle(cycle(1_500L))
        b.onCycle(cycle(2_000L))
        assertEquals("ABORT",b.onCycle(cycle(2_500L,speed=1.0))?.type)
        assertTrue(b.terminal)
        assertEquals(1,b.reports.size)
        assertTrue(b.reportText().contains("speed"))
    }

    @Test fun missingSpeedDuringRunFailsClosed() {
        val b=AutomatedDmeBenchmark(1_000L)
        b.onCycle(cycle(1_500L))
        assertEquals("ABORT",b.onCycle(cycle(2_000L,speed=null))?.type)
    }

    @Test fun waitingWithoutEvidenceStopsInBoundedTime() {
        val b=AutomatedDmeBenchmark(1_000L)
        assertEquals("ABORT",b.onCycle(cycle(91_001L,speed=null))?.type)
        assertEquals(AutomatedDmeBenchmark.State.ABORTED,b.state)
        assertEquals(0,b.reports.size)
    }

    @Test fun modeFallbackIsNotMislabelledAsRequestedMode() {
        val b=AutomatedDmeBenchmark(1_000L)
        b.onCycle(cycle(1_500L))
        assertEquals("ABORT",b.onCycle(cycle(2_000L,mode="B"))?.type)
        assertTrue(b.failure!!.contains("fallback"))
    }

    @Test fun transitionsOnlyAtCompletedCycleBoundary() {
        val b=AutomatedDmeBenchmark(1_000L)
        b.onCycle(cycle(2_000L))
        val e=b.onCycle(cycle(122_100L))
        assertEquals("STAGE_END",e?.type)
        assertEquals(2,b.current?.id)
        assertEquals(1,b.reports.size)
        assertEquals(1,b.reports[0].cycles)
        val second=b.current!!
        assertEquals(HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,second.profile)
        assertEquals("ABORT",b.onCycle(cycle(122_200L,
            profile=HighSpeedProfilePolicy.Profile.LEGACY))?.type)
    }

    @Test fun completesAllSixWithoutClaimingContinuousBackground() {
        val b=AutomatedDmeBenchmark(1_000L)
        var now=2_000L
        b.onCycle(cycle(now))
        for((i,s) in AutomatedDmeBenchmark.stages.withIndex()) {
            if(i>0) b.onCycle(cycle(now+1L,mode=s.pollMode,
                profile=s.profile,visible=!s.background))
            now+=s.durationMs+1L
            val e=b.onCycle(cycle(now,mode=s.pollMode,profile=s.profile,
                visible=!s.background))
            if(i<5) assertEquals("STAGE_END",e?.type)
            else assertEquals("COMPLETE",e?.type)
        }
        assertEquals(AutomatedDmeBenchmark.State.COMPLETED,b.state)
        assertEquals(6,b.reports.size)
        assertEquals(AutomatedDmeBenchmark.stages.map{it.id},
            b.reports.map{it.stage.id})
        assertTrue(b.reports.last().backgroundCycles>0)
        assertTrue(b.reportText().contains("No ECU acquisition timestamp"))
    }

    @Test fun elapsedGapIsReportedNotCountedAsActiveSamples() {
        val b=AutomatedDmeBenchmark(1_000L)
        b.onCycle(cycle(2_000L))
        b.onCycle(cycle(2_100L,complete=false))
        b.onCycle(cycle(12_100L,csvLost=3L))
        b.onCycle(cycle(122_500L,csvLost=7L))
        val r=b.reports.single()
        assertEquals(3,r.cycles)
        assertEquals(2,r.complete)
        assertEquals(2,r.gaps)
        assertTrue(r.maxGapMs>=110_000L)
        assertEquals(7L,r.lostDelta) // first per-stage sample starts at 0
        assertTrue(b.csvRow("STAGE_END",122_500L,123_000L).split(",").size==
            AutomatedDmeBenchmark.HEADER.split(",").size)
    }

    @Test fun noBatteryDrainingSyntheticFastRateFromSingleSample() {
        val b=AutomatedDmeBenchmark(1_000L)
        b.onCycle(cycle(2_000L))
        b.onCycle(cycle(122_100L))
        assertTrue(b.reports[0].observedHz<1.0)
    }
}
