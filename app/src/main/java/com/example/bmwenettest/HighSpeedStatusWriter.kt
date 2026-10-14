package com.example.bmwenettest

import java.io.File
import java.util.Locale

/**
 * Leaves the single-socket coroutine's hot path compact. Research metrics
 * generation remains on its original coroutine, with the same CSV Channel.
 */
internal object HighSpeedStatusWriter {
    data class Snapshot(
        val wallMs:Long,val tripId:Long,val fuelSession:Int,
        val epoch:Int,val profile:String,val effectiveMode:String,
        val cycleMs:Long,val fastHz:Double,val validKnock:Int,
        val validIgn:Int,val summaries:List<HighSpeedMetrics.Summary>,
        val queue:AsyncCsvWriter.QueueSnapshot
    )
    suspend fun appendAndSummarize(
        writer:AsyncCsvWriter,file:File,s:Snapshot
    ):String {
        for(item in s.summaries) {
            writer.append(file,HighSpeedBenchmark.encode(
                HighSpeedBenchmark.Sample(
                    s.wallMs,s.tripId,s.fuelSession,s.epoch,
                    s.profile,s.effectiveMode,item,s.queue,s.cycleMs,
                    s.fastHz,s.validKnock,s.validIgn
                )))
        }
        val core=s.summaries.filter { it.channel in listOf(
            "OBD_0C","OBD_04","OBD_0B","DID_BATCH_8",
            "DID_BATCH_4","DID_4A37","DID_4A49"
        ) }
        val errors=core.sumOf{it.errors}
        val minCompleteness=core.minOfOrNull{it.completeness}
        return "Профиль ${s.profile} • "+
            "${"%.1f".format(Locale.US,s.fastHz)} цикла/с"+
            " • каналов ${s.summaries.size}"+
            " • p95 OBD RPM ${core.firstOrNull{it.channel=="OBD_0C"}
                ?.firstByteP95Ms?.toString()?:"—"} мс"+
            " • полнота ключевых ${minCompleteness?.let{
                "%.0f".format(Locale.US,it*100)
            }?:"—"}%"+
            " • ошибок ${errors}"+
            " • CSV очередь ${s.queue.depth}"+
            " / потерь ${s.queue.lost}"
    }
}
