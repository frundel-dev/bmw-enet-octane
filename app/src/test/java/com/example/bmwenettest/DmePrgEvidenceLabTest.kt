package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DmePrgEvidenceLabTest {
    private fun bytes(vararg v:Int)=v.map{it.toByte()}.toByteArray()
    private fun hex(b:ByteArray)=b.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun positive(did:Int,vararg data:Int):String=hex(bytes(
        0x62,did ushr 8,did and 0xFF,*data
    ))

    @Test fun all22PrimaryPrgEntriesArePresentAndUnique() {
        assertEquals(22,DmePrgEvidenceLab.specs.size)
        assertEquals(22,DmePrgEvidenceLab.specs.map{it.did}.distinct().size)
        assertEquals(1,DmePrgEvidenceLab.spec(0x554C)?.width)
        assertEquals(1,DmePrgEvidenceLab.spec(0x5776)?.width)
        assertNull(DmePrgEvidenceLab.spec(0x4035)?.width)
    }

    @Test fun noNewDmeDidCanReachVehicleEvenWithAllUserAcknowledgements() {
        for(spec in DmePrgEvidenceLab.specs) {
            assertFalse(DmePrgEvidenceLab.canIssueNewDidRequest(spec.did,true,true))
            assertFalse(DmePrgEvidenceLab.canIssueNewDidRequest(spec.did,true,false))
            assertFalse(DmePrgEvidenceLab.canIssueNewDidRequest(spec.did,false,true))
        }
        assertFalse(FastDidValidation.canRequest(0x554C,true,true))
        assertFalse(FastDidValidation.canRequest(0x5776,true,true))
        assertFalse(FastDidValidation.canRequest(0x4035,true,true))
    }

    @Test fun signedMeanKnockRetardUsesPrgScaleButRemainsOfflinePreview() {
        val result=DmePrgEvidenceLab.interpretHex(0x554C,positive(0x554C,0xFE))
        assertEquals(DmePrgEvidenceLab.Status.PREVIEW_ONLY,result.status)
        assertTrue(result.value!!.contains("-0.750"))
        assertTrue(result.detail.contains("SWFL"))
        assertTrue(DmePrgEvidenceLab.interpretHex(0x554C,positive(0x554C,0x02))
            .value!!.contains("0.750"))
    }

    @Test fun dmeFuelFactorCannotBeConvertedToRonOrClaimVehicleSupport() {
        val result=DmePrgEvidenceLab.interpretHex(0x5776,positive(0x5776,0x80))
        assertEquals(DmePrgEvidenceLab.Status.PREVIEW_ONLY,result.status)
        assertTrue(result.value!!.contains("0.500000"))
        assertTrue(result.value!!.contains("не лабораторный RON"))
    }

    @Test fun unsignedKnockValueAndReferenceUseSamePrgScaleButDifferentMeaning() {
        val value=DmePrgEvidenceLab.interpretHex(0x5883,positive(0x5883,0,1,0,0))
        assertEquals(DmePrgEvidenceLab.Status.PREVIEW_ONLY,value.status)
        assertTrue(value.value!!.contains("0.0500000"))
        assertTrue(value.value!!.contains("knock-value"))
        val reference=DmePrgEvidenceLab.interpretHex(0x4A37,positive(0x4A37,0,1,0,0))
        assertTrue(reference.value!!.contains("reference, не knock event"))
        assertEquals(DmePrgEvidenceLab.VehicleEvidence.DERIVED_CSV_ONLY,
            DmePrgEvidenceLab.spec(0x4A37)?.evidence)
    }

    @Test fun signedKnockAdaptationDoesNotInventAngleUnits() {
        val result=DmePrgEvidenceLab.interpretHex(0x554A,positive(0x554A,0xFF,0xF6))
        assertTrue(result.value!!.contains("-1.00"))
        assertTrue(result.value!!.contains("единица спорна"))
        assertEquals(DmePrgEvidenceLab.VehicleEvidence.APP_RAW_ONLY,
            DmePrgEvidenceLab.spec(0x554A)?.evidence)
    }

    @Test fun offlineOnlyStrictlyRejectsBadSidDidLengthAndHex() {
        val valid=positive(0x554C,0x01)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x554C,"62 55 4A 01").status)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x554C,valid+"00").status)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x554C,"62554C").status)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x554C,"62554CZ1").status)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x554C,"").status)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x9999,valid).status)
        assertEquals(DmePrgEvidenceLab.Status.INVALID,
            DmePrgEvidenceLab.interpretHex(0x554C,"7F2E31").status)
    }

    @Test fun negativeResponseNeverBecomesMeasurement() {
        val result=DmePrgEvidenceLab.interpretHex(0x554C,"7F 22 31")
        assertEquals(DmePrgEvidenceLab.Status.NEGATIVE_NRC,result.status)
        assertNull(result.value)
        assertTrue(result.detail.contains("NRC=0x31"))
    }

    @Test fun structuredHistoryCannotBeBlindlyParsedAsOneByteOrQueried() {
        for(id in listOf(0x4035,0x407F)) {
            val result=DmePrgEvidenceLab.interpretHex(id,"62 40 35 00")
            assertEquals(DmePrgEvidenceLab.Status.STRUCTURE_LOCKED,result.status)
            assertNull(result.value)
        }
    }

    @Test fun mapAndIgnitionPreservePrgUnitsAndSigns() {
        val map=DmePrgEvidenceLab.interpretHex(0x4A2D,positive(0x4A2D,0x32,0))
        assertTrue(map.value!!.contains("100.00000 кПа"))
        val ign=DmePrgEvidenceLab.interpretHex(0x4A49,positive(0x4A49,0xFF,0xF6))
        assertTrue(ign.value!!.contains("-1.00 °KW"))
    }
}
