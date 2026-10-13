package com.example.bmwenettest

/**
 * Independent side-by-side research data. OBD is the legacy 1.7 reference,
 * RAM is 2.0 experimental. These values are not simultaneous ECU snapshots.
 */
internal data class Octane2Cycle(
    val wallTimeMs:Long,val elapsedMs:Long,val tripId:Long,val fuelSession:Int,
    val epoch:Int,val pollMode:String,val obdRpm:Double?,val ramRpm:Double?,
    val effectiveRpm:Double?,val rpmSource:String,val ramState:String,
    val obdLatencyMs:Long?,val ramLatencyMs:Long?,val ramAfterObdMs:Long?,
    val cycleMs:Long,val gapMs:Long?,val mapKpa:Double?,val loadPct:Double?,
    val speed:Double?,val iat:Double?,val coolant:Double?,
    val knockMean:Double?,val ignitionSpread:Double?,
    val actualLambda:Double?,val lambdaPid:Int?,
    val lambdaAgeMs:Long?,val lambdaAux:Double?,val commandedEq:Double?,
    val commandedAgeMs:Long?,val stft:Double?,val ltft:Double?,
    val controlState:String?,val steadyIndex:Int,val steadyHoldMs:Long,
    val legacySessionScore:Double?,val legacyConfidence:Int
)
internal object Octane2CycleCsv {
    const val HEADER="wall_time_ms,elapsed_ms,trip_id,fuel_session,epoch,poll_mode,"+
        "obd_rpm,ram_rpm,effective_rpm,rpm_source,ram_state,"+
        "obd_latency_ms,ram_latency_ms,ram_after_obd_ms,cycle_ms,cycle_gap_ms,"+
        "map_kpa_abs,load_pct,speed_kmh,iat_c,coolant_c,"+
        "knock_mean_vms,ignition_spread_deg,"+
        "actual_lambda,actual_lambda_pid,lambda_age_ms,lambda_voltage_or_current,"+
        "commanded_equivalence_ratio,commanded_age_ms,stft_pct,ltft_pct,"+
        "fuel_control_state,steady_region,steady_hold_ms,legacy17_score,legacy17_confidence_pct"
    fun file(start:Long)="bmw_octane2_comparison_v1_${start}.csv"
    fun encode(s:Octane2Cycle):String {
        fun n(v:Double?)=v?.takeIf{it.isFinite()}?.toString()?:""
        fun l(v:Long?)=v?.takeIf{it>=0L}?.toString()?:""
        fun txt(v:String)=v.replace(',',';').replace('\n',' ').take(80)
        return listOf(s.wallTimeMs,s.elapsedMs,s.tripId,s.fuelSession,
            s.epoch,txt(s.pollMode),n(s.obdRpm),n(s.ramRpm),n(s.effectiveRpm),
            txt(s.rpmSource),txt(s.ramState),l(s.obdLatencyMs),l(s.ramLatencyMs),
            l(s.ramAfterObdMs),s.cycleMs,l(s.gapMs),n(s.mapKpa),n(s.loadPct),
            n(s.speed),n(s.iat),n(s.coolant),n(s.knockMean),
            n(s.ignitionSpread),n(s.actualLambda),s.lambdaPid?:"",
            l(s.lambdaAgeMs),n(s.lambdaAux),n(s.commandedEq),
            l(s.commandedAgeMs),n(s.stft),n(s.ltft),
            txt(s.controlState?:""),s.steadyIndex,s.steadyHoldMs,
            n(s.legacySessionScore),s.legacyConfidence
        ).joinToString(",")
    }
    fun acceptedRows(lines:Sequence<String>)=lines.count {
        it.isNotBlank() && !it.startsWith("#") && it!=HEADER && it.split(',').size==36
    }
}
