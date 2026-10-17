package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class LabGroupedUds22EvidenceTest {
    private val schema=GroupedUds22Research.Schema(listOf(0x4A36 to 1,0x4A49 to 2))
    private fun observe(vararg p:ByteArray)=LabGroupedUds22Evidence.fromCapturedPayloads(
        tripId=7L,fuelSessionId=4,connectionEpoch=3,cycleId=5,
        startNs=1_000L,endNs=2_000L,schema=schema,payloads=p.toList()
    )
    @Test fun exactRecordedPositiveIsRawOnlyAndNeverPromotedToFuelMeasurement() {
        val r=observe(byteArrayOf(0x62,0x4A,0x36,0x01,0x4A,0x49,0,10))
        assertEquals(LabGroupedUds22Evidence.Verdict.POSITIVE_RAW,r.verdict)
        assertEquals("624A36014A49000A",r.rawHex)
        assertEquals(3,r.connectionEpoch)
        assertEquals(5L,r.cycleId)
        val headers=LabGroupedUds22Evidence.CSV_HEADER.split(",")
        val values=LabGroupedUds22Evidence.csv(r).split(",")
        assertEquals(headers.size,values.size)
        assertEquals("true",headers.zip(values).toMap()["offline_only"])
        assertTrue(r.reason.contains("UNVALIDATED"))
    }
    @Test fun ambiguousNegativeTimeoutAndCorruptedRepliesRemainObservablyInvalid() {
        val expected=byteArrayOf(0x62,0x4A,0x36,0x01,0x4A,0x49,0,10)
        assertEquals(LabGroupedUds22Evidence.Verdict.AMBIGUOUS,
            observe(expected,expected).verdict)
        assertEquals("",observe(expected,expected).rawHex)
        assertEquals(LabGroupedUds22Evidence.Verdict.NO_RESPONSE,observe().verdict)
        assertEquals(LabGroupedUds22Evidence.Verdict.NEGATIVE_NRC,
            observe(byteArrayOf(0x7F,0x22,0x31)).verdict)
        assertEquals("NRC_31",
            observe(byteArrayOf(0x7F,0x22,0x31)).reason)
        assertEquals(LabGroupedUds22Evidence.Verdict.INVALID_REPLY,
            observe(expected.copyOf(expected.size-1)).verdict)
        assertEquals(LabGroupedUds22Evidence.Verdict.INVALID_REPLY,
            observe(byteArrayOf(0x62,0x4A,0x37,0x01,0x4A,0x49,0,10)).verdict)
    }
    @Test fun physicalPrerequisitesAndUserConsentCannotOverrideUnverifiedDids() {
        assertFalse(LabGroupedUds22Evidence.allowGroupedFast())
        for(did in GroupedUds22Research.catalog.map{it.did}) {
            assertFalse(LabGroupedUds22Evidence.allowNewVehicleProbe(
                did,true,true,true))
        }
        assertFalse(LabGroupedUds22Evidence.allowNewVehicleProbe(
            0x4807,true,true,true))
        assertFalse(LabGroupedUds22Evidence.allowNewVehicleProbe(
            0x4807,false,true,true))
    }
    @Test fun impossibleTimeOrderIsRejectedRatherThanPresentedAsAnECUReading() {
        try {
            LabGroupedUds22Evidence.fromCapturedPayloads(1,1,1,1,1000,999,schema,emptyList())
            fail("backwards host time")
        }catch(_:IllegalArgumentException){}
    }
}
