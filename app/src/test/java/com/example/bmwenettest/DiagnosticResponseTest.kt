package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DiagnosticResponseTest {
    private fun b(vararg values:Int)=values.map { it.toByte() }.toByteArray()

    @Test fun matchesOnlyRequestedObdPidWithExactWidth() {
        assertArrayEquals(b(0x12,0x34),DiagnosticResponse.obd(b(0x41,0x0C,0x12,0x34),0x0C))
        assertNull(DiagnosticResponse.obd(b(0x41,0x0D,0x12),0x0C))
        assertNull(DiagnosticResponse.obd(b(0x41,0x0C,0x12),0x0C))
        assertNull(DiagnosticResponse.obd(b(0x41,0x0C,0x12,0x34,0x56),0x0C))
        assertNull(DiagnosticResponse.obd(b(0x7F,0x01,0x11,0x41,0x0C,1,2),0x0C))
    }

    @Test fun acceptsOneByteGatewayPrefixWithoutIgnoringWrongService() {
        assertArrayEquals(b(0x3F),DiagnosticResponse.obd(b(4,0x41,0x0B,0x3F),0x0B))
        assertNull(DiagnosticResponse.obd(b(1,2,0x41,0x0B,0x3F),0x0B))
        assertNull(DiagnosticResponse.obd(b(0x62,0x41,0x0B,0x3F),0x0B))
    }

    @Test fun matchingUdsDidIsMandatory() {
        assertArrayEquals(b(0xFF,0x80),DiagnosticResponse.uds(b(0x62,0x4A,0x37,0xFF,0x80),0x4A37))
        assertNull(DiagnosticResponse.uds(b(0x62,0x4A,0x38,1,2),0x4A37))
        assertNull(DiagnosticResponse.uds(b(0x7F,0x22,0x31),0x4A37))
        assertNull(DiagnosticResponse.uds(b(0x62,0x4A),0x4A37))
    }

    @Test fun unrelatedFrameBeforeCorrectReplyCannotContaminateSample() {
        val payloads=listOf(b(0x41,0x0D,99),b(0x41,0x0C,0x14,0x23))
        val match=payloads.firstNotNullOfOrNull { DiagnosticResponse.obd(it,0x0C) }
        assertArrayEquals(b(0x14,0x23),match)
    }
}
