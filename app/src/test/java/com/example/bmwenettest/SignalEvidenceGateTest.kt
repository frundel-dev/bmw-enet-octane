package com.example.bmwenettest

import com.example.bmwenettest.SignalEvidenceGate as G
import org.junit.Assert.*
import org.junit.Test

class SignalEvidenceGateTest {
    private fun row(did:String="4A37",raw:String="00000100",
                    trip:String="101",fuel:String="1",epoch:String="0",
                    age:String="90",context:Boolean=true,
                    clock:String="1000"):String {
        val c=MutableList(25){""}
        c[0]="1792000000000";c[1]=trip;c[2]=fuel;c[3]=epoch
        c[4]="2.0.1-lab";c[5]="DME8FF_R";c[6]="USER_ACK_ONLY"
        c[7]="BASELINE";c[8]="B+";c[9]="DID_$did"
        c[10]=did;c[11]="NOT_VERIFIED";c[12]="SEMANTICS_UNVERIFIED"
        c[13]=raw;c[14]=if(raw.isEmpty())"" else (raw.length/2).toString()
        if(context) {c[17]="3000";c[18]="52";c[19]="115";c[20]=age}
        c[21]=clock;c[22]=(clock.toLong()+1).toString()
        c[23]="1"
        return c.joinToString(",")
    }

    @Test fun rawEvidenceNeverValidatesTargetSwflOrSignalMeaning() {
        val v=G.assess(sequenceOf(row()))
        assertEquals(1,v.validRows)
        val d=v.dids.single()
        assertEquals(G.Level.FAMILY_WIDTH_MATCH_ONLY,d.level)
        assertEquals("NOT_SWFL_VALIDATED",d.semantics)
        assertEquals(1,d.rawRows)
        assertEquals(1,d.contextualFreshRows)
        assertEquals(1,d.rpmBinCount)
        assertEquals(1,d.mapBinCount)
    }

    @Test fun wrongWidthIsRejectedAsPhysicalFormatEvidence() {
        val result=G.assess(sequenceOf(row(raw="ABCD")))
        assertEquals(G.Level.FAMILY_WIDTH_CONFLICT,result.dids.single().level)
        assertEquals(1,result.dids.single().widthConflicts)
        assertEquals(0,result.invalidRows) // CSV is syntactically well-formed
    }

    @Test fun emptyRawAndUnsupportedSignalsNeverBecomeZeros() {
        val d=G.assess(sequenceOf(row(raw="",context=false))).dids.single()
        assertEquals(G.Level.NO_RAW,d.level)
        assertEquals(1,d.noRawRows)
        assertEquals(0,d.rawRows)
        assertEquals(0,d.contextualFreshRows)
    }

    @Test fun duplicateSampleIsCountedOnceAcrossRows() {
        val a=row()
        val d=G.assess(sequenceOf(a,a)).dids.single()
        assertEquals(1,d.rawRows)
        assertEquals(1,d.duplicates)
        assertEquals(1,d.uniqueRaw)
    }

    @Test fun staleOrAbsentObdContextIsNotFreshEvidence() {
        val r=G.assess(sequenceOf(
            row(age="2200",clock="100"),
            row(age="100",context=false,clock="200")
        )).dids.single()
        assertEquals(2,r.rawRows)
        assertEquals(0,r.contextualFreshRows)
    }

    @Test fun repeatedTripIsNotIndependentFuelSession() {
        val d=G.assess(sequenceOf(
            row(trip="100",fuel="2",clock="100"),
            row(trip="101",fuel="2",clock="200",raw="00000200")
        )).dids.single()
        assertEquals(2,d.tripCount)
        assertEquals(1,d.fuelSessionCount)
        assertEquals(2,d.uniqueRaw)
        assertEquals("NOT_SWFL_VALIDATED",d.semantics)
    }

    @Test fun invalidRowsDoNotPromoteEvidence() {
        val report=G.assess(sequenceOf(
            "bad,row",row(raw="XYZ"),row()
        ))
        assertEquals(3,report.totalRows)
        assertEquals(2,report.invalidRows)
        assertEquals(1,report.validRows)
        assertEquals(1,report.dids.single().rawRows)
    }

    @Test fun offlineSummaryIsExplicitAboutLimits() {
        val txt=G.offlineSummary(G.assess(sequenceOf(row())))
        assertTrue(txt.contains("SWFL/meaning NOT validated"))
        assertTrue(txt.contains("0x4A37"))
        assertTrue(txt.contains("fresh_context=1"))
    }
}
