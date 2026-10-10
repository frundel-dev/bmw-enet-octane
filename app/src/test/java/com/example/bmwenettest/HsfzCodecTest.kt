package com.example.bmwenettest

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class HsfzCodecTest {
    private fun bytes(vararg values:Int)=values.map { it.toByte() }.toByteArray()

    @Test fun encodesLengthAddressesAndReadOnlyRequest() {
        assertArrayEquals(
            bytes(0,0,0,5,0,1,0xF4,0x12,0x22,0x4A,0x37),
            HsfzCodec.encode(bytes(0x22,0x4A,0x37))
        )
    }

    @Test fun extractsPayloadFromFragmentedTcpReads() {
        val raw=HsfzCodec.encode(bytes(0x62,0x4A,0x37,0x01,0x02))
        val split=object:InputStream() {
            var pos=0
            override fun read():Int=if(pos<raw.size)raw[pos++].toInt() and 255 else -1
            override fun read(b:ByteArray,off:Int,len:Int):Int {
                val value=read()
                if(value<0)return -1
                b[off]=value.toByte()
                return 1 // force TCP-style one-byte fragments
            }
        }
        val frames=HsfzCodec.readFrames(split)
        assertEquals(1,frames.size)
        assertArrayEquals(bytes(0x62,0x4A,0x37,1,2),HsfzCodec.payload(frames[0]))
    }

    @Test fun skipsNonDataFrameBeforeDataReply() {
        val control=bytes(0,0,0,2,0,2,0xF4,0x12)
        val reply=HsfzCodec.encode(bytes(0x41,0x0C,0x0C,0x80))
        val frames=HsfzCodec.readFrames(ByteArrayInputStream(control+reply))
        assertEquals(2,frames.size)
        assertNull(HsfzCodec.payload(frames[0]))
        assertArrayEquals(bytes(0x41,0x0C,0x0C,0x80),HsfzCodec.payload(frames[1]))
    }

    @Test fun neverPublishesTruncatedFrame() {
        val full=HsfzCodec.encode(bytes(0x62,0x4A,0x37,0x12,0x34))
        val truncated=full.copyOf(full.size-2)
        assertTrue(HsfzCodec.readFrames(ByteArrayInputStream(truncated)).isEmpty())
    }

    @Test fun malformedLengthsAreRejectedBeforeAllocation() {
        val invalid=bytes(0,1,0,1,0,1)
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(invalid))
            fail("Oversized HSFZ frame should throw IOException")
        } catch(_:IOException) { }
        assertNull(HsfzCodec.payload(bytes(0,0,0,8,0,1,0x12,0xF4,0x62)))
    }

    @Test fun requestSizeAndFrameCountHaveBounds() {
        try {
            HsfzCodec.encode(ByteArray(HsfzCodec.MAX_FRAME_BODY_BYTES))
            fail("Oversized request must be rejected")
        } catch(_:IllegalArgumentException) { }
        try {
            HsfzCodec.readFrames(ByteArrayInputStream(byteArrayOf()),9)
            fail("Excessive frame count must be rejected")
        } catch(_:IllegalArgumentException) { }
    }
}
