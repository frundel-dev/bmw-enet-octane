package com.example.bmwenettest

import kotlin.math.abs

/**
 * Read-only research-coverage benchmark for the two specifically missing
 * STEADY RPM × MAP regions. It is NOT a fuel test, an octane estimate,
 * or a self-updating calibration.
 */
internal object SteadyTargets {
    const val HEADER="wall_time_ms,trip_id,fuel_session_id,segment_id,rpm,map_kpa_abs,load_pct,iat_c,coolant_c,knock_mean_vms"
    const val POINTS_TARGET=24
    const val SEGMENTS_TARGET=4
    const val LOGGER_STARTS_TARGET=2
    const val MIN_DWELL_MS=5000L
    const val MIN_SAMPLE_INTERVAL_MS=2500L
    const val SEGMENT_GAP_MS=6000L
    const val MAP_MIN=105.0
    const val MAP_MAX_EXCLUSIVE=130.0

    data class Target(val rpmMin:Int,val rpmMaxExclusive:Int) {
        val title:String get()="${rpmMin}–${rpmMaxExclusive-1} об/мин"
    }
    val targets=listOf(Target(2500,3000),Target(3000,3500))
    fun filename(fuelId:Int)="bmw_steady_targets_v1732_fuel${fuelId}.csv"

    data class Record(
        val wallTimeMs:Long,val tripId:Long,val fuelSessionId:Int,val segmentId:Int,
        val rpm:Double,val map:Double,val load:Double,val iat:Double,
        val coolant:Double,val knock:Double
    )
    data class Cell(
        val points:Int=0,val segments:Int=0,val loggerStarts:Int=0,val iatBins:Int=0
    ) {
        val coveragePct:Int get()=(
            minOf(points.toDouble()/POINTS_TARGET,1.0)*100.0+
            minOf(segments.toDouble()/SEGMENTS_TARGET,1.0)*100.0+
            minOf(loggerStarts.toDouble()/LOGGER_STARTS_TARGET,1.0)*100.0
        ).div(3.0).toInt().coerceIn(0,100)
        val candidateSupport:Boolean get()=
            points>=POINTS_TARGET && segments>=SEGMENTS_TARGET &&
            loggerStarts>=LOGGER_STARTS_TARGET
    }
    data class Snapshot(val first:Cell,val second:Cell,val hint:String="Ожидаю данные DME") {
        val complete:Int get()=listOf(first,second).count { it.candidateSupport }
    }

    fun targetIndex(rpm:Double?,map:Double?):Int {
        if(rpm==null || map==null || !rpm.isFinite() || !map.isFinite() ||
            map<MAP_MIN || map>=MAP_MAX_EXCLUSIVE)return -1
        return targets.indexOfFirst{rpm>=it.rpmMin && rpm<it.rpmMaxExclusive}
    }

    fun serialize(s:Record)=listOf(s.wallTimeMs,s.tripId,s.fuelSessionId,
        s.segmentId,s.rpm,s.map,s.load,s.iat,s.coolant,s.knock).joinToString(",")

    fun parse(line:String):Record? {
        if(line.isBlank() || line.startsWith("#") || line.startsWith("wall_time"))return null
        val v=line.split(",")
        if(v.size!=10)return null
        val s=Record(
            v[0].toLongOrNull()?:return null,v[1].toLongOrNull()?:return null,
            v[2].toIntOrNull()?:return null,v[3].toIntOrNull()?:return null,
            v[4].toDoubleOrNull()?:return null,v[5].toDoubleOrNull()?:return null,
            v[6].toDoubleOrNull()?:return null,v[7].toDoubleOrNull()?:return null,
            v[8].toDoubleOrNull()?:return null,v[9].toDoubleOrNull()?:return null
        )
        if(s.wallTimeMs<=0 || s.tripId<=0 || s.fuelSessionId<1 || s.segmentId<=0 ||
            !s.load.isFinite() || s.load !in 15.0..68.0 ||
            !s.iat.isFinite() || s.iat !in 10.0..65.0 ||
            !s.coolant.isFinite() || s.coolant<65.0 ||
            !s.knock.isFinite() || s.knock<=0.0 ||
            targetIndex(s.rpm,s.map)<0)return null
        return s
    }

    class Tracker(private val fuelSessionId:Int, existing:Iterable<Record> = emptyList()) {
        private class Counter {
            var points=0
            val segments=mutableSetOf<Pair<Long,Int>>()
            val starts=mutableSetOf<Long>()
            val temperatureBins=mutableSetOf<Int>()
            fun result()=Cell(points,segments.size,starts.size,temperatureBins.size)
        }
        private val counters=Array(targets.size){Counter()}
        private var currentTarget=-1
        private var candidateSinceMs=0L
        private var lastEligibleMs=0L
        private var lastStoredMs=0L
        private var segmentSerial=0
        init { existing.forEach(::accept) }

