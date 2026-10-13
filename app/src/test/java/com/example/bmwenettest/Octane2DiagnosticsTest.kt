package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class Octane2DiagnosticsTest {
    @Test fun ramRequiresManualSoftwareIdentityAndParkedIdleValidation() {
        val denied=Octane2RamPolicy(false)
        assertFalse(denied.canRead(750.0,0.0,true))
        assertEquals(Octane2RamPolicy.State.LOCKED,denied.state)
        val policy=Octane2RamPolicy(true)
        assertFalse(policy.canRead(750.0,0.0,false))
        assertFalse(policy.canRead(750.0,1.0,true))
        assertFalse(policy.canRead(2300.0,0.0,true))
        assertTrue(policy.canRead(750.0,0.0,true))
        assertTrue(policy.accept(765.0,750.0))
        assertEquals(Octane2RamPolicy.State.ACTIVE,policy.state)
        assertTrue(policy.canRead(2700.0,53.0,true))
        policy.onReconnect()
        assertEquals(Octane2RamPolicy.State.NEEDS_PARKED_CHECK,policy.state)
        assertFalse(policy.canRead(2700.0,53.0,true))
        assertTrue(policy.canRead(770.0,0.0,true))
    }

    @Test fun ramMismatchDisablesAndNeverRetriesInSameLoggerStart() {
        val policy=Octane2RamPolicy(true)
        assertTrue(policy.canRead(800.0,0.0,true))
        assertFalse(policy.accept(1300.0,800.0))
        assertEquals(Octane2RamPolicy.State.DISABLED_UNTIL_RESTART,policy.state)
        assertFalse(policy.canRead(800.0,0.0,true))
        val verified=Octane2RamPolicy(true)
        assertTrue(verified.accept(810.0,800.0))
        assertFalse(verified.accept(null,1300.0))
        assertEquals(Octane2RamPolicy.State.DISABLED_UNTIL_RESTART,verified.state)
    }

    @Test fun widebandPidCapabilitiesMustBeBitExact() {
        // 01 20 bitmap covers 0x21..0x40, bit 28 for PID 0x24,
        // bit 12 for 0x34.
        val mask=byteArrayOf(0x10,0x00,0x10,0x00)
        assertTrue(Octane2Lambda.supported(mask,0x24))
        assertTrue(Octane2Lambda.supported(mask,0x34))
        assertFalse(Octane2Lambda.supported(mask,0x25))
        assertFalse(Octane2Lambda.supported(null,0x34))
        val mask00=byteArrayOf(0x20,0x00,0x00,0x01)
        assertTrue(Octane2Lambda.supportedBase(mask00,0x03))
        assertFalse(Octane2Lambda.supportedBase(mask00,0x04))
    }

    @Test fun actualOxygenSensorPid34And24AreDistinctFromCommanded44() {
        val payload=byteArrayOf(0x80.toByte(),0,0x80.toByte(),0)
        val actual34=Octane2Lambda.decode(0x34,payload)!!
        assertEquals(1.0,actual34.lambda,0.000001)
        assertEquals(0.0,actual34.auxiliary,0.000001)
        val actual24=Octane2Lambda.decode(0x24,payload)!!
        assertEquals(1.0,actual24.lambda,0.000001)
        assertEquals(4.0,actual24.auxiliary,0.000001)
        assertEquals(1.0,Octane2Lambda.commandedEquivalence(
            byteArrayOf(0x80.toByte(),0))!!,0.000001)
        assertNull(Octane2Lambda.decode(0x44,payload))
        assertNull(Octane2Lambda.decode(0x34,payload.copyOfRange(0,2)))
        assertNull(Octane2Lambda.decode(0x24,byteArrayOf(0,0,0,0)))
        assertNull(Octane2Lambda.commandedEquivalence(null))
    }

    @Test fun fuelControlStatusIsUnavailableWhenMalformed() {
        assertEquals("ENGINE_OFF",Octane2Lambda.controlStatus(byteArrayOf(0,0)))
        assertEquals("OPEN_INSUFFICIENT_TEMP",Octane2Lambda.controlStatus(byteArrayOf(1,0)))
        assertEquals("CLOSED_LOOP",Octane2Lambda.controlStatus(byteArrayOf(2,0)))
        assertEquals("OPEN_LOAD_OR_DECEL",Octane2Lambda.controlStatus(byteArrayOf(4,0)))
        assertEquals("CLOSED_LOOP_O2_FAULT",Octane2Lambda.controlStatus(byteArrayOf(16,0)))
        assertNull(Octane2Lambda.controlStatus(byteArrayOf(0x03,0)))
        assertNull(Octane2Lambda.controlStatus(byteArrayOf(1)))
    }

    @Test fun obdResponseCorrelationRejectsWrongSensorAndLengths() {
        assertArrayEquals(byteArrayOf(0x80.toByte(),0,0,0),
            DiagnosticResponse.obd(byteArrayOf(0x41,0x34,0x80.toByte(),0,0,0),0x34))
        assertNull(DiagnosticResponse.obd(byteArrayOf(0x41,0x24,0,0,0,0),0x34))
        assertNull(DiagnosticResponse.obd(byteArrayOf(0x41,0x34,0,0),0x34))
        assertNull(DiagnosticResponse.obd(byteArrayOf(0x7F,0x01,0x31),0x34))
    }
}
