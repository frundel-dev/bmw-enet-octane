package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DmeMonitorCsvRowTest {
    @Test fun twoDmeMonitorRecordsAreTwoPhysicalLines() {
        val raw=DmeHistoryProtocol.completeCsvRow(DmeHistoryProtocol.csv(
            1792052365125L,100L,"WIFI","B+","22","0x554A","22554A","62554A0000","OK","0"))
        val next=DmeHistoryProtocol.completeCsvRow(DmeHistoryProtocol.csv(
            1792052366125L,1100L,"WIFI","B+","22","0x4A36","224A36","624A3600","OK","0"))
        val joined=raw+next
        assertEquals(2,joined.trimEnd('\n').split('\n').size)
        assertFalse(joined.contains("\\n")) // no literal backslash-n characters
        assertEquals("1792052365125",joined.lineSequence().first().substringBefore(','))
    }
    @Test fun escapesQuotedCsvCellsWithoutIntroducingFakeRecords() {
        val row=DmeHistoryProtocol.completeCsvRow(DmeHistoryProtocol.csv("a,b","line1\nline2","ok"))
        assertTrue(row.startsWith("\"a,b\""))
        assertTrue(row.endsWith("\n"))
    }
}
