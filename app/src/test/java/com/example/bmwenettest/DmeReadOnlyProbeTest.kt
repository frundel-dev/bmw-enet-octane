package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DmeReadOnlyProbeTest {
    private fun bytes(vararg n:Int)=n.map { it.toByte() }.toByteArray()
    private fun spec(did:Int)=DmeReadOnlyProbe.specs.first { it.did==did }

    @Test fun positiveKnockAdaptationDecodesTentativelyNotInstantRetard() {
        val result=DmeReadOnlyProbe.parse(spec(0x554A),
            listOf(bytes(0x62,0x55,0x4A,0xFF,0xF6)))
        assertEquals("OK",result.status)
        assertEquals("FFF6",result.rawHex)
        assertTrue(result.decodedCandidate.contains("-1.0"))
        assertTrue(result.decodedCandidate.contains("адаптация"))
    }

    @Test fun injectionModeIsNotMislabelledAsPulseWidth() {
        val result=DmeReadOnlyProbe.parse(spec(0x4530),
            listOf(bytes(0x62,0x45,0x30,1)))
        assertEquals("OK",result.status)
        assertEquals("1 (код)",result.decodedCandidate)
        assertTrue(result.spec.description.contains("не длительность"))
    }

    @Test fun unsupportedDidAndSecurityNegativeAreSeparated() {
        val unsupported=DmeReadOnlyProbe.parse(spec(0x4A36),
            listOf(bytes(0x7F,0x22,0x31)))
        assertEquals("UNSUPPORTED",unsupported.status)
        assertEquals("31",unsupported.negativeCode)
        val secured=DmeReadOnlyProbe.parse(spec(0x4A36),
            listOf(bytes(0x7F,0x22,0x33)))
        assertEquals("NEGATIVE",secured.status)
        assertTrue(secured.note.contains("НЕ запрашиваем"))
    }

    @Test fun unexpectedDidAndWrongWidthAreNotAccepted() {
        val other=DmeReadOnlyProbe.parse(spec(0x554A),
            listOf(bytes(0x62,0x55,0x4B,0,0)))
        assertEquals("UNEXPECTED",other.status)
        val short=DmeReadOnlyProbe.parse(spec(0x554A),
            listOf(bytes(0x62,0x55,0x4A,0)))
        assertEquals("INVALID_LENGTH",short.status)
        assertEquals("NO_RESPONSE",DmeReadOnlyProbe.parse(spec(0x554A),emptyList()).status)
    }
}
