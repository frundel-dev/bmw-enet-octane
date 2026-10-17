package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class GroupedUds22ResearchTest {
    private val s=GroupedUds22Research.Schema(listOf(
        0x4807 to 2,0x5813 to 1,0x580B to 2,0x4A36 to 1,
        0x4A37 to 4,0x4A38 to 4,0x4A39 to 4,0x4A3A to 4,
        0x4A49 to 2,0x4A4A to 2,0x4A4C to 2,0x4A4D to 2
    ))
    private fun positive(schema:GroupedUds22Research.Schema=s):ByteArray {
        val out=mutableListOf(0x62)
        for((id,n) in schema.widths) {
            out.add(id ushr 8)
            out.add(id and 255)
            repeat(n){out.add((it+1) and 255)}
        }
        return out.map { it.toByte() }.toByteArray()
    }
    @Test fun unresolvedDidsCannotGainPermissionsOrAssumedLengths() {
        assertEquals(12,GroupedUds22Research.catalog.size)
        assertEquals(setOf(0x4807,0x5813,0x580B),
            GroupedUds22Research.unresolved().map{it.did}.toSet())
        assertEquals(null,GroupedUds22Research.catalog.first{it.did==0x4807}.familyWidth)
        assertFalse(GroupedUds22Research.activeGroupAllowed())
        for(c in GroupedUds22Research.catalog)
            assertFalse(GroupedUds22Research.liveCandidateAllowed(c.did))
        assertEquals(9,GroupedUds22Research.knownSchema().widths.size)
    }

    @Test fun offlineVectorCanContain12IdsButNeverImpliesAnApprovedRequest() {
        val req=GroupedUds22Research.encodeOffline(s)
        assertEquals(25,req.size)
        assertEquals(0x22,req[0].toInt() and 255)
        assertEquals(0x48,req[1].toInt() and 255)
        assertEquals(0x07,req[2].toInt() and 255)
        val data=positive()
        assertEquals(s.expectedPositiveBytes,data.size)
        val parsed=GroupedUds22Research.decodeOffline(data,s)
        assertTrue(parsed is FastDidValidation.RawReply.Positive)
        val values=(parsed as FastDidValidation.RawReply.Positive).values
        assertEquals(12,values.size)
        assertArrayEquals(byteArrayOf(1,2),values.getValue(0x4807))
        assertArrayEquals(byteArrayOf(1),values.getValue(0x5813))
        assertNull(GroupedUds22Research.valueOfCandidate(0x4807,byteArrayOf(0,1)))
    }

    @Test fun sidNrcOrderDuplicationTruncationAndTrailingBytesAreRejected() {
        val good=positive()
        fun reason(b:ByteArray):String=
            (GroupedUds22Research.decodeOffline(b,s) as FastDidValidation.RawReply.Invalid).reason
        assertEquals("SID_OR_NRC",reason(good.clone().apply{this[0]=0x61}))
        assertEquals("DID_ORDER",reason(good.clone().apply{this[1]=0x58}))
        assertEquals("DID_ORDER",reason(good.clone().apply{
            this[1]=0x58;this[2]=0x13
        }))
        assertEquals("LENGTH",reason(good.copyOf(good.size-1)))
        assertEquals("EXTRA_OR_OVERSIZED",reason(good+byteArrayOf(0)))
        assertEquals("SID_OR_NRC",reason(byteArrayOf(0x7F,0x23,0x31)))
        val negative=GroupedUds22Research.decodeOffline(byteArrayOf(0x7F,0x22,0x31),s)
        assertEquals(0x31,(negative as FastDidValidation.RawReply.Negative).nrc)
        assertEquals("SID_OR_NRC",reason(byteArrayOf(0x7F,0x22,0x31,0)))
        try {
            GroupedUds22Research.Schema(listOf(0x4A36 to 1,0x4A36 to 1))
            fail("duplicate DID not allowed")
        }catch(_:IllegalArgumentException) {}
        try {
            GroupedUds22Research.Schema(listOf(0x9999 to 1))
            fail("out-of-catalog DID not allowed")
        }catch(_:IllegalArgumentException) {}
    }

    @Test fun parserNeverReusesAnOlderFastCycleForMissingValues() {
        val good=positive()
        val first=GroupedUds22Research.decodeOffline(good,s)
        assertTrue(first is FastDidValidation.RawReply.Positive)
        val next=GroupedUds22Research.decodeOffline(good.copyOf(good.size-3),s)
        assertTrue(next is FastDidValidation.RawReply.Invalid)
    }
}
