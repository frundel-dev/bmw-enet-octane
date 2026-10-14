package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FastDidValidationTest {
    private fun payload(vararg v:Int)=v.map{it.toByte()}.toByteArray()

    @Test fun newMockDidsAreNotAuthorizedForRealEcu() {
        for(did in listOf(0x5819,0x5804,0x4A2D,0x5883,0x5885,
                        0x5886,0x5888,0x554A)) {
            assertEquals(FastDidValidation.Status.NOT_VERIFIED,
                FastDidValidation.status(did))
            assertFalse(FastDidValidation.canRequest(did,true,true))
            try {
                FastDidValidation.documentedReadRequest(did,true,true)
                fail("A demo DID must never reach the socket")
            }catch(_:IllegalArgumentException){}
        }
        assertTrue(FastDidValidation.canActivate(
            FastDidValidation.Profile.BASELINE,false,false))
        assertFalse(FastDidValidation.canActivate(
            FastDidValidation.Profile.FAST_DID_TEST,true,true))
        assertFalse(FastDidValidation.canActivate(
            FastDidValidation.Profile.FAST_BATCH_TEST,true,true))
    }

    @Test fun mockDecodeChecksExactWidthBigEndianAndMapUnit() {
        val rpm=FastDidValidation.mockPreview(0x5819,payload(0x13,0x88))!!
        assertEquals(2500.0,rpm.value,0.001)
        assertTrue(rpm.warning.contains("MOCK"))
        assertEquals("%",FastDidValidation.mockPreview(0x5804,payload(128))!!.unit)
        assertEquals(50.0,FastDidValidation.mockPreview(0x5804,payload(128))!!.value,0.001)
        val map=FastDidValidation.mockPreview(0x4A2D,payload(0x32,0))!!
        assertEquals(100.0,map.value,0.001) // 12800 * 0.078125 hPa / 10
        assertNull(FastDidValidation.mockPreview(0x5819,payload(0x12)))
        assertNull(FastDidValidation.mockPreview(0x4A37,payload(0,0,0,1)))
        assertEquals("00FF",FastDidValidation.hex(payload(0,255)))
    }

    @Test fun mixedWidthPayloadRejectsMissingReorderedExtraAndNrc() {
        val widths=listOf(0x5819 to 2,0x5804 to 1,0x4A2D to 2)
        val correct=payload(0x62,0x58,0x19,0x13,0x88,
            0x58,0x04,0x80,
            0x4A,0x2D,0x32,0x00)
        val decoded=FastDidValidation.parseExact(correct,widths)
        assertTrue(decoded is FastDidValidation.RawReply.Positive)
        assertArrayEquals(payload(0x13,0x88),
            (decoded as FastDidValidation.RawReply.Positive).values[0x5819])
        assertTrue(FastDidValidation.parseExact(correct.copyOf(correct.size-1),widths)
            is FastDidValidation.RawReply.Invalid)
        assertTrue(FastDidValidation.parseExact(correct+byteArrayOf(0),widths)
            is FastDidValidation.RawReply.Invalid)
        val swapped=correct.clone().apply { this[1]=0x4A;this[2]=0x2D }
        assertEquals("DID_ORDER", (FastDidValidation.parseExact(swapped,widths)
            as FastDidValidation.RawReply.Invalid).reason)
        val nrc=FastDidValidation.parseExact(payload(0x7F,0x22,0x31),widths)
        assertEquals(0x31,(nrc as FastDidValidation.RawReply.Negative).nrc)
        assertTrue(FastDidValidation.parseExact(payload(0x7F,0x2E,0x31),widths)
            is FastDidValidation.RawReply.Invalid)
    }

    @Test fun candidatesNotConfusedWithExistingActiveKnockWidth() {
        for(did in 0x4A37..0x4A3A) {
            val c=FastDidValidation.candidate(did)!!
            assertTrue(c.existingReadOnly)
            assertEquals(4,c.mockWidth)
            assertNull(FastDidValidation.mockPreview(did,payload(0,1)))
            assertEquals(FastDidValidation.Status.NOT_VERIFIED,
                FastDidValidation.status(did))
        }
        assertEquals(listOf(0x4A37,0x4A38,0x4A39,0x4A3A),
            DmeBatchParser.knockDids)
        assertEquals(8,MixedDidBatch.dids.size)
    }
}
