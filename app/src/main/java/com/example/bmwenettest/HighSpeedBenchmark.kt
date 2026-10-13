package com.example.bmwenettest

/**
 * Versioned extra CSV. NEVER changes existing diagnostic CSV schema.
 * One row per actual per-channel request group at a periodic sampling point.
 */
internal object HighSpeedBenchmark {
    const val VERSION="1"
    const val HEADER="wall_time_ms,trip_id,fuel_session_id,connection_epoch,"+
        "highspeed_profile,poll_mode,channel,requests,successful,error_count,"+
        "response_byte_changes,unchanged_responses,request_hz,success_hz,"+
        "response_completeness,setup_p95_ms,send_p95_ms,first_byte_p50_ms,"+
        "first_byte_p95_ms,first_byte_p99_ms,first_byte_max_ms,"+
        "receive_p95_ms,decode_p95_ms,transaction_p95_ms,"+
        "csv_queue_depth,csv_queue_max,csv_offered,csv_written,csv_lost,"+
        "csv_failed_files,csv_queue_wait_p95_ms,csv_write_p95_ms,"+
        "fast_cycle_ms,fast_hz,valid_knock_channels,valid_ign_channels"
    data class Sample(
        val wallMs:Long,val tripId:Long,val fuelSession:Int,val epoch:Int,
        val profile:String,val pollMode:String,
        val channel:HighSpeedMetrics.Summary,val writer:AsyncCsvWriter.QueueSnapshot,
        val cycleMs:Long,val fastHz:Double,val validKnock:Int,val validIgn:Int
    )
    private fun numeric(value:Double?):String =
        value?.takeIf{it.isFinite()}?.let{
            String.format(java.util.Locale.US,"%.3f",it)
        }?:""
    fun encode(row:Sample):String {
        val channel=row.channel
        val queue=row.writer
        return listOf(
            row.wallMs,row.tripId,row.fuelSession,row.epoch,row.profile,row.pollMode,
            channel.channel,channel.requests,channel.successful,channel.errors,
            channel.responseChanges,channel.responseUnchanged,
            numeric(channel.requestHz),numeric(channel.successHz),
            numeric(channel.completeness),numeric(channel.setupP95Ms),
            numeric(channel.sendP95Ms),numeric(channel.firstByteMedianMs),
            numeric(channel.firstByteP95Ms),numeric(channel.firstByteP99Ms),
            numeric(channel.firstByteMaxMs),numeric(channel.receiveP95Ms),
            numeric(channel.decodeP95Ms),numeric(channel.completeP95Ms),
            queue.depth,queue.maxDepth,queue.offered,queue.written,queue.lost,
            queue.failedFiles,numeric(queue.waitP95Ms),numeric(queue.writeP95Ms),
            row.cycleMs,numeric(row.fastHz),row.validKnock,row.validIgn
        ).joinToString(",")
    }
    fun validRow(line:String):Boolean {
        val cells=line.split(",")
        if(cells.size!=HEADER.split(",").size)return false
        if(cells[4] !in HighSpeedProfilePolicy.Profile.entries.map { it.name })
            return false
        return cells[0].toLongOrNull()!=null &&
            cells[1].toLongOrNull()!=null &&
            cells[2].toIntOrNull()!=null &&
            cells[3].toIntOrNull()!=null &&
            cells[7].toLongOrNull()!=null &&
            cells[8].toLongOrNull()!=null &&
            cells[9].toLongOrNull()!=null &&
            cells[7].toLong()>=cells[8].toLong() &&
            cells[7].toLong()==cells[8].toLong()+cells[9].toLong() &&
            cells[25].toIntOrNull()!=null &&
            cells[28].toLongOrNull()!=null
    }
    /** Offline, deterministic safety/coverage comparison, not ECU-derived truth. */
    data class Offline(val rows:Int,val validRows:Int,val sessions:Set<Int>,
                       val modes:Set<String>,val channels:Set<String>,
                       val incompleteRows:Int)
    fun analyze(lines:Sequence<String>):Offline {
        var rows=0;var valid=0
        val sessions=linkedSetOf<Int>()
        val modes=linkedSetOf<String>()
        val channels=linkedSetOf<String>()
        for(line in lines) {
            if(line.startsWith("#") || line==HEADER || line.isBlank())continue
            rows++
            if(!validRow(line))continue
            valid++
            val parts=line.split(",")
            sessions.add(parts[2].toInt())
            modes.add(parts[4])
            channels.add(parts[6])
        }
        return Offline(rows,valid,sessions,modes,channels,rows-valid)
    }
}
