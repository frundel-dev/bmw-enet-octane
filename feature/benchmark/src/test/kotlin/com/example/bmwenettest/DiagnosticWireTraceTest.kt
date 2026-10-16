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
    @Test fun lostDiagnosticDataAfterVerifiedAckRecordsExactPidAndWaitPhase() {
        val request=HsfzCodec.encode(b(0x01,0x0D))
        val ack=b(0,0,0,2,0,2,0xF4,0x12)
        var phase=HsfzCodec.Phase.WAIT_ACK_OR_DATA
        var phaseAt=0L
        var ackAt:Long?=null
        var headerAt:Long?=null
        var completeAt:Long?=null
        var caught:IOException?=null
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(ack),
                expectedRequest=request,
                onAckAtNs={ackAt=it},
                onDataHeaderAtNs={headerAt=it},
                onDataCompleteAtNs={completeAt=it},
                onPhase={p,ns->phase=p;phaseAt=ns})
            fail("No diagnostic DATA: incomplete transaction must throw")
        } catch(e:IOException) { caught=e }
        assertNotNull(caught)
        assertEquals(HsfzCodec.Phase.WAIT_DATA,phase)
        assertNotNull(ackAt)
        assertNull("ACK is never DATA",headerAt)
        assertNull(completeAt)
        assertTrue(phaseAt>=ackAt!!)

        val trace=DiagnosticWireTrace()
        val stopped=System.nanoTime()
        trace.record(DiagnosticWireTrace.Tx(
            wallMs=1_700_000_000_000L,channel="OBD_0D",
            requestStartNs=100L,sendStartNs=110L,sendEndNs=120L,
            ackVerifiedNs=ackAt,dataHeaderFirstReadNs=headerAt,
            dataCompleteNs=completeAt,decodeEndNs=null,valid=false,
            error="IOException",requestedIds=listOf(0x0D),
            requestSid=0x01,recvPhase=phase.name,phaseAtNs=phaseAt,
            failedAtNs=stopped,exceptionClass=IOException::class.java.name,
            exceptionMessage=caught!!.message!!,unfinished=true))
        val row=trace.flush(1,4,19L,stopped)!!
        val fields=row.split(",")
        assertEquals(DiagnosticWireTrace.HEADER.split(",").size,fields.size)
        assertEquals("PID",fields[19])
        assertEquals("0x01",fields[20])
        assertEquals("0x0D",fields[21])
        assertEquals("WAIT_DATA",fields[22])
        assertEquals("java.io.IOException",fields[25])
        assertEquals("HSFZ EOF before diagnostic response",fields[26])
        assertEquals("true",fields[27])
        assertEquals("",fields[9]) // DATA did not start
        assertEquals("",fields[10]) // DATA did not finish
    }

    @Test fun lostPartialDidDataPreservesHeaderAndDataBodyPhase() {
        val request=HsfzCodec.encode(b(0x22,0x4A,0x37))
        val ack=b(0,0,0,2,0,2,0xF4,0x12)
        // Complete DATA header declares 6 body bytes; only addresses arrive.
        val partial=b(0,0,0,6,0,1,0x12,0xF4)
        var ackAt:Long?=null
        var headerAt:Long?=null
        var completeAt:Long?=null
        var phase=HsfzCodec.Phase.WAIT_ACK_OR_DATA
        var phaseNs=0L
        var err:IOException?=null
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(ack+partial),
                expectedRequest=request,
                onAckAtNs={ackAt=it},
                onDataHeaderAtNs={headerAt=it},
                onDataCompleteAtNs={completeAt=it},
                onPhase={p,ns->phase=p;phaseNs=ns})
            fail("Truncated DATA body must throw IOException")
        } catch(e:IOException) { err=e }
        assertNotNull(err)
        assertEquals(HsfzCodec.Phase.DATA_BODY,phase)
        assertNotNull(ackAt)
        assertNotNull(headerAt)
        assertNull(completeAt)
        assertTrue(phaseNs>=headerAt!!)
        assertTrue(err!!.message!!.contains("Incomplete HSFZ frame on EOF"))
        val trace=DiagnosticWireTrace()
        trace.record(DiagnosticWireTrace.Tx(
            5L,"DID_4A37",1L,2L,3L,ackAt,headerAt,completeAt,
            null,false,"IOException",listOf(0x4A37),0x22,phase.name,
            phaseNs,System.nanoTime(),IOException::class.java.name,
            err!!.message!!,true))
        val row=trace.flush(3,2,7L,System.nanoTime())!!.split(",")
        assertEquals(DiagnosticWireTrace.HEADER.split(",").size,row.size)
        assertEquals("DID",row[19])
        assertEquals("0x22",row[20])
        assertEquals("0x4A37",row[21])
        assertEquals("DATA_BODY",row[22])
        assertTrue(row[9].isNotEmpty())
        assertEquals("",row[10])
        assertEquals("true",row.last())
    }

    @Test fun failureRowSurvivesBoundedTraceCapacity() {
        val trace=DiagnosticWireTrace()
        repeat(128){i ->
            trace.record(DiagnosticWireTrace.Tx(i.toLong(),"OBD_0C",
                1L,2L,3L,null,4L,5L,6L,true))
        }
        trace.record(DiagnosticWireTrace.Tx(
            999L,"DID_4A37",100L,101L,102L,null,null,null,null,
            false,"IOException",listOf(0x4A37),0x22,"WAIT_DATA",
            103L,104L,"java.io.IOException","lost ECU answer",true))
        val flushed=trace.flush(2,1,7L,105L)!!.lines()
        assertEquals(128,flushed.size)
        assertEquals(1,trace.dropped)
        assertTrue(flushed.last().contains("lost ECU answer"))
        assertTrue(flushed.last().endsWith(",true"))
    }

    @Test fun boundedTraceDoesNotGrowUnbounded() {
        val tr=DiagnosticWireTrace()
        repeat(140) {i->tr.record(DiagnosticWireTrace.Tx(i.toLong(),
            "OBD_0C",0,1,2,null,3,4,5,true)) }
        assertEquals(12,tr.dropped)
        assertEquals(128,tr.flush(1,0,1,6)!!.lines().size)
    }
}
