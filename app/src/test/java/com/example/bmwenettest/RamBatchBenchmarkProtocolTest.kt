package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class RamBatchBenchmarkProtocolTest {
    private val p=RamBatchBenchmarkProtocol

    @Test fun requestsHaveOnlyKnownReadOnlyDids() {
        assertArrayEquals(byteArrayOf(0x22,0x4A,0x37),p.request(p.Kind.KNOCK))
        assertArrayEquals(byteArrayOf(0x22,0x4A,0x49),p.request(p.Kind.IGNITION))
        assertArrayEquals(byteArrayOf(0x22,0x4A,0x37,0x4A,0x49),p.request(p.Kind.BATCH))
        for(kind in p.Kind.values())assertEquals(0x22,p.request(kind)[0].toInt() and 255)
    }

    @Test fun matchesSinglesOnlyWithExactSidDidAndWidth() {
        val knock=byteArrayOf(0x62,0x4A,0x37,0x00,0x00,0xE8.toByte(),0xA4.toByte())
        val ignition=byteArrayOf(0x62,0x4A,0x49,0xFF.toByte(),0x1D)
        assertTrue(p.matches(p.Kind.KNOCK,knock))
        assertTrue(p.matches(p.Kind.IGNITION,ignition))
        assertFalse(p.matches(p.Kind.IGNITION,knock))
        assertFalse(p.matches(p.Kind.KNOCK,ignition))
        assertFalse(p.matches(p.Kind.KNOCK,knock+byteArrayOf(0x00)))
        assertFalse(p.matches(p.Kind.IGNITION,ignition.copyOfRange(0,4)))
    }

    @Test fun batchReplyRequiresBothDidsInOrderAndKnownWidths() {
        val correct=byteArrayOf(
            0x62,0x4A,0x37,0,0,0xE8.toByte(),0xA4.toByte(),
            0x4A,0x49,0xFF.toByte(),0x1D)
        assertTrue(p.matches(p.Kind.BATCH,correct))
        assertFalse(p.matches(p.Kind.BATCH,correct.copyOfRange(0,10)))
        assertFalse(p.matches(p.Kind.BATCH,correct+byteArrayOf(0)))
        val wrongSecond=correct.clone()
        wrongSecond[8]=0x37
        assertFalse(p.matches(p.Kind.BATCH,wrongSecond))
        val wrongFirst=correct.clone()
        wrongFirst[2]=0x49
        assertFalse(p.matches(p.Kind.BATCH,wrongFirst))
        val wrongService=correct.clone()
        wrongService[0]=0x63
        assertFalse(p.matches(p.Kind.BATCH,wrongService))
        assertFalse(p.matches(p.Kind.BATCH,byteArrayOf(0x7F,0x22,0x31)))
    }

    @Test fun requestOrderAlternatesAndAllIterationsAreBounded() {
        assertEquals(listOf(p.Kind.KNOCK,p.Kind.IGNITION,p.Kind.BATCH),p.order(0))
        assertEquals(listOf(p.Kind.BATCH,p.Kind.KNOCK,p.Kind.IGNITION),p.order(1))
        assertEquals(24,p.ITERATIONS)
        assertEquals(30_000L,p.MAX_RUN_MS)
    }

    @Test fun statsUseMedianAndNearestRankP95() {
        val vals=(1L..20L).toList()
        val s=p.stats(vals)!!
        assertEquals(20,s.count)
        assertEquals(10.5,s.medianMs,0.0001)
        assertEquals(19L,s.p95Ms)
        assertEquals(7.0,p.stats(listOf(7L))!!.medianMs,0.0001)
        assertNull(p.stats(emptyList()))
        assertNull(p.stats(listOf(-1L,2L)))
    }
}
