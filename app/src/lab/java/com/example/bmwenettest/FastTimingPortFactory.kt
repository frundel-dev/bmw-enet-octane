package com.example.bmwenettest

import java.io.File

/** Passive LAB-only FAST trace. Uses existing audit queue; no ECU requests. */
internal object FastTimingPortFactory {
    fun create(dir:File,trip:Long,writer:AsyncCsvWriter):FastTimingPort? = try {
        val note="# host_monotonic_ns=true;ecu_sample_clock=unknown;"+
            "same_uds_reply_does_not_prove_simultaneous_acquisition=true;"+
            "dynamic_limits=research_only\n"
        val tx=File(dir,"bmw_fast_tx_timing_v1_${trip}.csv").apply {
            writeText(note+FastTimingTrace.TX_HEADER+"\n")
        }
        val signal=File(dir,"bmw_fast_signal_timing_v1_${trip}.csv").apply {
            writeText(note+FastTimingTrace.SIGNAL_HEADER+"\n")
        }
        val cycle=File(dir,"bmw_fast_cycle_timing_v1_${trip}.csv").apply {
            writeText(note+FastTimingTrace.CYCLE_HEADER+"\n")
        }
        LabFastTimingPort(FastTimingTrace(
            onTransactions={line->writer.offerAudit(tx,line)},
            onSignals={line->writer.offerAudit(signal,line)},
            onCycle={line->writer.offerAudit(cycle,line)}
        ))
    } catch(_:Exception) { null }
}

private class LabFastTimingPort(private val trace:FastTimingTrace):FastTimingPort {
    override fun begin(trip:Long,fuel:Int,epoch:Int,cycle:Long,profile:String,
                       mode:String,atNs:Long,transport:String) =
        trace.begin(trip,fuel,epoch,cycle,profile,mode,atNs,transport)
    override fun transaction(channel:String,dids:List<Int>,wall:Long,time:FastTimingPort.Timing) {
        trace.transaction(channel,dids,wall,FastTimingTrace.Timing(
            time.requestStartNs,time.sendStartNs,time.sendEndNs,time.firstByteNs,
            time.responseCompleteNs,time.decodeEndNs,time.valid,time.error))
    }
    override fun finish(atNs:Long,actual:Map<Int,Boolean>):FastTimingPort.Outcome {
        val x=trace.finish(atNs,actual)
        return FastTimingPort.Outcome(x.complete,x.hostSkewMs)
    }
    override fun abort(atNs:Long,reason:String){trace.abort(atNs,reason)}
}
