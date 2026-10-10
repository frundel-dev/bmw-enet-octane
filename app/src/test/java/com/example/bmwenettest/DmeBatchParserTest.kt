package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DmeBatchParserTest {
    private fun bytes(vararg n:Int)=n.map { it.toByte() }.toByteArray()
    private val dids=listOf(0x4A37,0x4A38,0x4A39,0x4A3A)

    @Test fun createsSingleReadOnlyUdsRequestForFourDids() {
        assertArrayEquals(
            bytes(0x22,0x4A,0x37,0x4A,0x38,0x4A,0x39,0x4A,0x3A),
            DmeBatchParser.request(dids)
        )
    }

    @Test fun decodesFourOrderedBigEndianRecordsExactly() {
        val reply=bytes(
            0x62,
            0x4A,0x37,1,2,3,4,
            0x4A,0x38,5,6,7,8,
            0x4A,0x39,9,10,11,12,
            0x4A,0x3A,13,14,15,16
        )
        val parsed=DmeBatchParser.parse(reply,dids,4)
        assertNotNull(parsed)
        assertEquals(dids,parsed!!.keys.toList())
        assertArrayEquals(bytes(9,10,11,12),parsed[0x4A39])
    }

    @Test fun rejectsSwappedDidWithoutMisassigningCylinder() {
        val reply=bytes(0x62,0x4A,0x38,1,2,0x4A,0x37,3,4)
        assertNull(DmeBatchParser.parse(reply,listOf(0x4A37,0x4A38),2))
    }

    @Test fun rejectsSingleDidResponseToBatch() {
        assertNull(DmeBatchParser.parse(
            bytes(0x62,0x4A,0x37,1,2,3,4),dids,4))
    }

    @Test fun rejectsTruncationTrailingDataAndNegativeReply() {
        val good=bytes(0x62,0x4A,0x49,0x00,0xFE)
        assertNotNull(DmeBatchParser.parse(good,listOf(0x4A49),2))
        assertNull(DmeBatchParser.parse(good.copyOf(4),listOf(0x4A49),2))
        assertNull(DmeBatchParser.parse(good+bytes(0),listOf(0x4A49),2))
        assertNull(DmeBatchParser.parse(bytes(0x7F,0x22,0x31),listOf(0x4A49),2))
    }

    @Test fun rejectsEmptyAndExcessivelyLargeBatches() {
        for(ids in listOf(emptyList(),List(9){0x4A37})) {
            try {
                DmeBatchParser.request(ids)
                fail("Batch cardinality must be bounded")
            } catch(_:IllegalArgumentException) { }
        }
    }
}
