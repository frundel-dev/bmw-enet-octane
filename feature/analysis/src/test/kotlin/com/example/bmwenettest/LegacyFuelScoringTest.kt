package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class LegacyFuelScoringTest {
    @Test fun emptyDatasetNeverInfersFuelQuality() {
        val r=LegacyFuelScoring.evaluate(LegacyFuelScoring.Input())
        assertNull(r.sessionScore)
        assertNull(r.liveRunScore)
        assertEquals(0,r.sessionConfidence)
        assertEquals("COLLECTING",r.resultState)
    }
    @Test fun frozenAutoMathAndIndependentPhaseConfidence() {
        val r=LegacyFuelScoring.evaluate(LegacyFuelScoring.Input(
            runWeight=2.0,runWeightedRatio=2.0,runKnockEvents=1,
            runHighPoints=4,runValidPoints=12,
            autoRatioWeight=2.0,autoRatioSum=2.0,
            autoKnockEvents=1,
            steadyPoints=12,steadySegments=3,steadyCells=3,
            accelPoints=20,accelSegments=4,accelCells=4
        ))
        assertEquals(100.0,r.liveRunScore!!,0.0)
        assertEquals(40,r.liveRunQuality)
        assertEquals(100.0,r.sessionScore!!,0.0)
        assertEquals(49,r.steadyConfidence)
        assertEquals(76,r.accelConfidence)
        assertEquals(76,r.sessionConfidence)
        assertEquals("NORMAL",r.resultState)
    }
    @Test fun pendingRefuelOrMixingWinsOverApparentScore() {
        val x=LegacyFuelScoring.Input(autoRatioWeight=1.0,autoRatioSum=1.0,
            accelPoints=20,accelSegments=4,accelCells=4,
            pendingRefuel=true)
        assertEquals("REFUEL CHECK",LegacyFuelScoring.evaluate(x).resultState)
        assertEquals("MIXING",LegacyFuelScoring.evaluate(x.copy(
            pendingRefuel=false,mixingKm=0.1)).resultState)
    }
    @Test fun manualModeKeepsHistoricalWeightedScoreAndConfidence() {
        val r=LegacyFuelScoring.evaluate(LegacyFuelScoring.Input(
            autoMode=false,sessionQualitySum=4.0,sessionScoreSum=380.0,
            sessionValidRuns=2,sessionHighPoints=3,coverageCells=4,
            sessionScores=listOf(94.0,96.0)
        ))
        assertEquals(95.0,r.sessionScore!!,0.0)
        assertEquals(0,r.steadyConfidence)
        assertEquals(0,r.accelConfidence)
        // 16 run + 6 high + 8 cells + 12 consistency = 42
        assertEquals(42,r.sessionConfidence)
        assertEquals("COLLECTING",r.resultState)
    }
    @Test fun legacyConfidenceCannotBeMisreadAsLabRON() {
        val r=LegacyFuelScoring.evaluate(LegacyFuelScoring.Input(
            autoRatioWeight=1.0,autoRatioSum=1.3,
            steadyPoints=10,steadySegments=3,steadyCells=3,
            accelPoints=5,accelSegments=1,accelCells=1
        ))
        assertEquals(85.0,r.sessionScore!!,0.0000001)
        assertTrue(r.sessionConfidence<100)
        assertEquals("COLLECTING",r.resultState)
    }
}
