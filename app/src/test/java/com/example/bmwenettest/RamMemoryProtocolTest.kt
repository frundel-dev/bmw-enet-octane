package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class RamMemoryProtocolTest {
    private val codec=RamMemoryProtocol

    @Test fun correctReadMemoryByAddressFormat() {
        assertArrayEquals(byteArrayOf(0x23,0x14,0x12,0x34,0x56,0x78,0x04),
            codec.encode(0x12345678L,4,4))
        assertArrayEquals(byteArrayOf(0x23,0x12,0x12,0x34,0x02),
            codec.encode(0x1234,2,2))
        assertArrayEquals(byteArrayOf(0x23,0x11,0x12,0x01),
            codec.encode(0x12,1,1))
    }

    @Test fun positiveRequiresExactSidAndLength() {
        val good=byteArrayOf(0x63,0,0,0x06,0xD6.toByte())
        assertArrayEquals(byteArrayOf(0,0,0x06,0xD6.toByte()),
            codec.decodePositive(good,4))
        assertNull(codec.decodePositive(good,2))
        assertNull(codec.decodePositive(good+byteArrayOf(0),4))
        assertNull(codec.decodePositive(good.copyOf(4),4))
        assertNull(codec.decodePositive(byteArrayOf(0x62,0,0,0,0),4))
        assertNull(codec.decodePositive(byteArrayOf(0x7F,0x23,0x31),2))
    }

    @Test fun nrcIsIdentifiedButNeverAcceptedAsData() {
        assertEquals(0x31,codec.negativeCode(byteArrayOf(0x7F,0x23,0x31)))
        assertEquals(0x33,codec.negativeCode(byteArrayOf(0x7F,0x23,0x33)))
        assertNull(codec.negativeCode(byteArrayOf(0x7F,0x22,0x31)))
        assertNull(codec.negativeCode(byteArrayOf(0x7F,0x23)))
    }

    @Test fun requestsRejectZeroAddressOutOfBoundsAndOversize() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(0,4,4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(0xFFFFFFFEL,4,4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(0x10000L,2,1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(0x100L,0,1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.encode(0x1234L,4,9)
        }
    }

    @Test fun onlyExactFirmwareMatchedRpmLocationMayBeRequested() {
        assertTrue(codec.liveProfileAvailable())
        val expected=byteArrayOf(0x23,0x14,0x60,0,0x3B,0x64,0x02)
        assertArrayEquals(expected,codec.approvedVehicleRequest(
            codec.APPROVED_A2L,codec.RPM_SIGNAL))
        assertEquals("00005D55-070.060.007",codec.APPROVED_SWFL)
        assertNull(codec.approvedVehicleRequest("SYNTHETIC",codec.RPM_SIGNAL))
        assertNull(codec.approvedVehicleRequest("","RPM"))
        assertNull(codec.approvedVehicleRequest("known DID 4A37","RPM"))
        assertNull(codec.approvedVehicleRequest(codec.APPROVED_A2L,"Knock"))
    }

    @Test fun littleEndianSignedNkwDecoderRejectsOutOfRange() {
        assertEquals(750,codec.decodeNkwRpm(byteArrayOf(0xEE.toByte(),0x02)))
        assertEquals(0,codec.decodeNkwRpm(byteArrayOf(0,0)))
        assertNull(codec.decodeNkwRpm(byteArrayOf(0xFF.toByte(),0xFF.toByte())))
        assertNull(codec.decodeNkwRpm(byteArrayOf(0xFF.toByte())))
    }

    @Test fun offlineFixtureProducesValidResponseWithoutAccessingTransport() {
        val (request,response)=codec.offlineFixture()
        assertEquals(0x23,request[0].toInt() and 255)
        assertEquals(0x63,response[0].toInt() and 255)
        assertEquals(4,codec.decodePositive(response,4)?.size)
    }
}
