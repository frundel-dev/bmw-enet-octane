package com.example.bmwenettest

/**
 * LAB research witness for manually supplied, already-recorded UDS payloads.
 * No socket, Service, HSFZ stream, ECU address or active diagnostic request.
 * A positive response is RAW evidence only, not physical signal validation.
 */
object LabGroupedUds22Evidence {
    enum class Verdict { POSITIVE_RAW, NEGATIVE_NRC, INVALID_REPLY, NO_RESPONSE, AMBIGUOUS }
    data class Record(
        val tripId:Long, val fuelSessionId:Int, val connectionEpoch:Int,
        val cycleId:Long, val requestStartNs:Long, val responseEndNs:Long?,
        val requestedDids:List<Int>, val rawHex:String,
        val verdict:Verdict, val reason:String
    )
    const val CSV_HEADER="trip_id,fuel_session_id,connection_epoch,cycle_id,request_start_ns,response_end_ns,requested_dids,raw_uds_hex,verdict,reason,offline_only"
    private fun hex(b:ByteArray)=b.joinToString(""){"%02X".format(it.toInt() and 255)}
    fun fromCapturedPayloads(
        tripId:Long,fuelSessionId:Int,connectionEpoch:Int,cycleId:Long,
        startNs:Long,endNs:Long?,
        schema:GroupedUds22Research.Schema,
        payloads:List<ByteArray>
    ):Record {
        require(startNs>0L && (endNs==null || endNs>=startNs))
        // Reject ambiguous or excessive evidence even when replies look valid.
        val raw=if(payloads.size==1 && payloads[0].size<=4096)
            hex(payloads[0]) else ""
        val (verdict,reason)=when {
            payloads.size>1 -> Verdict.AMBIGUOUS to "MULTIPLE_RESPONSES"
            payloads.isEmpty() -> Verdict.NO_RESPONSE to "NO_DATA"
            payloads[0].size>4096 -> Verdict.INVALID_REPLY to "PAYLOAD_TOO_LONG"
            else -> when(val p=GroupedUds22Research.decodeOffline(payloads[0],schema)) {
                is FastDidValidation.RawReply.Positive ->
                    Verdict.POSITIVE_RAW to "OFFLINE_UNVALIDATED_SEMANTICS"
                is FastDidValidation.RawReply.Negative ->
                    Verdict.NEGATIVE_NRC to "NRC_%02X".format(p.nrc)
                is FastDidValidation.RawReply.Invalid ->
                    Verdict.INVALID_REPLY to p.reason
            }
        }
        return Record(tripId,fuelSessionId,connectionEpoch,cycleId,startNs,endNs,
            schema.widths.map{it.first},raw,verdict,reason)
    }
    fun csv(record:Record):String=listOf(
        record.tripId,record.fuelSessionId,record.connectionEpoch,record.cycleId,
        record.requestStartNs,record.responseEndNs?:"",
        record.requestedDids.joinToString("|"){"%04X".format(it)},
        record.rawHex,record.verdict.name,record.reason,true
    ).joinToString(",")

    /**
     * NO CANDIDATE HAS BEEN APPROVED FOR THE TARGET SWFL.
     * Not even explicit UI confirmation, parked=true or known B+ permits
     * one-shot new DID polling or a new grouped read in this iteration.
     */
    fun allowNewVehicleProbe(did:Int,parked:Boolean,swflConfirmed:Boolean,
                             explicitConsent:Boolean):Boolean=false
    fun allowGroupedFast():Boolean=false
}
