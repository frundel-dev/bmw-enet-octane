package com.example.bmwenettest

import java.util.Locale

/**
 * Candidate catalog, not a map of confirmed MG1CS201/DME8FF_R DIDs.
 * The bootmod3 mock_channels.json values are DEMONSTRATION data.
 *
 * No DID here may be queried on a vehicle before independent ECU/SWFL-specific
 * evidence has been reviewed and an explicit immutable allowlist is updated.
 */
object FastDidValidation {
    enum class Status {
        NOT_VERIFIED, DOCUMENTED, RAW_CONFIRMED, SEMANTICS_VALIDATED, REJECTED
    }
    enum class Profile { BASELINE, FAST_DID_TEST, FAST_BATCH_TEST }
    enum class Meaning { MOCK_ENGINE_RPM, MOCK_RELATIVE_LOAD, MOCK_MAP_HPA,
        KNOCK_REFERENCE_UNKNOWN, KNOCK_STATUS_UNKNOWN,
        KNOCK_CONTROL_REFERENCE_UNKNOWN, KNOCK_ADAPTATION_UNKNOWN }
    data class Candidate(
        val did:Int, val label:String, val meaning:Meaning,
        val mockWidth:Int?=null, val existingReadOnly:Boolean=false
    ) {
        val name:String get()="0x%04X".format(Locale.US,did)
    }
    val candidates=listOf(
        Candidate(0x5819,"Engine Speed (bootmod3 mock)",Meaning.MOCK_ENGINE_RPM,2),
        Candidate(0x5804,"Load Actual Relative (bootmod3 mock)",Meaning.MOCK_RELATIVE_LOAD,1),
        Candidate(0x4A2D,"Manifold Absolute Pressure (bootmod3 mock)",Meaning.MOCK_MAP_HPA,2),
        Candidate(0x4A37,"Knock channel cylinder 1 (meaning unverified)",Meaning.KNOCK_REFERENCE_UNKNOWN,4,true),
        Candidate(0x4A38,"Knock channel cylinder 2 (meaning unverified)",Meaning.KNOCK_REFERENCE_UNKNOWN,4,true),
        Candidate(0x4A39,"Knock channel cylinder 3 (meaning unverified)",Meaning.KNOCK_REFERENCE_UNKNOWN,4,true),
        Candidate(0x4A3A,"Knock channel cylinder 4 (meaning unverified)",Meaning.KNOCK_REFERENCE_UNKNOWN,4,true),
        Candidate(0x4A36,"Knock detected/status (existing source)",Meaning.KNOCK_STATUS_UNKNOWN,1,true),
        Candidate(0x5883,"Knock control reference candidate",Meaning.KNOCK_CONTROL_REFERENCE_UNKNOWN),
        Candidate(0x5885,"Knock control reference candidate",Meaning.KNOCK_CONTROL_REFERENCE_UNKNOWN),
        Candidate(0x5886,"Knock control reference candidate",Meaning.KNOCK_CONTROL_REFERENCE_UNKNOWN),
        Candidate(0x5888,"Knock control reference candidate",Meaning.KNOCK_CONTROL_REFERENCE_UNKNOWN),
        Candidate(0x554A,"Knock adaptation candidate (existing probe)",Meaning.KNOCK_ADAPTATION_UNKNOWN,2,true)
    )
    private val byId=candidates.associateBy{it.did}

    // Intentionally EMPTY. An APK's demo channel list or a working different
    // DME is not documentary confirmation for our DME8FF_R + exact SWFL.
    // A future review must provide proof, test fixtures and a separate PR.
    private val documentedForTargetSwfl=emptySet<Int>()

    fun candidate(did:Int):Candidate?=byId[did]
    fun status(did:Int):Status=if(did in documentedForTargetSwfl)
        Status.DOCUMENTED else Status.NOT_VERIFIED
    fun canRequest(did:Int,parked:Boolean,swflConfirmed:Boolean):Boolean =
        parked && swflConfirmed && did in documentedForTargetSwfl

    fun canActivate(profile:Profile,parked:Boolean,swflConfirmed:Boolean):Boolean =
        when(profile) {
            Profile.BASELINE -> true
            Profile.FAST_DID_TEST ->
                listOf(0x5819,0x5804,0x4A2D).all { canRequest(it,parked,swflConfirmed) }
            Profile.FAST_BATCH_TEST -> false // requires independent group proof
        }

    /** Protects even callers bypassing the UI. No unverified wire requests. */
    fun documentedReadRequest(did:Int,parked:Boolean,swflConfirmed:Boolean):ByteArray {
        require(canRequest(did,parked,swflConfirmed)) {
            "DID 0x%04X is not documented/allowlisted for this ECU and SWFL".format(Locale.US,did)
        }
        return byteArrayOf(0x22,(did ushr 8).toByte(),did.toByte())
    }

    sealed class RawReply {
        data class Positive(val values:Map<Int,ByteArray>):RawReply()
        data class Negative(val nrc:Int):RawReply()
        data class Invalid(val reason:String):RawReply()
    }

    /**
     * Offline strict UDS 0x62 decoder; fixed widths supplied by validated
     * external fixture. No data from failed/mismatched responses is used.
     */
    fun parseExact(payload:ByteArray,widths:List<Pair<Int,Int>>):RawReply {
        if(widths.isEmpty() || widths.size>16 || widths.any{
            it.first !in 0..65535 || it.second !in 1..32
        } || widths.map{it.first}.distinct().size!=widths.size)
            return RawReply.Invalid("INVALID_SPEC")
        if(payload.size==3 &&
            (payload[0].toInt() and 255)==0x7F &&
            (payload[1].toInt() and 255)==0x22)
            return RawReply.Negative(payload[2].toInt() and 255)
        if(payload.firstOrNull()!=0x62.toByte())
            return RawReply.Invalid("SID_OR_NRC")
        if(payload.size!=1+widths.sumOf{2+it.second})
            return RawReply.Invalid("LENGTH")
        var offset=1
        val out=linkedMapOf<Int,ByteArray>()
        for((did,width) in widths) {
            val id=((payload[offset].toInt() and 255) shl 8) or
                (payload[offset+1].toInt() and 255)
            if(id!=did)return RawReply.Invalid("DID_ORDER")
            offset+=2
            out[did]=payload.copyOfRange(offset,offset+width)
            offset+=width
        }
        return RawReply.Positive(out)
    }

    data class DemoPreview(val value:Double,val unit:String,val warning:String)
    /**
     * Explicitly MOCK-ONLY decoder. Never a DME reading or a validated
     * physical conversion; callers must label output as unverified.
     */
    fun mockPreview(did:Int,raw:ByteArray):DemoPreview? {
        val c=byId[did]?:return null
        if(raw.size!=c.mockWidth)return null
        val u=raw.fold(0){acc,b->(acc shl 8) or (b.toInt() and 255)}
        val mock="BOOTMOD3_MOCK_NOT_VALIDATED_FOR_DME8FF_R"
        return when(c.meaning) {
            Meaning.MOCK_ENGINE_RPM -> DemoPreview(u*0.5,"rpm",mock)
            Meaning.MOCK_RELATIVE_LOAD -> DemoPreview(u*0.390625,"%",mock)
            Meaning.MOCK_MAP_HPA -> DemoPreview(u*0.078125/10.0,"kPa (mock hPa / 10)",mock)
            else -> null
        }
    }
    fun hex(data:ByteArray):String=data.joinToString("") {
        "%02X".format(Locale.US,it.toInt() and 255)
    }
}
