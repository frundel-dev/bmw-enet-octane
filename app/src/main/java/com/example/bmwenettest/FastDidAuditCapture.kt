package com.example.bmwenettest

import java.io.File

/**
 * Suspended CSV producer separated from the large DME coroutine state
 * machine. Runs only at a bounded interval and adds NO diagnostic requests.
 */
internal object FastDidAuditCapture {
    data class Snapshot(
        val wallMs:Long, val tripId:Long,val fuelSession:Int,
        val epoch:Int,val pollMode:String,val userAcknowledgedSwfl:Boolean,
        val rpm:Double?,val load:Double?,val map:Double?,
        val knockDecoded:Array<Double?>,
        val knockRaw:Map<Int,ByteArray>,val knockStatusRaw:ByteArray?
    )

    suspend fun append(writer:AsyncCsvWriter,file:File,s:Snapshot) {
        // The first three are mock-catalog DID candidates, never ECU reads.
        // The remaining reads are existing, passive observations.
        val dids=listOf(0x5819,0x5804,0x4A2D,
            0x4A37,0x4A38,0x4A39,0x4A3A,0x4A36,
            0x5883,0x5885,0x5886,0x5888,0x554A)
        for(did in dids) {
            val existing=if(did==0x4A36)s.knockStatusRaw else s.knockRaw[did]
            val legacyIndex=did-0x4A37
            val referenceOnly=legacyIndex in 0..3
            val legacy=if(referenceOnly)s.knockDecoded.getOrNull(legacyIndex) else null
            writer.append(file,FastDidAuditCsv.encode(FastDidAuditCsv.Row(
                s.wallMs,s.tripId,s.fuelSession,s.epoch,
                "2.0.1-lab/205","DME8FF_R",
                if(s.userAcknowledgedSwfl)"USER_ACK_ONLY" else "UNCONFIRMED",
                FastDidValidation.Profile.BASELINE,s.pollMode,
                if(existing!=null)"EXISTING_READ" else "NO_ADDITIONAL_QUERY",
                did,FastDidValidation.status(did),
                if(referenceOnly)"SEMANTICS_UNVERIFIED" else "NOT_VALIDATED",
                existing,legacy,
                if(referenceOnly)"legacy_knock_z_vms_UNVERIFIED" else "",
                s.rpm,s.load,s.map,null,null,null,null,
                if(existing==null)
                    "No new UDS query; candidate not approved"
                else "PASSIVE_EXISTING_FAST; no ECU timestamp"
            )))
        }
    }
}
