package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DmeHistoryProtocolTest {
    private fun bytes(vararg v:Int)=v.map{it.toByte()}.toByteArray()

    @Test fun parsesEmptyAndMultipleDtcRecordsWithThreeByteCodes() {
        assertEquals(emptyList<DmeHistoryProtocol.Dtc>(),
            DmeHistoryProtocol.parseDtcList(bytes(0x59,0x02,0xFF)))
        val result=DmeHistoryProtocol.parseDtcList(bytes(
            0x59,0x02,0xAA,
            0x12,0x34,0x56,0x2F,
            0x01,0x02,0x03,0x80))
        assertNotNull(result)
        assertEquals(2,result!!.size)
        assertEquals("123456",result[0].hex)
        assertEquals("2F",result[0].statusHex)
        assertEquals("010203",result[1].hex)
        assertEquals("80",result[1].statusHex)
    }

    @Test fun rejectsTruncatedMalformedOrUnrelatedDtcLists() {
        val complete=bytes(0x59,0x02,0xFF,0x12,0x34,0x56,0x80)
        for(n in 0 until complete.size)
            assertNull("truncated $n",DmeHistoryProtocol.parseDtcList(complete.copyOf(n)))
        assertNull(DmeHistoryProtocol.parseDtcList(complete+bytes(0)))
        assertNull(DmeHistoryProtocol.parseDtcList(bytes(0x59,0x01,0xFF)))
        assertNull(DmeHistoryProtocol.parseDtcList(bytes(0x7F,0x19,0x31)))
    }

    @Test fun snapshotAndExtendedReadsAreReadOnlyAndMatchDtcIdentity() {
        for(sub in listOf(0x04,0x06)) {
            val request=DmeHistoryProtocol.byDtcRequest(sub,0x123456)
            assertArrayEquals(bytes(0x19,sub,0x12,0x34,0x56,0xFF),request)
            assertTrue(DmeHistoryProtocol.dtcResponseMatches(sub,0x123456,
                bytes(0x59,sub,0x12,0x34,0x56,0x80,0x01)))
            assertFalse(DmeHistoryProtocol.dtcResponseMatches(sub,0x123456,
                bytes(0x59,sub,0x12,0x34,0x57,0x80,0x01)))
            assertFalse(DmeHistoryProtocol.dtcResponseMatches(sub,0x123456,
                bytes(0x7F,0x19,0x31)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DmeHistoryProtocol.byDtcRequest(0x14,0x123456)
        }
    }

    @Test fun handlesOnlyNegativeResponseToMatchingService() {
        assertEquals("UNSUPPORTED",
            DmeHistoryProtocol.classifyNrc(0x19,bytes(0x7F,0x19,0x31)))
        assertEquals("SECURITY_DENIED",
            DmeHistoryProtocol.classifyNrc(0x22,bytes(0x7F,0x22,0x33)))
        assertEquals("RESPONSE_PENDING",
            DmeHistoryProtocol.classifyNrc(0x19,bytes(0x7F,0x19,0x78)))
        assertNull(DmeHistoryProtocol.classifyNrc(0x19,bytes(0x7F,0x22,0x31)))
        assertNull(DmeHistoryProtocol.classifyNrc(0x19,bytes(0x7F,0x19)))
    }

    @Test fun csvPreservesRawHexAndEscapesNotes() {
        assertEquals("DTC,123456",DmeHistoryProtocol.csv("DTC","123456"))
        assertEquals("A,\"foo,bar\",\"say \"\"yes\"\"\"",
            DmeHistoryProtocol.csv("A","foo,bar","say \"yes\""))
    }

    @Test fun historyRequestIsBoundedToTwelveFaults() {
        assertEquals(12,DmeHistoryProtocol.MAX_DTCS)
        assertTrue(DmeHistoryProtocol.MAX_RESPONSE_BYTES < HsfzCodec.MAX_FRAME_BODY_BYTES)
    }
}
