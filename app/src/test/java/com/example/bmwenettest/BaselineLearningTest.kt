package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class BaselineLearningTest {
    private fun sample(
        phase:BaselineLearning.Phase=BaselineLearning.Phase.STEADY,
        rpm:Double=2600.0,load:Double=42.0,map:Double=110.0,
        trip:Long=100L,segment:Int=1,iat:Double=23.0
    )=BaselineLearning.Sample(
        1760000000000L,trip,2,phase,segment,rpm,load,map,
        iat,90.0,1.2,-2.2,0.091,1.5
    )

    @Test fun separateSteadyAndAccelAndRespectPhysicalGates() {
        val steady=BaselineLearning.Phase.STEADY
        val accel=BaselineLearning.Phase.ACCEL
        assertEquals(BaselineLearning.Cell(steady,2,1),
            BaselineLearning.cell(steady,2600.0,42.0,110.0))
        assertEquals(BaselineLearning.Cell(accel,2,2),
            BaselineLearning.cell(accel,3650.0,93.0,211.0))
        assertNull(BaselineLearning.cell(accel,3650.0,93.0,190.0))
        assertNull(BaselineLearning.cell(steady,2600.0,42.0,200.0))
        assertNull(BaselineLearning.cell(steady,4500.0,42.0,110.0))
        assertNull(BaselineLearning.cell(steady,2600.0,69.0,110.0))
        assertNull(BaselineLearning.cell(accel,2499.0,93.0,211.0))
        assertNull(BaselineLearning.cell(steady,Double.NaN,42.0,110.0))
    }

    @Test fun roundTripAndRejectMalformedOrNonComparableRows() {
        val src=sample()
        assertEquals(src,BaselineLearning.parse(BaselineLearning.serialize(src)))
        assertNull(BaselineLearning.parse("garbage"))
        assertNull(BaselineLearning.parse(BaselineLearning.HEADER))
        assertNull(BaselineLearning.parse("# label=not validated"))
        assertNull(BaselineLearning.parse(BaselineLearning.serialize(src.copy(iat=78.0))))
        assertNull(BaselineLearning.parse(BaselineLearning.serialize(src.copy(knock=Double.NaN))))
        assertNull(BaselineLearning.parse(BaselineLearning.serialize(src.copy(ltft=22.0))))
        assertNull(BaselineLearning.parse(BaselineLearning.serialize(src.copy(map=151.0))))
    }

    @Test fun adjacentSamplesAreNotIndependentSegmentsOrTrips() {
        val coverage=BaselineLearning.Coverage()
        val c=BaselineLearning.cell(BaselineLearning.Phase.STEADY,2600.0,42.0,110.0)!!
        repeat(100){coverage.accept(sample())}
        val current=coverage.stats(c)
        assertEquals(100,current.count)
        assertEquals(1,current.segments)
        assertEquals(1,current.trips)
        assertEquals(25,current.progress)
        assertFalse(current.readyForReview)
    }

    @Test fun readinessRequiresCountDistinctSegmentsAndLoggerStarts() {
        val coverage=BaselineLearning.Coverage()
        val c=BaselineLearning.cell(BaselineLearning.Phase.STEADY,2600.0,42.0,110.0)!!
        repeat(24) { i ->
            coverage.accept(sample(trip=if(i<12)100 else 200,
                segment=i%4+1,iat=if(i<12)17.0 else 29.0))
        }
        val result=coverage.stats(c)
        assertEquals(24,result.count)
        assertEquals(8,result.segments)
        assertEquals(2,result.trips)
        assertEquals(2,result.iatBuckets)
        assertEquals(100,result.progress)
        assertTrue(result.readyForReview)
        assertNotNull(result.provisionalMedianVms)
        assertEquals(0.091,result.provisionalMedianVms!!,0.0000001)
        assertEquals(0.0,result.betweenTripMadPct!!,0.000001)
        assertTrue(coverage.exportCandidate().contains("STEADY,2500,2999,35,49"))
        assertTrue(coverage.exportCandidate().contains("ron_verified"))
        assertEquals(1,coverage.reviewed(BaselineLearning.Phase.STEADY))
        assertEquals(0,coverage.reviewed(BaselineLearning.Phase.ACCEL))
    }

    @Test fun adviceNeverPromotesFuelToKnownRON() {
        val c=BaselineLearning.Coverage()
        val advice=c.advice(BaselineLearning.Phase.STEADY,2500.0,38.0)
        assertTrue(advice.text.contains("MAP"))
        assertTrue(advice.text.contains("об/мин"))
        assertFalse(advice.text.contains("АИ-95"))
        assertEquals("bmw_baseline_learning_v1730_42.csv",BaselineLearning.filename(42))
    }
}
