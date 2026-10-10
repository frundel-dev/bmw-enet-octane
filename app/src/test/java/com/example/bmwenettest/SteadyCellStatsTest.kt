package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class SteadyCellStatsTest {
    @Test fun medianMadAndRepeatabilityNeedIndependentSegments() {
        val stats=SteadyCellStats()
        repeat(12) { stats.record("cell",it%3,0.1) }
        val s=stats.summary("cell")
        assertEquals(12,s.observations)
        assertEquals(3,s.segments)
        assertEquals(0.1,s.medianVms!!,1e-9)
        assertEquals(0.0,s.dispersionPct!!,1e-9)
        assertTrue(s.repeatableWithinSession)
        assertEquals(1,stats.repeatableCells())
        assertEquals(1,stats.cells())
    }

    @Test fun noRepeatabilityClaimAcrossOneSegment() {
        val stats=SteadyCellStats()
        repeat(12) { stats.record("cell",1,0.07) }
        assertFalse(stats.summary("cell").repeatableWithinSession)
    }

    @Test fun rejectsNonphysicalAndCapsMemory() {
        val stats=SteadyCellStats()
        stats.record("cell",0,Double.NaN)
        stats.record("cell",0,0.0)
        stats.record("cell",0,Double.POSITIVE_INFINITY)
        assertEquals(0,stats.summary("cell").observations)
        repeat(600) { stats.record("cell",it%3,0.07) }
        assertEquals(500,stats.summary("cell").observations)
        stats.clear()
        assertEquals(0,stats.cells())
        assertNull(stats.summary("cell").medianVms)
    }
}
