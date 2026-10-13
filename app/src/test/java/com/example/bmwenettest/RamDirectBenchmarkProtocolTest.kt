package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class RamDirectBenchmarkProtocolTest {
    private val p=RamDirectBenchmarkProtocol

    @Test fun onlyProvenRpmRamAddressAndStandardObdPidsAllowed() {
        assertArrayEquals(byteArrayOf(0x23,0x14,0x60,0,0x3B,0x64,2),
            p.rpmRequest(RamDirectBenchmarkProtocol.Source.DIRECT_RAM))
        assertArrayEquals(byteArrayOf(0x01,0x0C),
            p.rpmRequest(RamDirectBenchmarkProtocol.Source.OBD))
        assertArrayEquals(byteArrayOf(0x01,0x0D),p.speedRequest())
    }

    @Test fun boundedAlternatingBlocks() {
        assertEquals(4,p.ROUNDS)
        assertEquals(4,p.SAMPLES_PER_BLOCK)
        assertEquals(16,p.TOTAL_PER_SOURCE)
        assertEquals(150L,p.PACE_MS)
        assertEquals(35_000L,p.MAX_RUN_MS)
        assertEquals(listOf(RamDirectBenchmarkProtocol.Source.DIRECT_RAM,
            RamDirectBenchmarkProtocol.Source.OBD),p.order(0))
        assertEquals(listOf(RamDirectBenchmarkProtocol.Source.OBD,
            RamDirectBenchmarkProtocol.Source.DIRECT_RAM),p.order(1))
        assertEquals(p.order(0),p.order(2))
        assertEquals(p.order(1),p.order(3))
        assertThrows(IllegalArgumentException::class.java) {p.order(-1)}
        assertThrows(IllegalArgumentException::class.java) {p.order(4)}
    }

    @Test fun positivePayloadsRequireExactLengthAndService() {
        val ram=byteArrayOf(0x63,0xC9.toByte(),0x02)
        assertEquals(713.0,p.decodeRpm(RamDirectBenchmarkProtocol.Source.DIRECT_RAM,ram)!!,0.001)
        assertNull(p.decodeRpm(RamDirectBenchmarkProtocol.Source.DIRECT_RAM,
            byteArrayOf(0x7F,0x23,0x31)))
        assertNull(p.decodeRpm(RamDirectBenchmarkProtocol.Source.DIRECT_RAM,
            ram+byteArrayOf(0)))
        assertNull(p.decodeRpm(RamDirectBenchmarkProtocol.Source.DIRECT_RAM,
            byteArrayOf(0x63,0xC9.toByte())))
        val obd=byteArrayOf(0x41,0x0C,0x0B,0x24)
        assertEquals(713.0,p.decodeRpm(RamDirectBenchmarkProtocol.Source.OBD,obd)!!,0.001)
        assertNull(p.decodeRpm(RamDirectBenchmarkProtocol.Source.OBD,
            byteArrayOf(0x41,0x0D,0)))
        assertNull(p.decodeRpm(RamDirectBenchmarkProtocol.Source.OBD,
            obd+byteArrayOf(0)))
    }

    @Test fun idleGuardRejectsOutOfRangeAndDrift() {
        assertTrue(p.isValidIdleSample(718.0,715.0))
        assertFalse(p.isValidIdleSample(920.0,715.0))
        assertFalse(p.isValidIdleSample(0.0,715.0))
        assertFalse(p.isValidIdleSample(null,715.0))
        assertFalse(p.isValidIdleSample(Double.NaN,715.0))
        assertFalse(p.isValidIdleSample(715.0,Double.NaN))
    }

    @Test fun rateIncludesElapsedPacingNotJustSingleTransactionTime() {
        assertEquals(4.0,p.observedRateHz(4,1000L)!!,0.0001)
        assertEquals(8.0,p.observedRateHz(4,500L)!!,0.0001)
        assertNull(p.observedRateHz(0,1000))
        assertNull(p.observedRateHz(5,1000))
        assertNull(p.observedRateHz(4,0))
    }
}
