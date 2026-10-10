package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class OctaneCalibrationTest {
    @Test fun accelerationBoundariesRemainFrozen() {
        val expected=listOf(
            2500.0 to 0.092081,
            3000.0 to 0.109775,
            3500.0 to 0.121829,
            4000.0 to 0.131983
        )
        expected.forEach { (rpm,baseline) ->
            val ref=OctaneCalibration.accelerationReference(rpm,200.0)
            assertNotNull(ref)
            assertEquals(baseline,ref!!.knockMeanVms,0.0000001)
            assertTrue(ref.validated)
            assertEquals(3,ref.trainingTrips)
        }
        assertNull(OctaneCalibration.accelerationReference(2499.0,200.0))
        assertNull(OctaneCalibration.accelerationReference(4500.0,200.0))
        assertNull(OctaneCalibration.accelerationReference(2600.0,199.99))
        assertNull(OctaneCalibration.accelerationReference(Double.NaN,210.0))
    }

    @Test fun steadyReferenceDoesNotExtrapolateToUnseenCells() {
        val ref=OctaneCalibration.steadyReference(1750.0,95.0,38.0)
        assertNotNull(ref)
        assertEquals(0.0690528,ref!!.knockMeanVms,1e-9)
        assertFalse(ref.validated)
        assertNull(OctaneCalibration.steadyReference(2500.0,95.0,38.0))
        assertNull(OctaneCalibration.steadyReference(1750.0,105.0,38.0))
        assertNull(OctaneCalibration.steadyReference(1750.0,95.0,70.0))
    }

    @Test fun fuelScoreClampsAndKeepsFrozenFormula() {
        assertEquals(100.0,OctaneCalibration.experimentalScore(1.0),0.00001)
        assertEquals(110.0,OctaneCalibration.experimentalScore(0.8),0.00001)
        assertEquals(120.0,OctaneCalibration.experimentalScore(0.0),0.00001)
        assertEquals(0.0,OctaneCalibration.experimentalScore(4.0),0.00001)
    }

    @Test fun trimAndCellFiltersAreStable() {
        assertTrue(OctaneCalibration.trimsComparable(15.0,-15.0))
        assertFalse(OctaneCalibration.trimsComparable(15.001,0.0))
        assertFalse(OctaneCalibration.trimsComparable(Double.NaN,0.0))
        assertEquals("r1500:m0:l36:i18:c80",
            OctaneCalibration.fineSteadyCell(1750.0,95.0,38.0,19.0,84.0))
    }
}
