package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class MixedDidBatchTest {
    private fun b(vararg items:Int)=items.map{it.toByte()}.toByteArray()
    private fun full():ByteArray {
        val bytes=ArrayList<Byte>()
        bytes.add(0x62)
        for((did,width) in MixedDidBatch.widths) {
            bytes.add((did ushr 8).toByte())
            bytes.add(did.toByte())
            repeat(width){ i->bytes.add((i+1).toByte()) }
        }
        return bytes.toByteArray()
    }

    @Test fun encodesEightDidsInOneReadOnlyRequest() {
        assertEquals(8,MixedDidBatch.dids.size)
        val req=MixedDidBatch.request()
        assertEquals(17,req.size)
        assertEquals(0x22,req[0].toInt() and 255)
    }

    @Test fun decodesExactlyOrderedMixedWidths() {
        val decoded=MixedDidBatch.parse(full())
        assertNotNull(decoded)
        assertEquals(8,decoded!!.size)
        assertArrayEquals(b(1,2,3,4),decoded[0x4A37])
        assertArrayEquals(b(1,2),decoded[0x4A4D])
    }

    @Test fun rejectsAllTruncationsAndTrailingData() {
        val payload=full()
        for(i in 0 until payload.size)assertNull("len $i",MixedDidBatch.parse(payload.copyOf(i)))
        assertNull(MixedDidBatch.parse(payload+b(0)))
    }

    @Test fun rejectsNegativeWrongServiceAndReorderedCylinder() {
        val payload=full()
        assertNull(MixedDidBatch.parse(b(0x7F,0x22,0x31)))
        assertNull(MixedDidBatch.parse(payload.copyOf().also { it[0]=0x61 }))
        assertNull(MixedDidBatch.parse(payload.copyOf().also { it[2]=0x38 }))
        assertNull(MixedDidBatch.parse(payload.copyOf().also { it[34]=0x50 }))
    }
}
