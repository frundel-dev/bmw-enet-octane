package com.example.bmwenettest

import java.util.Locale

/**
 * Passive timestamps of host-side diagnostic transactions. ONE polling owner.
 * No ECU acquisition timestamp can be inferred from these host boundaries.
 * Existing FAST/fuel computation remains unchanged.
 */
internal class FastTimingTrace(
    private val onTransactions:(String)->Unit,
    private val onSignals:(String)->Unit,
    private val onCycle:(String)->Unit
) {
    companion object {
        const val TX_HEADER="wall_time_ms,trip_id,fuel_session_id,epoch,cycle_id,profile,poll_mode,tx_id,channel,ids,request_start_ns,send_start_ns,send_end_ns,first_byte_ns,response_complete_ns,decode_end_ns,include_ns,valid,error,transport"
        const val SIGNAL_HEADER="wall_time_ms,trip_id,fuel_session_id,epoch,cycle_id,profile,poll_mode,signal,id,status,source_tx_id,request_start_ns,response_complete_ns,decode_end_ns,include_ns,age_ms,reused_previous_cycle,ecu_sample_time_known,transport"
        const val CYCLE_HEADER="wall_time_ms,trip_id,fuel_session_id,epoch,cycle_id,profile,poll_mode,cycle_start_ns,cycle_end_ns,complete_count,expected_count,complete_set,steady_candidate,dynamic_candidate,host_window_ms,latest_age_ms,host_skew_ms,obd_complete,knock_complete,ign_complete,aborted,notes,transport"
        // STEADY/DYNAMIC limits are explicit *research heuristics*, NOT verified physics.
        const val STEADY_WINDOW_MS=750L
        const val DYNAMIC_WINDOW_MS=150L
        private val ids=listOf(0x0C,0x04,0x0B,0x4A37,0x4A38,0x4A39,0x4A3A,0x4A49,0x4A4A,0x4A4C,0x4A4D)
        private val labels=listOf("RPM","LOAD","MAP","KNOCK1","KNOCK2","KNOCK3","KNOCK4","IGN1","IGN2","IGN3","IGN4")
        private fun clean(x:String)=x.replace(',', ';').replace('\n',' ').replace('\r',' ').take(70)
        private fun nanosToMs(a:Long,b:Long):Long?=if(a>=0 && b>=a)(b-a)/1_000_000L else null
        private fun number(n:Long?):String=n?.toString()?:""
    }
    data class Timing(
        val requestStartNs:Long,
        val sendStartNs:Long,
        val sendEndNs:Long,
        val firstByteNs:Long?,
        val responseCompleteNs:Long?,
        val decodeEndNs:Long?,
        val valid:Boolean,
        val error:String=""
    )
    data class Outcome(
        val completeCount:Int,val complete:Boolean,
        val steadyCandidate:Boolean,val dynamicCandidate:Boolean,
        val hostWindowMs:Long?,val latestAgeMs:Long?,
        val hostSkewMs:Long?,val aborted:Boolean
    )
    private data class Transaction(val wall:Long,val id:Long,val channel:String,
                                   val dids:List<Int>,val time:Timing)
    private data class Context(val trip:Long,val fuel:Int,val epoch:Int,
                               val cycle:Long,val profile:String,val mode:String,val transport:String,
                               val startedNs:Long,val txs:MutableList<Transaction>)
    private var ctx:Context?=null
    private var nextTx=0L
    private val lastValidById=mutableMapOf<Int,Pair<Int,Transaction>>()
    private var droppedDueToCycleLimit=0

    fun begin(trip:Long,fuel:Int,epoch:Int,cycle:Long,profile:String,
              mode:String,atNs:Long,transport:String="UNKNOWN") {
        if(ctx!=null)abort(atNs,"implicit_cycle_reset")
        ctx=Context(trip,fuel,epoch,cycle,profile,mode,transport,atNs,ArrayList(24))
        // A previous epoch cannot supply current valid FAST measurements.
        lastValidById.keys.toList().forEach { key ->
            if(lastValidById[key]?.first!=epoch)lastValidById.remove(key)
        }
    }

    /** Invoked in measuredDiagnostic's finally; never suppress a transport failure. */
    fun transaction(channel:String,dids:List<Int>,wall:Long,time:Timing) {
        val cycle=ctx?:return
        if(cycle.txs.size>=128) {
            droppedDueToCycleLimit++
            return
        }
        val event=Transaction(wall,++nextTx,channel,dids.toList(),time)
        cycle.txs.add(event)
        if(time.valid && dids.isNotEmpty())
            dids.forEach { lastValidById[it]=cycle.epoch to event }
    }

    fun finish(atNs:Long,actualPresent:Map<Int,Boolean>):Outcome =
        flush(atNs,actualPresent,false,"")
    fun abort(atNs:Long,reason:String):Outcome =
        flush(atNs,emptyMap(),true,reason)

    private fun flush(atNs:Long,actual:Map<Int,Boolean>,
                      aborted:Boolean,reason:String):Outcome {
        val c=ctx?:return Outcome(0,false,false,false,null,null,null,aborted)
        ctx=null
        fun base(wall:Long)=listOf(wall,c.trip,c.fuel,c.epoch,c.cycle,clean(c.profile),clean(c.mode))
        val txLines=c.txs.map { t ->
            (base(t.wall)+listOf(t.id,clean(t.channel),
                clean(t.dids.joinToString("|"){String.format(Locale.US,"%04X",it)}),
                t.time.requestStartNs,t.time.sendStartNs,t.time.sendEndNs,
                number(t.time.firstByteNs),number(t.time.responseCompleteNs),
                number(t.time.decodeEndNs),
                if(aborted)"" else atNs.toString(),
                t.time.valid,clean(t.time.error),clean(c.transport))).joinToString(",")
        }
        val accepted=ArrayList<Transaction>()
        var completed=0
        val signalLines=ids.indices.map{index ->
            val did=ids[index]
            val current=c.txs.lastOrNull { it.time.valid && did in it.dids }
            val older=lastValidById[did]?.takeIf { it.first==c.epoch }?.second
            val available=actual[did]==true
            val status=when {
                !aborted && available && current!=null -> "VALID"
                current!=null && !available -> "INVALID"
                !aborted && c.txs.any { did in it.dids && !it.time.valid } -> "INVALID"
                older!=null && older!==current -> "STALE"
                else -> "MISSING"
            }
            if(status=="VALID") {
                completed++
                accepted.add(current!!)
            }
            val provenance=if(status=="VALID")current else if(status=="STALE")older else null
            val age=provenance?.time?.responseCompleteNs?.let{nanosToMs(it,atNs)}
            (base(System.currentTimeMillis())+listOf(labels[index],
                String.format(Locale.US,"%04X",did),status,
                provenance?.id?.toString()?:"",
                number(provenance?.time?.requestStartNs),
                number(provenance?.time?.responseCompleteNs),
                number(provenance?.time?.decodeEndNs),atNs,
                number(age),false,false,clean(c.transport))).joinToString(",")
        }
        val minRequest=accepted.minOfOrNull{it.time.requestStartNs}
        val maxResponse=accepted.mapNotNull{it.time.responseCompleteNs}.maxOrNull()
        val minResponse=accepted.mapNotNull{it.time.responseCompleteNs}.minOrNull()
        val window=if(minRequest!=null && maxResponse!=null)
            nanosToMs(minRequest,maxResponse)else null
        val skew=if(minResponse!=null && maxResponse!=null)
            nanosToMs(minResponse,maxResponse)else null
        val latestAge=if(minResponse!=null)nanosToMs(minResponse,atNs) else null
        val complete=completed==ids.size && !aborted
        val steady=complete && window!=null && latestAge!=null &&
            window<=STEADY_WINDOW_MS && latestAge<=STEADY_WINDOW_MS
        val dynamic=complete && window!=null && latestAge!=null &&
            window<=DYNAMIC_WINDOW_MS && latestAge<=DYNAMIC_WINDOW_MS
        val result=Outcome(completed,complete,steady,dynamic,window,latestAge,skew,aborted)
        // One bounded queue offer per output file per cycle; best-effort
        // research rows cannot stall main lossless logger. Its queue loss
        // is exposed by existing HighSpeedMetrics/AsyncCsvWriter.
        try { if(txLines.isNotEmpty())onTransactions(txLines.joinToString("\n")) }catch(_:Exception){}
        try { onSignals(signalLines.joinToString("\n")) }catch(_:Exception){}
        try {
            onCycle((base(System.currentTimeMillis())+listOf(
                c.startedNs,atNs,completed,ids.size,complete,
                steady,dynamic,number(window),number(latestAge),number(skew),
                ids.take(3).all{ actual[it]==true },
                ids.drop(3).take(4).all{actual[it]==true},
                ids.takeLast(4).all{actual[it]==true},
                aborted,clean(reason+(if(droppedDueToCycleLimit>0)
                    ";trace_limit_dropped="+droppedDueToCycleLimit else "")),clean(c.transport))).joinToString(","))
        }catch(_:Exception){}
        droppedDueToCycleLimit=0
        return result
    }
}
