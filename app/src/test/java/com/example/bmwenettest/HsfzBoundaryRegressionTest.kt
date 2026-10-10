package com.example.bmwenettest

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test

/** Regression coverage for TCP streaming and hostile or incomplete HSFZ input. */
class HsfzBoundaryRegressionTest {
    private fun b(vararg values:Int)=values.map { it.toByte() }.toByteArray()

    @Test fun fullFrameSurvivesEveryPossibleTcpSplit() {
        val frame=HsfzCodec.encode(b(0x62,0x4A,0x37,0xFF,0x80,0))
        for(split in 1 until frame.size) {
            val input=object:InputStream() {
                var offset=0
                override fun read():Int=if(offset==frame.size)-1 else frame[offset++].toInt() and 255
                override fun read(dst:ByteArray,off:Int,len:Int):Int {
                    if(offset==frame.size)return -1
                    val n=minOf(len,if(offset<split)split-offset else frame.size-offset)
                    System.arraycopy(frame,offset,dst,off,n)
                    offset+=n
                    return n
                }
            }
            val decoded=HsfzCodec.readFrames(input)
            assertEquals("split=$split",1,decoded.size)
            assertArrayEquals("split=$split",b(0x62,0x4A,0x37,0xFF,0x80,0),
                HsfzCodec.payload(decoded.single()))
        }
    }

    @Test fun eofAtEveryByteCannotPublishPartialFrame() {
        val frame=HsfzCodec.encode(b(0x62,0x4A,0x37,0x12,0x34))
        for(length in 0 until frame.size) {
            assertTrue("truncation=$length",
                HsfzCodec.readFrames(ByteArrayInputStream(frame.copyOf(length))).isEmpty())
        }
    }

    @Test fun incompleteSecondFrameNeverBecomesDiagnosticData() {
        val control=b(0,0,0,2,0,2,0xF4,0x12)
        val reply=HsfzCodec.encode(b(0x62,0x4A,0x37,1,2))
        val decoded=HsfzCodec.readFrames(ByteArrayInputStream(control+reply.copyOf(9)))
        assertEquals(1,decoded.size)
        assertNull(HsfzCodec.payload(decoded.single()))
    }

    @Test fun timeoutWhileReadingBodyNeverPublishesPartialFrame() {
        val frame=HsfzCodec.encode(b(0x62,0x4A,0x37,1,2))
        val input=object:InputStream() {
            var offset=0
            override fun read():Int=throw SocketTimeoutException("simulated timeout")
            override fun read(dst:ByteArray,off:Int,len:Int):Int {
                if(offset==9)throw SocketTimeoutException("simulated timeout")
                val n=minOf(len,9-offset)
                System.arraycopy(frame,offset,dst,off,n)
                offset+=n
                return n
            }
        }
        assertTrue(HsfzCodec.readFrames(input).isEmpty())
    }

    @Test fun declaredLengthMustMatchActualBytesAndDiagnosticType() {
        val valid=HsfzCodec.encode(b(0x62,0x4A,0x37))
        assertNull(HsfzCodec.payload(valid+b(0)))
        assertNull(HsfzCodec.payload(valid.copyOf(valid.size-1)))
        assertNull(HsfzCodec.payload(valid.copyOf().also { it[5]=2 }))
        for(invalidLength in listOf(0,1,HsfzCodec.MAX_FRAME_BODY_BYTES+1)) {
            val header=b(
                invalidLength ushr 24,invalidLength ushr 16,
                invalidLength ushr 8,invalidLength,0,1
            )
            try {
                HsfzCodec.readFrames(ByteArrayInputStream(header))
                fail("invalid length $invalidLength accepted")
            } catch(_:IOException) { }
        }
    }

    @Test fun maximumRequestSizeIsAcceptedWithoutOverflow() {
        val payload=ByteArray(HsfzCodec.MAX_FRAME_BODY_BYTES-2) { 0x55 }
        val frame=HsfzCodec.encode(payload)
        assertEquals(HsfzCodec.MAX_FRAME_BODY_BYTES+6,frame.size)
        assertArrayEquals(payload,HsfzCodec.payload(frame))
    }
}
