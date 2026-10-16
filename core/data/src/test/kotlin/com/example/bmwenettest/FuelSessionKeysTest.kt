package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FuelSessionKeysTest {
    @Test fun stablePersistedKeysRemainByteForByteCompatible() {
        assertEquals("guided_fuel_test_id",FuelSessionKeys.PREF_ID)
        assertEquals("guided_fuel_test_active",FuelSessionKeys.PREF_ACTIVE)
        assertEquals("guided_fuel_test_done",FuelSessionKeys.PREF_DONE)
        assertEquals("guided_fuel_test_fuel_session",FuelSessionKeys.PREF_FUEL_SESSION)
        assertEquals("guided_fuel_test_grade",FuelSessionKeys.PREF_GRADE)
        assertEquals("guided_fuel_test_station",FuelSessionKeys.PREF_STATION)
        assertEquals("bmw_fuel_test_v1731_123.csv",FuelSessionKeys.filename(123))
        assertEquals(listOf("АИ-95","АИ-98","АИ-100"),FuelSessionKeys.selectableGrades)
        assertTrue("old saved AI-92 must still be readable",
            "АИ-92" in FuelSessionKeys.grades)
        assertFalse("old AI-92 must not be selectable",
            "АИ-92" in FuelSessionKeys.selectableGrades)
    }
}
