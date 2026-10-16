package com.example.bmwenettest

import java.io.File

/**
 * LAB-only adapter: serial transaction owner stays in EnetLoggerService.
 * Feature benchmark holds no sockets, Android Service references or threads.
 */
internal object DmeBenchmarkRuntimeFactory {
    fun create(enabled:Boolean,dir:File,startedMs:Long,
               emit:(String,Boolean)->Unit):DmeBenchmarkRuntime? {
        if(!enabled)return null
        return try {
            LabDmeBenchmarkRuntime(dir,startedMs,emit)
        }catch(e:Exception) {
            emit("Cannot create safe benchmark evidence files: "+e.javaClass.simpleName,true)
            null
        }
    }
}

private class LabDmeBenchmarkRuntime(
    directory:File,startedAtMs:Long,
    private val emit:(String,Boolean)->Unit
):DmeBenchmarkRuntime {
    private val suite=AutomatedDmeBenchmark(startedAtMs)
    private val stem="bmw_dme_benchmark_suite_v1_${System.currentTimeMillis()}"
    private val stagesFile=File(directory,stem+".csv").apply {
        writeText("# stationary_read_only=true;ecu_sample_time=unknown;"+
            "android_elapsed_includes_suspend=true;parking_gear_unverified=true\n"+
            AutomatedDmeBenchmark.HEADER+"\n")
    }
    private val wireFile=File(directory,stem+"_wire_v1.csv").apply {
        writeText("# host_read_timestamps_not_ecu_acquisition=true;"+
            "ack_is_not_data=true\n"+DiagnosticWireTrace.HEADER+"\n")
    }
    private val textFile=File(directory,stem+".txt")
    private val wire=DiagnosticWireTrace()
    private var lastProgressMs=0L
    override val terminated:Boolean get()=suite.terminal
    override val termination:DmeBenchmarkRuntime.Termination? get()=when(suite.state) {
        AutomatedDmeBenchmark.State.COMPLETED ->
            DmeBenchmarkRuntime.Termination.COMPLETED
        AutomatedDmeBenchmark.State.ABORTED ->
            DmeBenchmarkRuntime.Termination.ABORTED
        else -> null
    }
    override val stopReason:String? get()=suite.failure
    override val stage:DmeBenchmarkRuntime.Stage?
        get()=suite.current?.let{
            DmeBenchmarkRuntime.Stage(it.id,it.pollMode,it.profile,it.background)
        }
    override fun status(nowMs:Long):String=suite.status(nowMs)
    override fun recordWire(tx:DmeBenchmarkRuntime.Wire) {
        wire.record(DiagnosticWireTrace.Tx(
            tx.wallMs,tx.channel,tx.requestStartNs,tx.sendStartNs,tx.sendEndNs,
            tx.ackVerifiedNs,tx.dataHeaderFirstReadNs,tx.dataCompleteNs,
            tx.decodeEndNs,tx.valid,tx.error,
            tx.requestedIds,tx.requestSid,tx.recvPhase,tx.phaseAtNs,
            tx.failedAtNs,tx.exceptionClass,tx.exceptionMessage,tx.unfinished))
    }
    override fun discardWire()=wire.discard()
    override suspend fun flushWire(epoch:Int,cycle:Long,publishNs:Long,writer:AsyncCsvWriter) {
        wire.flush(stage?.id?:-1,epoch,cycle,publishNs)?.let {
            // Unlike optional FAST metrics, a failed/incomplete transaction is
            // safety-critical diagnostic evidence: enqueue with backpressure.
            if(!writer.append(wireFile,it))
                emit("HSFZ wire journal write failed: "+wireFile.name,true)
        }
    }
    override suspend fun onCycle(cycle:DmeBenchmarkRuntime.Cycle,
                                 writer:AsyncCsvWriter):Boolean {
        val event=suite.onCycle(AutomatedDmeBenchmark.Cycle(
            cycle.nowMs,cycle.wallMs,cycle.speedKmh,cycle.speedAgeMs,
            cycle.coolantC,cycle.coolantAgeMs,cycle.rpm,cycle.actualMode,
            cycle.actualProfile,cycle.epoch,cycle.complete,cycle.skewMs,
            cycle.cycleMs,cycle.visible,cycle.csvLost))
        if(event!=null) {
            writer.append(stagesFile,suite.csvRow(
                event.type,cycle.nowMs,cycle.wallMs,event.summary))
            emit(status(cycle.nowMs)+" • "+event.summary,event.terminal)
        }
        if(!suite.terminal && cycle.nowMs-lastProgressMs>=5_000L) {
            lastProgressMs=cycle.nowMs
            writer.append(stagesFile,suite.csvRow(
                if(suite.state==AutomatedDmeBenchmark.State.WAITING)
                    "WAIT" else "PROGRESS",
                cycle.nowMs,cycle.wallMs,status(cycle.nowMs)))
            emit(status(cycle.nowMs),false)
        }
        return suite.terminal
    }
    override suspend fun abort(nowMs:Long,reason:String,writer:AsyncCsvWriter) {
        if(suite.terminal)return
        val event=suite.abort(nowMs,reason)
        writer.append(stagesFile,suite.csvRow(
            event.type,nowMs,System.currentTimeMillis(),event.summary))
        emit(status(nowMs),true)
    }
    override suspend fun finish(nowMs:Long,writer:AsyncCsvWriter) {
        if(!suite.terminal)abort(nowMs,"LOGGER stopped before benchmark completed",writer)
        try {
            textFile.writeText(suite.reportText()+"\n"+
                "Raw HOST HSFZ: "+wireFile.name+"\n"+
                "FAST traces: bmw_fast_*_timing_v1_*.csv\n"+
                "High-Speed: bmw_highspeed_benchmark_v1_*.csv\n")
        }catch(e:Exception){emit("Report persistence failed: "+e.javaClass.simpleName,true)}
    }
}
