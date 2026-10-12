package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class RamReadinessProtocolTest {
    @Test fun allRequestsAreStrictlyReadOnlyUds22() {
        assertEquals(6,RamReadinessProtocol.probes.size)
        for(probe in RamReadinessProtocol.probes) {
            val request=RamReadinessProtocol.request(probe.did)
            assertEquals(3,request.size)
            assertEquals(0x22,request[0].toInt() and 0xff)
            assertEquals(probe.did,((request[1].toInt() and 0xff) shl 8) or
                (request[2].toInt() and 0xff))
            assertNotEquals(0xF190,probe.did) // no VIN collection
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun arbitraryRamDidCannotBeRequested() {
        RamReadinessProtocol.request(0x1234)
    }

    @Test fun matchingResponseRequiresServiceDidAndPayload() {
        assertTrue(RamReadinessProtocol.positiveMatches(0x4A37,
            byteArrayOf(0x62,0x4A,0x37,0x01)))
        assertFalse(RamReadinessProtocol.positiveMatches(0x4A37,
            byteArrayOf(0x62,0x4A,0x38,0x01)))
        assertFalse(RamReadinessProtocol.positiveMatches(0x4A37,
            byteArrayOf(0x63,0x4A,0x37,0x01)))
        assertFalse(RamReadinessProtocol.positiveMatches(0x4A37,
            byteArrayOf(0x62,0x4A,0x37)))
    }

    @Test fun negativeResponseIsNeverAcceptedAsData() {
        assertFalse(RamReadinessProtocol.positiveMatches(0xF189,
            byteArrayOf(0x7f,0x22,0x31)))
    }

    @Test fun responseLengthIsBounded() {
        val tooLarge=ByteArray(513)
        tooLarge[0]=0x62
        tooLarge[1]=0x4A
        tooLarge[2]=0x49
        assertFalse(RamReadinessProtocol.positiveMatches(0x4A49,tooLarge))
    }
}
