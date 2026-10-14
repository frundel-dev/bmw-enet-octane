package com.example.bmwenettest

import android.content.SharedPreferences
import java.io.File
import java.net.Socket

/**
 * Passive observer of ALREADY decoded OBD/UDS transactions.
 * It never reads from, writes to or opens a DME socket.
 * Research CSV uses nonblocking, bounded best-effort queue with explicit loss
 * reporting; the primary measurement CSV still uses backpressure.
 */
internal class FastDidPassiveRecorder(
    private val prefs:SharedPreferences,
    private val writer:AsyncCsvWriter,
    private val file:File,
    private val tripId:Long
) {
    private var socket:Socket?=null
    private var epoch=-1
    private val lastLogged=hashMapOf<Int,Long>()
    private var rpm:Double?=null
    private var load:Double?=null
    private var mapKpa:Double?=null
    private var rpmAtNs=0L
    private var loadAtNs=0L
    private var mapAtNs=0L
    private val knockDids=setOf(0x4A37,0x4A38,0x4A39,0x4A3A,0x4A36)

    init {
        file.writeText("# passive_only=true;new_did_requests=0;"+
            "knock_semantics_unverified=true;ecu_timestamp=unknown\n"+
            FastDidAuditCsv.HEADER+"\n")
        // Explicit absence of RAW for candidates; do not manufacture zeros.
        for(spec in FastDidValidation.candidates.filter { !it.existingReadOnly }) {
            send(FastDidAuditCsv.Row(System.currentTimeMillis(),tripId,
                fuelSession(),0,"2.0.1-lab/205","DME8FF_R",
                swflEvidence(),FastDidValidation.Profile.BASELINE,
                "NO_NEW_REQUESTS","NO_ADDITIONAL_QUERY",spec.did,
                FastDidValidation.status(spec.did),"NOT_VALIDATED",null,
                null,"",null,null,null,null,null,null,null,
                "Demo catalogue is not proof for target SWFL"))
        }
    }
    private fun fuelSession():Int=prefs.getInt("fuel_session_id",1)
    private fun swflEvidence():String=
        if(prefs.getBoolean("octane2_swfl_confirmed",false))
            "USER_ACK_ONLY" else "UNCONFIRMED"
    private fun send(row:FastDidAuditCsv.Row) {
        writer.offerAudit(file,FastDidAuditCsv.encode(row))
    }
    private fun readObd(channel:String,payload:ByteArray,atNs:Long) {
        when(channel) {
            "OBD_0C" -> DiagnosticResponse.obd(payload,0x0C)?.let {
                if(it.size==2) {
                    rpm=(((it[0].toInt() and 255)*256)+
                        (it[1].toInt() and 255))/4.0
                    rpmAtNs=atNs
                }
            }
            "OBD_04" -> DiagnosticResponse.obd(payload,0x04)?.let {
                if(it.size==1) {
                    load=(it[0].toInt() and 255)*100.0/255.0
                    loadAtNs=atNs
                }
            }
            "OBD_0B" -> DiagnosticResponse.obd(payload,0x0B)?.let {
                if(it.size==1) {
                    mapKpa=(it[0].toInt() and 255).toDouble()
                    mapAtNs=atNs
                }
            }
        }
    }
    private fun observeDid(did:Int,raw:ByteArray,startNs:Long,endNs:Long,
                           origin:String) {
        if(did !in knockDids)return
        val previous=lastLogged[did]
        if(previous!=null && endNs-previous in 0 until 5_000_000_000L)return
        lastLogged[did]=endNs
        val value=if(did in 0x4A37..0x4A3A && raw.size>=4) {
            val u=((raw[0].toLong() and 255L) shl 24) or
                ((raw[1].toLong() and 255L) shl 16) or
                ((raw[2].toLong() and 255L) shl 8) or
                (raw[3].toLong() and 255L)
            u*0.05/65536.0
        } else null
        val ages=listOf(rpmAtNs,loadAtNs,mapAtNs).filter{it>0L}
        val age=if(ages.size==3)
            ((endNs-ages.minOrNull()!!).coerceAtLeast(0L)/1_000_000L)
            else null
        send(FastDidAuditCsv.Row(
            System.currentTimeMillis(),tripId,fuelSession(),epoch,
            "2.0.1-lab/205","DME8FF_R",swflEvidence(),
            FastDidValidation.Profile.BASELINE,"UNKNOWN_PASSIVE",
            origin,did,FastDidValidation.status(did),
            if(did in 0x4A37..0x4A3A)
                "SEMANTICS_UNVERIFIED" else "NOT_VALIDATED",
            raw,value,if(value!=null)"legacy_knock_z_vms_UNVERIFIED" else "",
            rpm,load,mapKpa,age,startNs,endNs,
            ((endNs-startNs).coerceAtLeast(0L)/1_000_000L),
            "RAW_FIELD_OF_EXISTING_READ; Android timestamp only"
        ))
    }

    /**
     * Runs only on the one diagnostic socket owner's thread and observes a
     * result AFTER its normal strict response decoder has accepted it.
     */
    fun onDecoded(channel:String,result:Any,startNs:Long,endNs:Long,
                  owningSocket:Socket) {
        if(socket!==owningSocket) {
            socket=owningSocket
            epoch++
            lastLogged.clear()
            rpm=null;load=null;mapKpa=null
            rpmAtNs=0;loadAtNs=0;mapAtNs=0
        }
        when(result) {
            is Map<*,*> -> for((did,raw) in result) {
                if(did is Int && raw is ByteArray)
                    observeDid(did,raw,startNs,endNs,channel)
            }
            is ByteArray -> {
                if(channel.startsWith("OBD_"))readObd(channel,result,endNs)
                if(channel.startsWith("DID_")) {
                    val did=channel.removePrefix("DID_").toIntOrNull(16)
                    if(did!=null)observeDid(did,result,startNs,endNs,channel)
                }
            }
        }
    }
}
