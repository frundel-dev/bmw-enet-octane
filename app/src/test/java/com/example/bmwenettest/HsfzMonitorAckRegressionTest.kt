package com.example.bmwenettest

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/**
 * The DME Monitor uses a normal 0x22 HSFZ request and must supply its
 * outgoing packet to the shared decoder (same rule as measuredDiagnostic).
 */
class HsfzMonitorAckRegressionTest {
    private fun frame(type:Int, body:ByteArray):ByteArray {
        val length=body.size
        return byteArrayOf(
            (length ushr 24).toByte(),(length ushr 16).toByte(),
            (length ushr 8).toByte(),length.toByte(),0,type.toByte()
        )+body
    }

    @Test fun matchedAckFollowedByMonitorDidReply() {
        val packet=HsfzCodec.encode(byteArrayOf(0x22,0x55,0x4A))
        val ack=frame(0x02,byteArrayOf(0xF4.toByte(),0x12))
        val positive=frame(0x01,byteArrayOf(0x12,0xF4.toByte(),
            0x62,0x55,0x4A,0x00,0x64))
        val payload=HsfzCodec.readFrames(ByteArrayInputStream(ack+positive),
            expectedRequest=packet).mapNotNull(HsfzCodec::payload).single()
        assertArrayEquals(byteArrayOf(0x62,0x55,0x4A,0x00,0x64),payload)
    }

    @Test fun monitorAckForAnotherAddressIsRejected() {
        val packet=HsfzCodec.encode(byteArrayOf(0x22,0x55,0x4A))
        val foreignAck=frame(0x02,byteArrayOf(0xF4.toByte(),0x11))
        val positive=frame(0x01,byteArrayOf(0x12,0xF4.toByte(),
            0x62,0x55,0x4A,0x00,0x64))
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(foreignAck+positive),
                expectedRequest=packet)
            fail("No reply may be accepted after uncorrelated ACK")
        }catch(e:IOException){
            assertTrue(e.message!!.contains("ACK"))
        }
    }

    @Test fun missingResponseAfterMonitorAckIsFatal() {
        val packet=HsfzCodec.encode(byteArrayOf(0x22,0x55,0x4A))
        val ack=frame(0x02,byteArrayOf(0xF4.toByte(),0x12))
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(ack),
                expectedRequest=packet)
            fail("An ACK is not diagnostic data")
        }catch(_:IOException){}
    }
}
