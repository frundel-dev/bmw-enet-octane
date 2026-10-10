package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

/** Protocol invariants: never misattribute one cylinder's DID value to another. */
class DmeBatchBoundaryRegressionTest {
    private fun b(vararg values:Int)=values.map { it.toByte() }.toByteArray()

    @Test fun preservesUnsignedBytesAndDidIdentityForAllFourCylinders() {
        val dids=DmeBatchParser.knockDids
        val response=ArrayList<Byte>()
        response.add(0x62)
        for((i,did) in dids.withIndex()) {
            response.add((did ushr 8).toByte())
            response.add(did.toByte())
            response.addAll(b(0x80+i,0xFF-i,i,0).toList())
        }
        val records=DmeBatchParser.parse(response.toByteArray(),dids,4)
        assertNotNull(records)
        assertEquals(dids,records!!.keys.toList())
        dids.forEachIndexed { i,did ->
            assertArrayEquals(b(0x80+i,0xFF-i,i,0),records[did])
        }
    }

    @Test fun rejectsEveryTruncationOfAnOtherwiseValidBatch() {
        val dids=listOf(0x4A49,0x4A4A,0x4A4C,0x4A4D)
        val response=b(0x62,
            0x4A,0x49,1,2,
            0x4A,0x4A,3,4,
            0x4A,0x4C,5,6,
            0x4A,0x4D,7,8)
        assertNotNull(DmeBatchParser.parse(response,dids,2))
        for(end in 0 until response.size) {
            assertNull("truncation=$end",
                DmeBatchParser.parse(response.copyOf(end),dids,2))
        }
        assertNull(DmeBatchParser.parse(response+b(0),dids,2))
    }

    @Test fun rejectsEveryDidCorruptionAndMisorderedRecord() {
        val dids=listOf(0x4A37,0x4A38,0x4A39,0x4A3A)
        val response=b(0x62,
            0x4A,0x37,1,2,
            0x4A,0x38,3,4,
            0x4A,0x39,5,6,
            0x4A,0x3A,7,8)
        for(pos in listOf(1,2,5,6,9,10,13,14)) {
            val corrupt=response.copyOf()
            corrupt[pos]=(corrupt[pos].toInt() xor 1).toByte()
            assertNull("DID corruption at $pos",DmeBatchParser.parse(corrupt,dids,2))
        }
        val misordered=response.copyOf()
        misordered[2]=0x38
        misordered[6]=0x37
        assertNull(DmeBatchParser.parse(misordered,dids,2))
    }

    @Test fun rejectsNegativeResponsesAndWrongServiceEvenWithValidLength() {
        val dids=listOf(0x4A37)
        assertNull(DmeBatchParser.parse(b(0x7F,0x22,0x31,0,0,0,0),dids,4))
        assertNull(DmeBatchParser.parse(b(0x61,0x4A,0x37,1,2,3,4),dids,4))
        assertNotNull(DmeBatchParser.parse(b(0x62,0x4A,0x37,1,2,3,4),dids,4))
    }

    @Test fun requestEncodesAllDidsInNetworkByteOrder() {
        assertArrayEquals(b(0x22,0x4A,0x49,0x4A,0x4A,0x4A,0x4C,0x4A,0x4D),
            DmeBatchParser.request(DmeBatchParser.ignitionDids))
    }
}
