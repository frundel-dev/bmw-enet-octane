package com.example.bmwenettest

/**
 * LAB-only per-cycle Android-host phases. This is NOT an ECU sample clock.
 * Separate CSV schema; existing FAST transaction/signal/cycle and fuel CSV
 * formats must remain unchanged.
 */
internal object FastPhaseTiming {
    const val HEADER="wall_time_ms,trip_id,fuel_session_id,connection_epoch,cycle_id,profile,poll_mode,transport,fast_start_ns,obd_complete_ns,fast_end_ns,slow_start_ns,slow_end_ns,first_publish_ns,monitor_start_ns,monitor_end_ns,cycle_end_ns,prev_cycle_end_ns,fast_ms,obd_ms,slow_ms,post_fast_publish_ms,monitor_ms,cycle_ms,gap_before_ms,full_fast_11,ecu_sample_time_known"

    data class Sample(
        val wallMs:Long,
        val tripId:Long,
        val fuelSessionId:Int,
        val epoch:Int,
        val cycleId:Long,
        val profile:String,
        val mode:String,
        val transport:String,
        val fastStartNs:Long,
        val obdCompleteNs:Long,
        val fastEndNs:Long,
        val slowStartNs:Long,
        val slowEndNs:Long,
        val firstPublishNs:Long,
        val monitorStartNs:Long?,
        val monitorEndNs:Long?,
        val cycleEndNs:Long,
        val previousCycleEndNs:Long?,
        val fullFast:Boolean
    )

    private fun ms(start:Long?,end:Long?):String =
        if(start==null || end==null || start<0L || end<start) ""
        else "%.3f".format(java.util.Locale.US,(end-start)/1_000_000.0)

    private fun token(s:String):String=s.replace(",",";")
        .replace("\n"," ").replace("\r"," ").take(64)
    fun encode(s:Sample):String = listOf(
        s.wallMs,s.tripId,s.fuelSessionId,s.epoch,s.cycleId,
        token(s.profile),token(s.mode),token(s.transport),
        s.fastStartNs,s.obdCompleteNs,s.fastEndNs,
        s.slowStartNs,s.slowEndNs,s.firstPublishNs,
        s.monitorStartNs?:"",s.monitorEndNs?:"",
        s.cycleEndNs,s.previousCycleEndNs?:"",
        ms(s.fastStartNs,s.fastEndNs),
        ms(s.fastStartNs,s.obdCompleteNs),
        ms(s.slowStartNs,s.slowEndNs),
        ms(s.fastEndNs,s.firstPublishNs),
        ms(s.monitorStartNs,s.monitorEndNs),
        ms(s.fastStartNs,s.cycleEndNs),
        ms(s.previousCycleEndNs,s.fastStartNs),
        s.fullFast,false
    ).joinToString(",")
}

/** Called by the single socket owner, with no additional diagnostics. */
internal fun interface FastPhasePort {
    fun record(sample:FastPhaseTiming.Sample)
}
