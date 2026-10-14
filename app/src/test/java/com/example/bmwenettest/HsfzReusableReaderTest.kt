package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class HsfzReusableReaderTest {
    private fun frame(payload:ByteArray,type:Int=1):ByteArray {
        val length=payload.size+2
        return byteArrayOf(
            0,0,(length ushr 8).toByte(),length.toByte(),0,type.toByte(),
            0x12,0xF4.toByte())+payload
    }

    @Test fun positiveRamRpmUsesNoCopiedPayload() {
        val reader=HsfzReusableReader()
        assertEquals(3,reader.readDiagnostic(ByteArrayInputStream(
            frame(byteArrayOf(0x63,0xB8.toByte(),0x02)))))
        assertEquals(696,reader.rpm())
        assertEquals(3,reader.readDiagnostic(ByteArrayInputStream(
            frame(byteArrayOf(0x63,0xBC.toByte(),0x02)))))
        assertEquals(700,reader.rpm())
    }

    @Test fun ignoresNonDiagnosticEnvelopeAndParsesNrc() {
        val input=ByteArrayInputStream(
            byteArrayOf(0,0,0,0,0,2)+frame(byteArrayOf(0x7F,0x23,0x31)))
        val reader=HsfzReusableReader()
        assertEquals(3,reader.readDiagnostic(input,expectedRequest=HsfzCodec.encode(byteArrayOf(0x23,0x14))))
        assertNull(reader.rpm())
        assertEquals(0x31,reader.ramNegativeCode())
    }

    @Test fun ramNackFailsClosed() {
        val reader=HsfzReusableReader()
        try {
            reader.readDiagnostic(ByteArrayInputStream(byteArrayOf(0,0,0,0,0,0xFF.toByte())),
                expectedRequest=HsfzCodec.encode(byteArrayOf(0x23,0x14)))
            fail("NACK must invalidate RAM stream")
        }catch(e:IOException){assertTrue(e.message!!.contains("NACK"))}
    }

    @Test fun ramAliveGetsResponseWithoutExtraEcuRequest() {
        val reader=HsfzReusableReader()
        val alive=byteArrayOf(0,0,0,2,0,0x12,0x12,0xF4.toByte())
        var response:ByteArray?=null
        val size=reader.readDiagnostic(ByteArrayInputStream(
            alive+frame(byteArrayOf(0x63,0xB8.toByte(),2))),
            expectedRequest=HsfzCodec.encode(byteArrayOf(0x23,0x14)),
            onAlive={response=it})
        assertEquals(3,size)
        assertEquals(696,reader.rpm())
        assertArrayEquals(byteArrayOf(0,0,0,2,0,0x13,0,0xF4.toByte()),response)
    }

    @Test fun rejectsTruncatedResponse() {
        val bytes=frame(byteArrayOf(0x63,1,2))
        val reader=HsfzReusableReader()
        try {
            reader.readDiagnostic(ByteArrayInputStream(bytes.copyOf(bytes.size-1)))
            fail("truncated frame must fail closed")
        } catch(e:IOException) {
            assertTrue(e.message!!.contains("Incomplete"))
        }
    }

    @Test fun rejectsInvalidLengthAndResetsPreviousResult() {
        val reader=HsfzReusableReader()
        reader.readDiagnostic(ByteArrayInputStream(frame(byteArrayOf(0x63,1,2))))
        assertEquals(3,reader.readDiagnostic(ByteArrayInputStream(frame(byteArrayOf(0x63,1,2)))))
        try {
            reader.readDiagnostic(ByteArrayInputStream(byteArrayOf(0,0,0,1,0,1)))
            fail("invalid size")
        } catch(e:IOException) { assertTrue(e.message!!.contains("Invalid")) }
        assertNull(reader.rpm())
    }

    @Test fun partialHeaderAndEndOfStreamAreDistinct() {
        val reader=HsfzReusableReader()
        assertNull(reader.readDiagnostic(ByteArrayInputStream(byteArrayOf())))
        try {
            reader.readDiagnostic(ByteArrayInputStream(byteArrayOf(0,0,0)))
            fail("partial header")
        } catch(e:IOException) { assertTrue(e.message!!.contains("Incomplete")) }
    }
}
