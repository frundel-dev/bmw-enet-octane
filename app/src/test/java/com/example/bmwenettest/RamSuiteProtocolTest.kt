package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class RamSuiteProtocolTest {
    private val p=RamSuiteProtocol

    @Test fun strictlyOrderedProgression() {
        val all=RamSuiteProtocol.Stage.entries
        assertEquals(listOf(7,8,9,10,11,12),all.map { it.number })
        assertTrue(p.allowed(RamSuiteProtocol.Stage.SIGNAL,emptySet()))
        for(stage in all.drop(1))
            assertFalse(p.allowed(stage,emptySet()))
        assertEquals(RamSuiteProtocol.Stage.SIGNAL,p.nextStage(emptySet()))
        assertTrue(p.allowed(RamSuiteProtocol.Stage.RPM_DYNAMICS,setOf(7)))
        assertFalse(p.allowed(RamSuiteProtocol.Stage.SEQUENTIAL,setOf(7)))
        assertEquals(RamSuiteProtocol.Stage.SEQUENTIAL,p.nextStage(setOf(7,8)))
        assertEquals(null,p.nextStage((7..12).toSet()))
    }

    @Test fun exactAllowlistedAddressesAndService() {
        assertArrayEquals(byteArrayOf(0x23,0x14,0x60,0,0x3B,0x64,0x02),
            p.memoryRequest("Nkw"))
        assertArrayEquals(byteArrayOf(0x23,0x14,0x60,0,0x36,0x18,0x02),
            p.memoryRequest("Dzw_tot_kr"))
        assertThrows(IllegalArgumentException::class.java) {
            p.memoryRequest("unapproved_knock_memory")
        }
    }

    @Test fun a2lSignedLittleEndianRetardScaleAndBounds() {
        // SWORD in MSB_LAST (LSB first), q0.1 degrees.
        assertEquals(1.5,p.retardDegrees(byteArrayOf(0x63,0x0F,0x00))!!,0.0001)
        assertEquals(-2.5,p.retardDegrees(byteArrayOf(0x63,0xE7.toByte(),0xFF.toByte()))!!,0.0001)
        assertEquals(0.0,p.retardDegrees(byteArrayOf(0x63,0,0))!!,0.0001)
        assertNull(p.retardDegrees(byteArrayOf(0x7F,0x23,0x31)))
        assertNull(p.retardDegrees(byteArrayOf(0x63,0,0,0)))
        assertNull(p.retardDegrees(byteArrayOf(0x63,0x10,0x27)))
        assertNull(p.retardDegrees(byteArrayOf(0x63,0)))
    }

    @Test fun speedAndRpmRejectWrongFrames() {
        assertTrue(p.speedStationary(byteArrayOf(0x41,0x0D,0)))
        assertFalse(p.speedStationary(byteArrayOf(0x41,0x0D,1)))
        assertFalse(p.speedStationary(byteArrayOf(0x7F,0x01,0x31)))
        assertEquals(713.0,p.rpm(byteArrayOf(0x63,0xC9.toByte(),0x02))!!,0.0001)
        assertEquals(713.0,p.obdRpm(byteArrayOf(0x41,0x0C,0x0B,0x24))!!,0.0001)
        assertTrue(p.plausible(713.0,715.0))
        assertFalse(p.plausible(980.0,715.0))
        assertFalse(p.plausible(null,715.0))
    }

    @Test fun ignitionReferenceIsFixedDidAndRequiresExactSize() {
        assertTrue(p.packedIgnitionReply(
            byteArrayOf(0x62,0x4A,0x49,0x00,0x12)))
        assertFalse(p.packedIgnitionReply(
            byteArrayOf(0x62,0x4A,0x49,0x00)))
        assertFalse(p.packedIgnitionReply(
            byteArrayOf(0x62,0x4A,0x4A,0x00,0x12)))
        assertFalse(p.packedIgnitionReply(
            byteArrayOf(0x7F,0x22,0x31)))
    }

    @Test fun boundedStageCountsAndTiming() {
        assertEquals(4,p.count(RamSuiteProtocol.Stage.SIGNAL))
        assertEquals(8,p.count(RamSuiteProtocol.Stage.RPM_DYNAMICS))
        assertEquals(6,p.count(RamSuiteProtocol.Stage.SEQUENTIAL))
        assertEquals(2,p.count(RamSuiteProtocol.Stage.RECONNECT))
        assertEquals(12,p.count(RamSuiteProtocol.Stage.REPEATABILITY))
        assertEquals(90,p.count(RamSuiteProtocol.Stage.SOAK))
        assertEquals(300L,p.PACE_MS)
        assertNull(p.gapMs(100L,90L))
        assertEquals(200L,p.gapMs(100L,300L))
    }
}
