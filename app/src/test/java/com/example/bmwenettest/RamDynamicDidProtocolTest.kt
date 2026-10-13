package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class RamDynamicDidProtocolTest {
    private val p=RamDynamicDidProtocol

    @Test fun onlyRpmMemoryCanBeDefinedInSelectedA2l() {
        assertTrue(p.sourceMatches())
        assertArrayEquals(byteArrayOf(
            0x2C,0x02,0xF3.toByte(),0xEF.toByte(),
            0x14,0x60,0,0x3B,0x64,0x02),p.defineRequest())
        assertEquals(6,p.SAMPLES)
        assertEquals(30_000L,p.MAX_EXPERIMENT_MS)
    }

    @Test fun preflightRequiresExactVacantNrc31() {
        assertArrayEquals(byteArrayOf(0x22,0xF3.toByte(),0xEF.toByte()),p.preflightRequest())
        assertTrue(p.isVacantRead(byteArrayOf(0x7F,0x22,0x31)))
        assertFalse(p.isVacantRead(byteArrayOf(0x7F,0x22,0x33)))
        assertFalse(p.isVacantRead(byteArrayOf(0x7F,0x2C,0x31)))
        assertFalse(p.isVacantRead(byteArrayOf(0x62,0xF3.toByte(),0xEF.toByte(),0,0)))
        assertFalse(p.isVacantRead(byteArrayOf(0x7F,0x22,0x31,0)))
    }

    @Test fun defineReplyMustConfirmSameSubfunctionAndDid() {
        assertTrue(p.defineResponseMatches(byteArrayOf(0x6C,0x02,0xF3.toByte(),0xEF.toByte())))
        assertFalse(p.defineResponseMatches(byteArrayOf(0x6C,0x03,0xF3.toByte(),0xEF.toByte())))
        assertFalse(p.defineResponseMatches(byteArrayOf(0x6C,0x02,0xF3.toByte(),0xEE.toByte())))
        assertFalse(p.defineResponseMatches(byteArrayOf(0x7F,0x2C,0x31)))
        assertFalse(p.defineResponseMatches(byteArrayOf(0x6C,0x02,0xF3.toByte())))
    }

    @Test fun readAcceptsOnlyExactTwoByteNkwValue() {
        assertEquals(713,p.readRpm(byteArrayOf(0x62,0xF3.toByte(),0xEF.toByte(),
            0xC9.toByte(),0x02)))
        assertNull(p.readRpm(byteArrayOf(0x62,0xF3.toByte(),0xEF.toByte(),0)))
        assertNull(p.readRpm(byteArrayOf(0x62,0xF3.toByte(),0xEF.toByte(),0,0,0)))
        assertNull(p.readRpm(byteArrayOf(0x62,0xF3.toByte(),0xEE.toByte(),0,0)))
        assertNull(p.readRpm(byteArrayOf(0x7F,0x22,0x31)))
    }

    @Test fun cleanupIsTargetedAndRequiresMatchingPositiveReply() {
        assertArrayEquals(byteArrayOf(0x2C,0x03,0xF3.toByte(),0xEF.toByte()),p.clearRequest())
        assertTrue(p.clearResponseMatches(byteArrayOf(0x6C,0x03,0xF3.toByte(),0xEF.toByte())))
        assertFalse(p.clearResponseMatches(byteArrayOf(0x6C,0x02,0xF3.toByte(),0xEF.toByte())))
        assertFalse(p.clearResponseMatches(byteArrayOf(0x6C,0x03)))
        assertFalse(p.clearResponseMatches(byteArrayOf(0x7F,0x2C,0x31)))
    }
}