        data class CaptureProgress(val targetIndex:Int, val dwellMs:Long)
        fun progress(nowMs:Long):CaptureProgress =
            if(currentTarget>=0 && candidateSinceMs>0L && lastEligibleMs>0L &&
                nowMs>=lastEligibleMs && nowMs-lastEligibleMs<=SEGMENT_GAP_MS)
                CaptureProgress(currentTarget,
                    (nowMs-candidateSinceMs).coerceIn(0L,MIN_DWELL_MS))
            else CaptureProgress(-1,0L)

        /** Discard partial dwell after invalid samples, reconnects or mode changes. */
        fun resetCandidate() {
            currentTarget=-1
            candidateSinceMs=0L
            lastEligibleMs=0L
        }

        fun accept(record:Record):Boolean {
            if(record.fuelSessionId!=fuelSessionId ||
                parse(serialize(record))==null)return false
            val bin=targetIndex(record.rpm,record.map)
            if(bin<0)return false
            val counter=counters[bin]
            counter.points++
            counter.segments.add(record.tripId to record.segmentId)
            counter.starts.add(record.tripId)
            counter.temperatureBins.add(kotlin.math.floor(record.iat/6.0).toInt())
            return true
        }

        /**
         * Only the network polling thread calls offer. This method does not
         * mutate counts until the caller has persisted the returned record.
         * Losing connectivity cannot falsely increase progress.
         */
        fun offer(nowMs:Long,wallTimeMs:Long,tripId:Long,rpm:Double?,map:Double?,
                  load:Double?,iat:Double?,coolant:Double?,knock:Double?,
                  eligible:Boolean):Record? {
            val bin=targetIndex(rpm,map)
            val valid=eligible && bin>=0 && nowMs>0 && wallTimeMs>0 && tripId>0 &&
                load!=null && load.isFinite() && load in 15.0..68.0 &&
                iat!=null && iat.isFinite() && iat in 10.0..65.0 &&
                coolant!=null && coolant.isFinite() && coolant>=65.0 &&
                knock!=null && knock.isFinite() && knock>0.0
            if(!valid) {
                // A sample outside RPM/MAP or failing STEADY/sensor validity
                // breaks the continuous 5-second hold immediately. Never
                // bridge a transient excursion into a false accepted segment.
                resetCandidate()
                return null
            }
            if(bin!=currentTarget || lastEligibleMs<=0 ||
                nowMs-lastEligibleMs>SEGMENT_GAP_MS) {
                currentTarget=bin
                candidateSinceMs=nowMs
                segmentSerial++
            }
            lastEligibleMs=nowMs
            if(nowMs-candidateSinceMs<MIN_DWELL_MS ||
                (lastStoredMs>0 && nowMs-lastStoredMs<MIN_SAMPLE_INTERVAL_MS))
                return null
            return Record(wallTimeMs,tripId,fuelSessionId,segmentSerial,
                rpm!!,map!!,load!!,iat!!,coolant!!,knock!!)
        }
        fun saved(record:Record,monotonicMs:Long) {
            if(accept(record))lastStoredMs=monotonicMs
        }
        fun snapshot(hint:String="Ожидаю данные DME")=
            Snapshot(counters[0].result(),counters[1].result(),hint)

        /**
         * Short contextual guidance for the next useful area. No invented
         * gear/speed targets and no attempt to control the car.
         */
        fun advice(rpm:Double?,map:Double?,qualified:Boolean,warm:Boolean):String {
            if(rpm==null || map==null || !rpm.isFinite() || !map.isFinite())
                return "Ожидаю свежие RPM и MAP от DME"
            val rpmBin=targets.indexOfFirst{rpm>=it.rpmMin && rpm<it.rpmMaxExclusive}
            val next=(0..1).filter{!counters[it].result().candidateSupport}
                .minByOrNull{counters[it].result().coveragePct} ?: 0
            if(rpmBin<0)return "Следующая цель: ${targets[next].title}, MAP 105–129 кПа"
            if(map<MAP_MIN)return "${targets[rpmBin].title}: MAP ${map.toInt()} кПа — нужно 105–129"
            if(map>=MAP_MAX_EXCLUSIVE)return "${targets[rpmBin].title}: MAP ${map.toInt()} кПа — выше 129"
            if(!warm)return "Условия целевые, но двигатель ещё не прогрет / IAT вне окна"
            if(!qualified)return "Целевые RPM/MAP, ожидаю стабильный STEADY и качественные датчики"
            if(counters[rpmBin].result().candidateSupport)
                return "Цель ${targets[rpmBin].title} покрыта предварительно; нужен контроль по поездкам"
            return "Собираю ${targets[rpmBin].title} при MAP ${map.toInt()} кПа • удерживай ровно"
        }
    }
}
