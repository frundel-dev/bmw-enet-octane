package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class SampleFreshnessTest {
    @Test fun rejectsMissingAndExpiredValues() {
        assertFalse(SampleFreshness.isFresh(22000L,null))
        assertTrue(SampleFreshness.isFresh(22000L,12000L))
        assertFalse(SampleFreshness.isFresh(22001L,12000L))
        assertFalse(SampleFreshness.isFresh(12000L,12001L))
    }

    @Test fun usesMonotonicTimestampWithCustomTTL() {
        assertTrue(SampleFreshness.isFresh(900L,800L,100L))
        assertFalse(SampleFreshness.isFresh(901L,800L,100L))
        assertFalse(SampleFreshness.isFresh(900L,800L,-1L))
    }
}
