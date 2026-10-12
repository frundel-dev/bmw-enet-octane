package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class GuidedFuelTestTest {
    private fun observation(
        phase:BaselineLearning.Phase=BaselineLearning.Phase.STEADY,
        rpm:Double=2150.0,load:Double=42.0,map:Double=110.0,
        trip:Long=100L,segment:Int=1,knock:Double=0.08
    )=BaselineLearning.Sample(
        1760000000000L,trip,1,phase,segment,rpm,load,map,
        24.0,90.0,1.2,-1.8,knock,1.5
    )

    @Test fun noSelfTrainingFromUnverifiedRefill() {
        val coverage=GuidedFuelTest.Coverage()
        repeat(40) {coverage.accept(observation())}
        val s=coverage.snapshot()
        assertEquals(0,s.steadyReady)
        assertEquals(0,s.accelReady)
        assertFalse(s.captureReady)
        assertNull(s.relativeKnockDeltaPct)
        assertTrue(s.progress<100)
    }

    @Test fun twoSteadyAndThreeAccelRangesTriggerPreliminaryResult() {
        val coverage=GuidedFuelTest.Coverage()
        listOf(2150.0,2650.0).forEach { rpm ->
            repeat(4) {i->
                coverage.accept(observation(rpm=rpm,segment=i/2+1))
            }
        }
        val rpmBins=listOf(2650.0,3250.0,3750.0)
        rpmBins.forEach { rpm ->
            val ref=OctaneCalibration.accelerationReference(rpm,210.0)!!
            repeat(4) {i->
                coverage.accept(observation(
                    phase=BaselineLearning.Phase.ACCEL,rpm=rpm,
                    load=85.0,map=210.0,
                    segment=20+i/2,
                    knock=ref.knockMeanVms*1.05
                ))
            }
        }
        val state=coverage.snapshot()
        assertEquals(2,state.steadyReady)
        assertEquals(3,state.accelReady)
        assertEquals(8,state.steadyPoints)
        assertEquals(12,state.accelPoints)
        assertEquals(100,state.progress)
        assertTrue(state.captureReady)
        assertEquals(3,state.comparableAccelBins)
        assertEquals(5.0,state.relativeKnockDeltaPct!!,0.000001)
        assertTrue(state.nextStep.contains("предварительного"))
    }

    @Test fun noResultWhenOneRpmBinOrBadSample() {
        val c=GuidedFuelTest.Coverage()
        val ref=OctaneCalibration.accelerationReference(2650.0,210.0)!!
        repeat(4) {i->
            c.accept(observation(phase=BaselineLearning.Phase.ACCEL,
                rpm=2650.0,map=210.0,load=85.0,
                segment=i/2+1,knock=ref.knockMeanVms))
        }
        c.accept(observation(knock=Double.NaN))
        c.accept(observation(phase=BaselineLearning.Phase.ACCEL,
            rpm=2550.0,load=85.0,map=180.0))
        assertEquals(4,c.accepted)
        assertEquals(1,c.snapshot().accelReady)
        assertFalse(c.snapshot().captureReady)
        assertNull(c.snapshot().relativeKnockDeltaPct)
    }

    @Test fun repeatedAdjacentSamplesDoNotCountAsSeparateSegments() {
        val c=GuidedFuelTest.Coverage()
        repeat(100) {
            c.accept(observation(
                phase=BaselineLearning.Phase.ACCEL,
                rpm=2700.0,load=85.0,map=215.0,
                segment=1))
        }
        assertEquals(0,c.snapshot().accelReady)
        assertFalse(c.snapshot().captureReady)
        assertTrue(c.snapshot().progress<100)
    }

    @Test fun gradeAndFilenameStayDistinctFromExistingCalibration() {
        assertTrue(GuidedFuelTest.grades.contains("АИ-95"))
        assertEquals("bmw_fuel_test_v1731_42.csv",GuidedFuelTest.filename(42))
        assertEquals("0.9-stratified-survey",OctaneCalibration.VERSION)
    }
}
