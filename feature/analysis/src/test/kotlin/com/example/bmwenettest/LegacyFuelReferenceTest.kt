package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class LegacyFuelReferenceTest {
    @Test fun historicReferenceValuesAndBoundariesAreFrozen() {
        assertNull(LegacyFuelReference.lookup(null,150.0))
        assertNull(LegacyFuelReference.lookup(1999.0,150.0))
        assertNull(LegacyFuelReference.lookup(2500.0,129.99))
        val first=LegacyFuelReference.lookup(2200.0,150.0)!!
        assertEquals(0.073798,first.v,0.0)
        assertEquals(12,first.n)
        val next=LegacyFuelReference.lookup(2500.0,160.0)!!
        assertEquals(0.071964,next.v,0.0)
        assertEquals(10,next.n)
        val upper=LegacyFuelReference.lookup(4100.0,210.0)!!
        assertEquals(0.114581,upper.v,0.0)
        assertEquals(9,upper.n)
        assertNull(LegacyFuelReference.lookup(4500.0,200.0))
    }
}
