package com.example.bmwenettest

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DiagnosticWireTraceTest {
    private fun b(vararg n:Int)=n.map{it.toByte()}.toByteArray()
    @Test fun ackIsDifferentFromDataAndDecode() {
        val req=HsfzCodec.encode(b(0x22,0x4A,0x37))
        val ack=b(0,0,0,2,0,2,0xF4,0x12)
        val reply=HsfzCodec.encode(b(0x62,0x4A,0x37,0x12))
        var ackAt:Long?=null
        var dataStart:Long?=null
        var dataEnd:Long?=null
        val results=HsfzCodec.readFrames(ByteArrayInputStream(ack+reply),
            expectedRequest=req,
            onAckAtNs={ackAt=it},
            onDataHeaderAtNs={dataStart=it},
            onDataCompleteAtNs={dataEnd=it})
        assertEquals(1,results.size)
        assertNotNull(ackAt)
        assertNotNull(dataStart)
        assertNotNull(dataEnd)
        assertTrue(ackAt!!<=dataStart!!)
        assertTrue(dataStart!!<=dataEnd!!)
        assertArrayEquals(b(0x62,0x4A,0x37,0x12),
            HsfzCodec.payload(results[0]))
    }
    @Test fun noAckDoesNotInventAckTimestamp() {
        var a:Long?=null
        var d:Long?=null
        val r=HsfzCodec.readFrames(ByteArrayInputStream(
            HsfzCodec.encode(b(0x41,0x0C,0x10,0x00))),
            onAckAtNs={a=it},onDataCompleteAtNs={d=it})
        assertEquals(1,r.size)
        assertNull(a)
        assertNotNull(d)
    }
    @Test fun rejectedAckNeverEmitsVerifiedAck() {
        var a:Long?=null
        val req=HsfzCodec.encode(b(0x22,0x4A,0x37))
        val ack=b(0,0,0,2,0,2,0x01,0x01)
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(ack),
                expectedRequest=req,onAckAtNs={a=it})
            fail("ACK correlation must reject")
        }catch(_:IOException){}
        assertNull(a)
    }
    @Test fun wireCsvKeepsMissingAckBlankAndHasCompleteSchema() {
        val tr=DiagnosticWireTrace()
        tr.record(DiagnosticWireTrace.Tx(11L,"DID_4A37",100,120,130,
            null,150,180,190,true))
        val row=tr.flush(1,0,4,200)!!
        val parts=row.split(",")
        assertEquals(DiagnosticWireTrace.HEADER.split(",").size,parts.size)
        assertEquals("",parts[8])
        assertEquals("false",parts[15])
        assertTrue(parts[17].isNotBlank())
        assertNull(tr.flush(1,0,5,300))
    }
    @Test fun boundedTraceDoesNotGrowUnbounded() {
        val tr=DiagnosticWireTrace()
        repeat(140) {i->tr.record(DiagnosticWireTrace.Tx(i.toLong(),
            "OBD_0C",0,1,2,null,3,4,5,true)) }
        assertEquals(12,tr.dropped)
        assertEquals(128,tr.flush(1,0,1,6)!!.lines().size)
    }
}
