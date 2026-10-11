package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class PollModePolicyTest {
    @Test fun defaultsToBPlus() {
        assertEquals("B+",PollModePolicy.resolve(null,false))
        assertEquals("B+",PollModePolicy.resolve("",false))
        assertEquals("B+",PollModePolicy.resolve("garbage",true))
    }

    @Test fun oldAutoAndTwoTcpSettingsBecomeBPlus() {
        for(old in listOf("AUTO","C","D"))
            assertEquals("B+",PollModePolicy.resolve(old,true))
    }

    @Test fun retainsExplicitManualFallbackSelections() {
        assertEquals("B",PollModePolicy.resolve("B",true))
        assertEquals("A",PollModePolicy.resolve("A",true))
        assertEquals("B+",PollModePolicy.resolve("B+",true))
    }

    @Test fun oldDefaultMigratesOnceButExplicitBRemains() {
        assertEquals("B+",PollModePolicy.resolve("B",false))
        assertEquals("B",PollModePolicy.resolve("B",true))
    }

    @Test fun onlySafeSingleTcpModesAreExposed() {
        assertEquals(listOf("B+","B","A"),PollModePolicy.manualModes)
    }
}
