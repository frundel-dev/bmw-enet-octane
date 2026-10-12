package com.example.bmwenettest

import java.io.Closeable
import java.io.Writer

/**
 * Append-only observational dataset. Values are observations, NOT fuel quality
 * scores. Freshness is per sensor: null means unavailable/unverified.
 * This schema is deliberately independent of the existing measurement CSVs.
 */
internal data class DriveObservation(
    val wallTimeMs:Long,
    val elapsedMs:Long,
    val tripId:Long,
    val fuelSessionId:Int,
    val connectionEpoch:Int,
    val event:String,
    val transport:String,
    val pollMode:String,
    val rpm:Double?=null,
    val mapKpa:Double?=null,
    val loadPct:Double?=null,
    val speedKmh:Double?=null,
    val iatC:Double?=null,
    val coolantC:Double?=null,
    val stftPct:Double?=null,
    val ltftPct:Double?=null,
    val knockVms:Double?=null,
    val ignSpreadDeg:Double?=null,
    val fastAgeMs:Long?=null,
    val speedAgeMs:Long?=null,
    val iatAgeMs:Long?=null,
    val coolantAgeMs:Long?=null,
    val stftAgeMs:Long?=null,
    val ltftAgeMs:Long?=null
)

internal object DriveObservationCsv {
    const val VERSION="1"
    const val HEADER="wall_time_ms,elapsed_ms,trip_id,fuel_session_id,connection_epoch,event,transport,poll_mode,"+
        "rpm,map_kpa_abs,load_pct,speed_kmh,iat_c,coolant_c,stft_pct,ltft_pct,"+
        "knock_mean_vms,ign_spread_deg,fast_age_ms,speed_age_ms,iat_age_ms,coolant_age_ms,stft_age_ms,ltft_age_ms"

    fun filename(startWallMs:Long)="bmw_observations_v1_${startWallMs}.csv"
    private fun text(value:String)=value.replace(',', ';').replace('\n',' ').replace('\r',' ').take(60)
    private fun number(value:Double?):String =
        if(value!=null && value.isFinite())value.toString() else ""
    private fun age(value:Long?):String=if(value!=null && value>=0L)value.toString() else ""

    fun encode(s:DriveObservation)=listOf(
        s.wallTimeMs,s.elapsedMs,s.tripId,s.fuelSessionId,s.connectionEpoch,
        text(s.event),text(s.transport),text(s.pollMode),
        number(s.rpm),number(s.mapKpa),number(s.loadPct),number(s.speedKmh),
        number(s.iatC),number(s.coolantC),number(s.stftPct),number(s.ltftPct),
        number(s.knockVms),number(s.ignSpreadDeg),
        age(s.fastAgeMs),age(s.speedAgeMs),age(s.iatAgeMs),
        age(s.coolantAgeMs),age(s.stftAgeMs),age(s.ltftAgeMs)
    ).joinToString(",")

    /**
     * Fail closed on malformed, nonfinite and negative-age fields.
     * Header/comments are ignored. Existing historical CSVs are not reinterpreted.
     */
    fun decode(line:String):DriveObservation? {
        if(line.isBlank() || line.startsWith("#") || line==HEADER)return null
        val a=line.split(',')
        if(a.size!=24)return null
        fun double(i:Int):Double?=a[i].takeIf{it.isNotBlank()}?.toDoubleOrNull()
        fun long(i:Int):Long?=a[i].takeIf{it.isNotBlank()}?.toLongOrNull()
        val optionalNumbers=(8..17).map { double(it) }
        val optionalAges=(18..23).map { long(it) }
        if((8..17).any { a[it].isNotBlank() && optionalNumbers[it-8]?.isFinite()!=true })
            return null
        if((18..23).any { a[it].isNotBlank() &&
            (optionalAges[it-18]==null || optionalAges[it-18]!!<0L) })return null
        val wall=a[0].toLongOrNull()?:return null
        val elapsed=a[1].toLongOrNull()?:return null
        val trip=a[2].toLongOrNull()?:return null
        val fuel=a[3].toIntOrNull()?:return null
        val epoch=a[4].toIntOrNull()?:return null
        if(wall<=0L || elapsed<0L || trip<=0L || fuel<1 || epoch<0 ||
            a[5].isBlank() || a[6].isBlank() || a[7].isBlank())return null
        return DriveObservation(wall,elapsed,trip,fuel,epoch,
            a[5],a[6],a[7],
            optionalNumbers[0],optionalNumbers[1],optionalNumbers[2],
            optionalNumbers[3],optionalNumbers[4],optionalNumbers[5],
            optionalNumbers[6],optionalNumbers[7],optionalNumbers[8],
            optionalNumbers[9],optionalAges[0],optionalAges[1],
            optionalAges[2],optionalAges[3],optionalAges[4],
            optionalAges[5])
    }
}

/**
 * Poll-thread-owned buffered writer: no second socket, no queue that could
 * reorder samples, and no flush per FAST cycle. A failed write disables only
 * this research journal; the primary logger is never interrupted.
 */
internal class DriveObservationJournal(private val out:Writer,
                                       private val flushIntervalMs:Long=3000L):Closeable {
    private var lastFlushMs=0L
    var failed:Boolean=false
        private set
    init { require(flushIntervalMs>=0L);out.write(DriveObservationCsv.HEADER+"\n") }

    fun append(sample:DriveObservation):Boolean {
        if(failed)return false
        return try {
            out.write(DriveObservationCsv.encode(sample))
            out.write("\n")
            if(lastFlushMs==0L || sample.elapsedMs-lastFlushMs>=flushIntervalMs ||
                sample.elapsedMs<lastFlushMs) {
                out.flush()
                lastFlushMs=sample.elapsedMs
            }
            true
        } catch(_:Exception) {
            failed=true
            false
        }
    }

    override fun close() {
        try { out.flush() } catch(_:Exception) { failed=true }
        try { out.close() } catch(_:Exception) { failed=true }
    }
}
