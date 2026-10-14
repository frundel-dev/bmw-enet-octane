package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FastDidAuditCsvTest {
    private fun row(fuel:Int,raw:ByteArray?)=FastDidAuditCsv.Row(
        wallMs=1712345678000L,tripId=1712345000000L,
        fuelSession=fuel,epoch=2,
        version="2.0.1-lab/205",ecuProfile="DME8FF_R",
        swflEvidence="USER_ACK_ONLY",
        profile=FastDidValidation.Profile.BASELINE,pollMode="B+",
        source=if(raw==null)"NO_ADDITIONAL_QUERY" else "EXISTING_READ",
        did=0x4A37,status=FastDidValidation.Status.NOT_VERIFIED,
        semantics="SEMANTICS_UNVERIFIED",raw=raw,
        legacyValue=if(raw==null)null else 0.075,
        unverifiedUnit="legacy_knock_z_vms_UNVERIFIED",
        obdRpm=2500.0,obdLoad=32.0,obdMap=105.0,
        ageMs=null,requestStartNs=null,responseEndNs=null,
        durationMs=null,reason="PASSIVE_EXISTING_FAST"
    )

    @Test fun passiveRawMustHaveExactlyMatchingDeclaredWidth() {
        val encoded=FastDidAuditCsv.encode(row(1,byteArrayOf(0,0,1,2)))
        assertEquals(FastDidAuditCsv.HEADER.split(",").size,encoded.split(",").size)
        assertTrue(FastDidAuditCsv.validateLine(encoded))
        val noRaw=FastDidAuditCsv.encode(row(2,null))
        assertTrue(FastDidAuditCsv.validateLine(noRaw))
        assertTrue(noRaw.contains("NOT_VERIFIED"))
        assertTrue(encoded.contains("SEMANTICS_UNVERIFIED"))
        assertFalse(encoded.contains("RON"))
        val broken=encoded.split(",").toMutableList()
        broken[14]="2"
        assertFalse(FastDidAuditCsv.validateLine(broken.joinToString(",")))
        broken[14]="4"
        broken[13]="ZX000102"
        assertFalse(FastDidAuditCsv.validateLine(broken.joinToString(",")))
    }

    @Test fun auditPreservesFuelSessionsAndDoesNotAssumeNoResponseIsZero() {
        val a=FastDidAuditCsv.encode(row(1,byteArrayOf(0,0,0,1)))
        val b=FastDidAuditCsv.encode(row(2,null))
        val audit=FastDidAuditCsv.analyze(sequenceOf(
            "# audit only",FastDidAuditCsv.HEADER,a,b,"incomplete,row"))
        assertEquals(3,audit.total)
        assertEquals(2,audit.valid)
        assertEquals(1,audit.invalid)
        assertEquals(setOf(1,2),audit.fuelSessions)
        assertEquals(setOf("4A37"),audit.dids)
        assertEquals(setOf("NOT_VERIFIED"),audit.statuses)
    }
    @Test fun latestRawViewerShowsOnlyValidatedCsvRowsAndLastAcceptedSnapshot() {
        val raw1=FastDidAuditCsv.encode(row(2,byteArrayOf(0,0,1,1)))
        val raw2=FastDidAuditCsv.encode(row(2,byteArrayOf(0,0,1,2)))
        val result=FastDidAuditCsv.latestSamples(sequenceOf(
            FastDidAuditCsv.HEADER,raw1,"broken,row",raw2))
        assertEquals(1,result.size)
        assertEquals("00000102",result["4A37"]?.rawHex)
        assertEquals("NOT_VERIFIED",result["4A37"]?.status)
        assertEquals("SEMANTICS_UNVERIFIED",result["4A37"]?.semanticStatus)
        assertEquals("EXISTING_READ",result["4A37"]?.source)
        assertEquals("",result["4A37"]?.durationMs)
    }

}
