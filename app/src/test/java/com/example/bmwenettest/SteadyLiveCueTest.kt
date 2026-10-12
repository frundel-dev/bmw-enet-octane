package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class SteadyLiveCueTest {
    @Test fun onlyBothRpmAndAbsoluteMapInBandAreGreen() {
        val good=SteadyLiveCue.evaluate(3200.0,115.0,1,2500L)
        assertEquals(SteadyLiveCue.Band.TARGET_MAP,good.band)
        assertEquals(50,good.holdPct)
        assertTrue(good.description.contains("3 с"))
        assertEquals(SteadyLiveCue.Band.LOW_MAP,
            SteadyLiveCue.evaluate(3200.0,104.9,-1,0).band)
        assertEquals(SteadyLiveCue.Band.HIGH_MAP,
            SteadyLiveCue.evaluate(3200.0,130.0,-1,0).band)
        assertEquals(SteadyLiveCue.Band.OUTSIDE_RPM,
            SteadyLiveCue.evaluate(3500.0,115.0,-1,0).band)
        assertEquals(SteadyLiveCue.Band.OUTSIDE_RPM,
            SteadyLiveCue.evaluate(2400.0,115.0,-1,0).band)
    }

    @Test fun rpmMapMatchingAloneIsNotSteadyOrPersisted() {
        val unstable=SteadyLiveCue.evaluate(2700.0,115.0,-1,5000L)
        assertEquals(SteadyLiveCue.Band.TARGET_MAP,unstable.band)
        assertEquals(0,unstable.holdPct)
        assertTrue(unstable.description.contains("ожидаем"))
        val complete=SteadyLiveCue.evaluate(2700.0,115.0,0,5000L)
        assertEquals(100,complete.holdPct)
        assertTrue(complete.description.contains("ждём подтверждения записи"))
        assertEquals(0,SteadyLiveCue.evaluate(2700.0,115.0,1,5000L).holdPct)
    }

    @Test fun badOrMissingDmeValuesNeverAppearAsCurrentTarget() {
        for(rpm in listOf<Double?>(null,Double.NaN,Double.POSITIVE_INFINITY)) {
            assertEquals(SteadyLiveCue.Band.NO_DATA,
                SteadyLiveCue.evaluate(rpm,115.0,-1,0).band)
        }
        for(map in listOf<Double?>(null,Double.NaN,Double.NEGATIVE_INFINITY)) {
            assertEquals(SteadyLiveCue.Band.NO_DATA,
                SteadyLiveCue.evaluate(2700.0,map,-1,0).band)
        }
        assertEquals(0,SteadyLiveCue.evaluate(3200.0,115.0,1,-500L).holdPct)
        assertEquals(100,SteadyLiveCue.evaluate(3200.0,115.0,1,10000L).holdPct)
    }
}
