package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class EngineReadingsFreshnessTest {
    @Test fun absentOrInvalidTimestampsAreNotFresh() {
        assertFalse(EngineReadingsFreshness.isFresh(0L, 100L, 2500L))
        assertFalse(EngineReadingsFreshness.isFresh(-1L, 100L, 2500L))
        assertFalse(EngineReadingsFreshness.isFresh(101L, 100L, 2500L))
        assertFalse(EngineReadingsFreshness.isFresh(50L, 100L, -1L))
    }

    @Test fun fastBoundaryExpiresAfterTwoAndHalfSeconds() {
        val t = 1_000L
        val ttl = EngineReadingsFreshness.FAST_MAX_AGE_MS
        assertTrue(EngineReadingsFreshness.isFresh(t, t, ttl))
        assertTrue(EngineReadingsFreshness.isFresh(t, t + ttl, ttl))
        assertFalse(EngineReadingsFreshness.isFresh(t, t + ttl + 1, ttl))
    }

    @Test fun slowTemperaturesExpireIndependentlyOfFastReadings() {
        val t = 1_000L
        val now = t + 4_000L
        assertFalse(EngineReadingsFreshness.isFresh(t, now,
            EngineReadingsFreshness.FAST_MAX_AGE_MS))
        assertTrue(EngineReadingsFreshness.isFresh(t, now,
            EngineReadingsFreshness.TEMPERATURE_MAX_AGE_MS))
        assertFalse(EngineReadingsFreshness.isFresh(t, t + 8_001L,
            EngineReadingsFreshness.TEMPERATURE_MAX_AGE_MS))
    }

    @Test fun wrappedOrResetClockCannotMakeOldSampleLookFresh() {
        assertFalse(EngineReadingsFreshness.isFresh(60_000L, 500L,
            EngineReadingsFreshness.TEMPERATURE_MAX_AGE_MS))
    }
}
