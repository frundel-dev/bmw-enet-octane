package com.example.bmwenettest

/**
 * Host-only HSFZ observability, scoped to the primary single-owner FAST cycle.
 * An ACK is a gateway control frame, not the first diagnostic DATA byte.
 * DATA header timestamp is when the first Android read returned bytes.
 * Neither that time nor cycle-publish time is an ECU sampling timestamp.
 */
class DiagnosticWireTrace {
    companion object {
        const val HEADER="wall_time_ms,stage_id,epoch,cycle,channel,request_start_ns,"+
            "send_start_ns,send_end_ns,ack_verified_ns,data_header_first_read_ns,"+
            "data_complete_ns,decode_end_ns,cycle_publish_ns,valid,error,"+
            "ack_present,host_send_to_ack_ms,host_send_to_data_ms,host_data_receive_ms,"+
            "identifier_kind,request_sid,request_ids,recv_phase,phase_at_ns,"+
            "failed_at_ns,exception_class,exception_message,unfinished"
        private fun number(v:Long?):String=v?.toString()?:""
        private fun diff(a:Long?,b:Long?):String=if(a!=null && b!=null && b>=a)
            String.format(java.util.Locale.US,"%.3f",(b-a)/1_000_000.0) else ""
        private fun clean(s:String):String=s.replace(",",";")
            .replace("\n"," ").replace("\r"," ").take(100)
        // Preserve the exact IOException message (including commas/quotes).
        // Line breaks are reversibly encoded so each transaction is one row.
        private fun exceptionText(s:String):String {
            val escaped=s.replace("\\","\\\\").replace("\n","\\n").replace("\r","\\r")
            return if(escaped.any{it==',' || it=='"'})
                "\""+escaped.replace("\"","\"\"")+"\"" else escaped
        }
        private fun identifiers(ids:List<Int>,kind:String):String =
            ids.joinToString("|") { id ->
                if(kind=="PID") "0x%02X".format(java.util.Locale.US,id)
                else "0x%04X".format(java.util.Locale.US,id)
            }
    }
    data class Tx(
        val wallMs:Long,val channel:String,
        val requestStartNs:Long,val sendStartNs:Long?,val sendEndNs:Long?,
        val ackVerifiedNs:Long?,val dataHeaderFirstReadNs:Long?,
        val dataCompleteNs:Long?,val decodeEndNs:Long?,val valid:Boolean,
        val error:String="",
        val requestedIds:List<Int> = emptyList(),val requestSid:Int?=null,
        val recvPhase:String="",val phaseAtNs:Long?=null,
        val failedAtNs:Long?=null,val exceptionClass:String="",
        val exceptionMessage:String="",val unfinished:Boolean=false
    )
    private val txs=ArrayList<Tx>(32)
    var dropped:Int=0
        private set
    fun record(v:Tx) {
        if(txs.size>=128) {
            dropped++
            // Never discard the sole evidence of a failed transaction.
            if(v.unfinished) {
                txs.removeAt(0)
                txs.add(v)
            }
            return
        }
        txs.add(v)
    }
    fun flush(stage:Int,epoch:Int,cycle:Long,publishNs:Long):String? {
        if(txs.isEmpty())return null
        val result=txs.joinToString("\n"){t->
            val kind=when {
                t.channel.startsWith("OBD_") -> "PID"
                t.channel.startsWith("DID_") -> "DID"
                else -> "UNKNOWN"
            }
            listOf(t.wallMs,stage,epoch,cycle,clean(t.channel),
                t.requestStartNs,number(t.sendStartNs),number(t.sendEndNs),
                number(t.ackVerifiedNs),number(t.dataHeaderFirstReadNs),
                number(t.dataCompleteNs),number(t.decodeEndNs),publishNs,
                t.valid,clean(t.error),t.ackVerifiedNs!=null,
                diff(t.sendEndNs,t.ackVerifiedNs),
                diff(t.sendEndNs,t.dataHeaderFirstReadNs),
                diff(t.dataHeaderFirstReadNs,t.dataCompleteNs),
                kind,t.requestSid?.let{"0x%02X".format(java.util.Locale.US,it)}?:"",
                identifiers(t.requestedIds,kind),t.recvPhase,
                number(t.phaseAtNs),number(t.failedAtNs),
                t.exceptionClass,exceptionText(t.exceptionMessage),
                t.unfinished).joinToString(",")
        }
        txs.clear()
        return result
    }
    fun discard() { txs.clear() }
}
