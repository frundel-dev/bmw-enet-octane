package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FuelRefillDetectorTest {
    @Test fun detectsOnlySustainedLargeIncrease() {
        val detector=FuelRefillDetector(22.4)
        assertTrue(detector.observe(100.0,1000L).pending)
        assertTrue(detector.observe(100.0,13000L).pending)
        val event=detector.observe(100.0,28000L)
        assertFalse(event.pending)
        assertNotNull(event.refill)
        assertEquals(22.4,event.refill!!.fromPercent,0.001)
        assertEquals(100.0,event.refill!!.toPercent,0.001)
        assertEquals(100.0,detector.stableLevel!!,0.001)
    }

    @Test fun ignoresSloshAndInvalidReadings() {
        val detector=FuelRefillDetector(40.0)
        assertNull(detector.observe(48.0,1000L).refill)
        assertFalse(detector.pending)
        assertNull(detector.observe(Double.NaN,2000L).refill)
        assertNull(detector.observe(110.0,3000L).refill)
        assertNull(detector.observe(-1.0,4000L).refill)
        assertEquals(40.0,detector.stableLevel!!,0.001)
    }

    @Test fun shortSpikeDoesNotProduceRefuel() {
        val detector=FuelRefillDetector(20.0)
        detector.observe(35.0,1000L)
        detector.observe(20.5,12000L)
        detector.observe(35.0,20000L)
        detector.observe(35.0,35000L)
        assertNull(detector.observe(20.5,38000L).refill)
        assertFalse(detector.pending)
    }

    @Test fun missingInitialLevelMustStabilizeFirst() {
        val detector=FuelRefillDetector(null)
        assertNull(detector.stableLevel)
        detector.observe(30.0,1000L)
        detector.observe(31.0,13000L)
        detector.observe(31.0,26000L)
        assertEquals(31.0,detector.stableLevel!!,0.01)
        assertNull(detector.observe(31.0,27000L).refill)
    }
}
