package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DiagnosticMenuTest {
    @Test fun everydayMenuHasOnlyOperationalFeatures() {
        val names = DiagnosticMenu.operations.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(DiagnosticMenu.Tool.DME_HISTORY_READ in DiagnosticMenu.operations)
        assertTrue(DiagnosticMenu.Tool.CONNECTION_EVENTS in DiagnosticMenu.operations)
        assertTrue(DiagnosticMenu.Tool.POLLING_PERFORMANCE in DiagnosticMenu.operations)
        assertFalse(DiagnosticMenu.Tool.RAM_SWFL in DiagnosticMenu.operations)
        assertFalse(DiagnosticMenu.Tool.FAST_DID_CANDIDATES in DiagnosticMenu.operations)
        assertFalse(DiagnosticMenu.Tool.FAST_RAW_EVIDENCE in DiagnosticMenu.operations)
    }

    @Test fun researchToolsRemainExplicitlySeparated() {
        assertEquals(setOf(
            DiagnosticMenu.Tool.FAST_DID_CANDIDATES,
            DiagnosticMenu.Tool.FAST_RAW_EVIDENCE,
            DiagnosticMenu.Tool.RAM_SWFL
        ), DiagnosticMenu.research.toSet())
        assertTrue(DiagnosticMenu.operations.intersect(DiagnosticMenu.research.toSet()).isEmpty())
        assertTrue((DiagnosticMenu.operations + DiagnosticMenu.research).all {
            it.label.isNotBlank()
        })
    }

    @Test fun retiredTestCannotBeTheOrdinaryMeasurementDefault() {
        assertEquals("AUTO", DiagnosticMenu.NORMAL_MODE)
        assertEquals("measurement_mode", DiagnosticMenu.MODE_KEY)
        assertTrue(DiagnosticMenu.RETIREMENT_MIGRATED_KEY.isNotEmpty())
    }
}
