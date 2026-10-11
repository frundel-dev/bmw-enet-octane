package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class PollModePolicyTest {
    @Test fun freshInstallsStartInBPlus() {
        assertEquals("B+",PollModePolicy.resolve(null,false))
        assertEquals("B+",PollModePolicy.resolve("",false))
        assertEquals("B+",PollModePolicy.resolve("unknown",true))
    }

    @Test fun oneTimeMigrationMovesOldDefaultBToBPlus() {
        assertEquals("B+",PollModePolicy.resolve("B",false))
        assertEquals("B",PollModePolicy.resolve("B",true))
        assertEquals("B+",PollModePolicy.resolve("C",false))
        assertEquals("B+",PollModePolicy.resolve("D",true))
    }

    @Test fun explicitModesRemainAfterUpgrade() {
        for(mode in listOf("A","B+","AUTO"))
            assertEquals(mode,PollModePolicy.resolve(mode,false))
        assertEquals("AUTO",PollModePolicy.resolve("AUTO",true))
    }

    @Test fun manualModesHaveNoSecondaryTcp() {
        assertEquals(listOf("B+","B","A","AUTO"),PollModePolicy.manualModes)
        assertEquals(listOf("A","B"),PollModePolicy.automaticModes)
        assertFalse(PollModePolicy.manualModes.any { it=="C" || it=="D" })
    }
}
