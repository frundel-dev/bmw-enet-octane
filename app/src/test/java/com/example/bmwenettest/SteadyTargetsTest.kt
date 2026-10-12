package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class SteadyTargetsTest {
    private fun record(rpm:Double=2620.0,map:Double=115.0,
        trip:Long=100L,segment:Int=1,fuel:Int=1)=SteadyTargets.Record(
        1791800000000L,trip,fuel,segment,rpm,map,42.0,23.0,91.0,0.084)

    @Test fun exactlyTwoRequestedRpmMapTargets() {
        assertEquals(0,SteadyTargets.targetIndex(2500.0,105.0))
        assertEquals(0,SteadyTargets.targetIndex(2999.99,129.999))
        assertEquals(1,SteadyTargets.targetIndex(3000.0,105.0))
        assertEquals(1,SteadyTargets.targetIndex(3499.99,129.999))
        assertEquals(-1,SteadyTargets.targetIndex(2499.999,105.0))
        assertEquals(-1,SteadyTargets.targetIndex(3500.0,115.0))
        assertEquals(-1,SteadyTargets.targetIndex(2700.0,104.999))
        assertEquals(-1,SteadyTargets.targetIndex(2700.0,130.0))
        assertEquals(-1,SteadyTargets.targetIndex(Double.NaN,115.0))
        assertEquals(-1,SteadyTargets.targetIndex(2700.0,Double.NaN))
    }

    @Test fun onlyValidFiniteRowsAreRestored() {
        assertEquals(record(),SteadyTargets.parse(SteadyTargets.serialize(record())))
        assertNull(SteadyTargets.parse(SteadyTargets.HEADER))
        assertNull(SteadyTargets.parse("# metadata"))
        assertNull(SteadyTargets.parse("2700,wrong"))
        assertNull(SteadyTargets.parse(SteadyTargets.serialize(record(map=150.0))))
        assertNull(SteadyTargets.parse(SteadyTargets.serialize(record().copy(iat=69.0))))
        assertNull(SteadyTargets.parse(SteadyTargets.serialize(record().copy(knock=Double.NaN))))
        assertNull(SteadyTargets.parse(SteadyTargets.serialize(record().copy(load=69.0))))
    }

    @Test fun dwellAndThrottleDoNotTreatFastPollsAsIndependentEvidence() {
        val c=SteadyTargets.Tracker(1)
        assertNull(c.offer(1000,1791800001000L,100,2620.0,115.0,42.0,23.0,91.0,0.084,true))
        assertNull(c.offer(5800,1791800005800L,100,2620.0,115.0,42.0,23.0,91.0,0.084,true))
        val first=c.offer(6000,1791800006000L,100,2620.0,115.0,42.0,23.0,91.0,0.084,true)
        assertNotNull(first)
        assertEquals(0,c.snapshot().first.points) // not counted before durable write
        c.saved(first!!,6000)
        assertEquals(1,c.snapshot().first.points)
        assertNull(c.offer(7000,1791800007000L,100,2620.0,115.0,42.0,23.0,91.0,0.084,true))
        val second=c.offer(9000,1791800009000L,100,2620.0,115.0,42.0,23.0,91.0,0.084,true)
        assertNotNull(second)
        c.saved(second!!,9000)
        assertEquals(2,c.snapshot().first.points)
        assertEquals(1,c.snapshot().first.segments)
        assertEquals(1,c.snapshot().first.loggerStarts)
        assertTrue(c.snapshot().first.coveragePct<100)
    }

    @Test fun independentSegmentsAndNewStartsMatter() {
        val c=SteadyTargets.Tracker(1)
        repeat(24) {i ->
            assertTrue(c.accept(record(trip=if(i<12)100 else 200,segment=i/6+1)))
        }
        val s=c.snapshot().first
        assertEquals(24,s.points)
        assertEquals(4,s.segments)
        assertEquals(2,s.loggerStarts)
        assertEquals(100,s.coveragePct)
        assertTrue(s.candidateSupport)
        assertFalse(c.snapshot().second.candidateSupport)
        assertEquals(1,c.snapshot().complete)
    }

    @Test fun sameTripWithManySamplesDoesNotMeanCompleteModel() {
        val c=SteadyTargets.Tracker(1)
        repeat(100) {c.accept(record())}
        val s=c.snapshot().first
        assertEquals(100,s.points)
        assertEquals(1,s.segments)
        assertEquals(1,s.loggerStarts)
        assertFalse(s.candidateSupport)
        assertTrue(s.coveragePct<100)
    }

    @Test fun mixingFuelSessionsAreNotCombinedAndReloadWorks() {
        val c=SteadyTargets.Tracker(1,listOf(record(),record(trip=200,segment=2,fuel=2)))
        assertEquals(1,c.snapshot().first.points)
        assertFalse(c.accept(record(fuel=2)))
        assertEquals(1,c.snapshot().first.points)
        assertEquals("bmw_steady_targets_v1732_fuel1.csv",SteadyTargets.filename(1))
    }

    @Test fun preciseFeedbackForMissingMapAndQualifiedTarget() {
        val c=SteadyTargets.Tracker(1)
        assertTrue(c.advice(2700.0,90.0,false,true).contains("105–129"))
        assertTrue(c.advice(3200.0,140.0,false,true).contains("выше 129"))
        assertTrue(c.advice(3200.0,115.0,false,false).contains("прогрет"))
        assertTrue(c.advice(3200.0,115.0,true,true).contains("Собираю"))
        assertTrue(c.advice(null,115.0,false,false).contains("Ожидаю"))
    }

    @Test fun unsafeOrUnstablePointsNeverCount() {
        val c=SteadyTargets.Tracker(1)
        assertNull(c.offer(5000,1791800005000L,100,2600.0,115.0,
            42.0,23.0,91.0,0.084,false))
        assertNull(c.offer(6000,1791800006000L,100,2600.0,115.0,
            75.0,23.0,91.0,0.084,true))
        assertNull(c.offer(7000,1791800007000L,100,2600.0,115.0,
            42.0,75.0,91.0,0.084,true))
        assertEquals(0,c.snapshot().first.points)
    }
}
