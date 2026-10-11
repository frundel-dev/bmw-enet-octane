package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class PollModePolicyTest {
    @Test fun freshInstallsStartInB() {
        assertEquals("B",PollModePolicy.resolve(null,false))
        assertEquals("B",PollModePolicy.resolve("",false))
        assertEquals("B",PollModePolicy.resolve("unknown",true))
    }

    @Test fun legacyAutoMigratesToBExactlyOnce() {
        assertEquals("B",PollModePolicy.resolve("AUTO",false))
        assertEquals("AUTO",PollModePolicy.resolve("AUTO",true))
    }

    @Test fun explicitManualChoicesSurviveUpdate() {
        for(mode in listOf("A","B","B+","C","D"))
            assertEquals(mode,PollModePolicy.resolve(mode,false))
    }

    @Test fun autoNeverStartsSecondTcp() {
        assertEquals(listOf("A","B"),PollModePolicy.automaticModes)
        assertFalse(PollModePolicy.automaticModes.any { it=="C" || it=="D" })
    }
}
