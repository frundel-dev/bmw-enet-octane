package com.example.bmwenettest

import java.util.Locale

/**
 * Passive snapshot of measurements already requested by BASELINE/B+.
 * No new ECU requests, no ECU timestamp, no claims about knock physics.
 * Null raw data is rendered EMPTY (never as a numeric zero).
 */
internal object FastDidAuditCsv {
    const val VERSION=1
    const val HEADER="wall_time_ms,trip_id,fuel_session_id,connection_epoch,"+
        "app_version,ecu_profile,swfl_evidence,profile,poll_mode,source,did,"+
        "validation_status,semantics_status,raw_hex,raw_width_bytes,"+
        "legacy_decoded_value,legacy_unit_unverified,obd_rpm,obd_load_pct,"+
        "obd_map_kpa,rpm_map_age_ms,request_start_ns,response_end_ns,"+
        "duration_ms,reason"
    data class Row(
        val wallMs:Long,val tripId:Long,val fuelSession:Int,val epoch:Int,
        val version:String,val ecuProfile:String,val swflEvidence:String,
        val profile:FastDidValidation.Profile,val pollMode:String,
        val source:String,val did:Int?,val status:FastDidValidation.Status,
        val semantics:String,val raw:ByteArray?,
        val legacyValue:Double?,val unverifiedUnit:String,
        val obdRpm:Double?,val obdLoad:Double?,val obdMap:Double?,
        val ageMs:Long?,val requestStartNs:Long?,val responseEndNs:Long?,
        val durationMs:Long?,val reason:String
    )
    private fun number(v:Double?):String=v?.takeIf{it.isFinite()}?.let{
        "%.6f".format(Locale.US,it)
    } ?: ""
    private fun safe(v:String):String=v.replace(",",";")
        .replace("\n"," ").replace("\r"," ").take(180)
    fun encode(row:Row):String=listOf(
        row.wallMs,row.tripId,row.fuelSession,row.epoch,row.version,
        safe(row.ecuProfile),safe(row.swflEvidence),row.profile.name,
        safe(row.pollMode),safe(row.source),
        row.did?.let{"%04X".format(Locale.US,it)}?:"",
        row.status.name,row.semantics,
        row.raw?.let{FastDidValidation.hex(it)}?:"",
        row.raw?.size?:"",number(row.legacyValue),safe(row.unverifiedUnit),
        number(row.obdRpm),number(row.obdLoad),number(row.obdMap),
        row.ageMs?:"",row.requestStartNs?:"",row.responseEndNs?:"",
        row.durationMs?:"",safe(row.reason)
    ).joinToString(",")

    fun validateLine(line:String):Boolean {
        val cells=line.split(",")
        if(cells.size!=HEADER.split(",").size)return false
        if(cells[0].toLongOrNull()==null ||
            cells[1].toLongOrNull()==null || cells[2].toIntOrNull()==null ||
            cells[3].toIntOrNull()==null ||
            cells[7] !in FastDidValidation.Profile.entries.map{it.name} ||
            cells[11] !in FastDidValidation.Status.entries.map{it.name})
            return false
        val raw=cells[13]
        val width=cells[14].toIntOrNull()
        if(raw.isNotEmpty() && (raw.length%2!=0 ||
                !raw.all{it in "0123456789ABCDEF"} ||
                width!=raw.length/2))return false
        if(raw.isEmpty() && width!=null)return false
        return true
    }

    data class Report(val total:Int,val valid:Int,val invalid:Int,
                      val fuelSessions:Set<Int>,val dids:Set<String>,
                      val statuses:Set<String>)
    fun analyze(rows:Sequence<String>):Report {
        var count=0;var good=0
        val sessions=linkedSetOf<Int>();val dids=linkedSetOf<String>()
        val statuses=linkedSetOf<String>()
        for(line in rows) {
            if(line.isBlank() || line.startsWith("#") || line==HEADER)continue
            count++
            if(!validateLine(line))continue
            good++
            val c=line.split(",")
            sessions.add(c[2].toInt())
            if(c[10].isNotBlank())dids.add(c[10])
            statuses.add(c[11])
        }
        return Report(count,good,count-good,sessions,dids,statuses)
    }
}
