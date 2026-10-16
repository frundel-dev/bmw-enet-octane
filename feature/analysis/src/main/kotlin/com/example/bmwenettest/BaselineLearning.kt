package com.example.bmwenettest

import kotlin.math.abs

/**
 * Collection coverage for a VEHICLE/FUEL-SPECIFIC observational baseline.
 * This is NOT an RON model and never modifies OctaneCalibration / Fuel Score.
 *
 * A trip means one LOGGER start, not an independent calendar day or fuel batch.
 * Segment IDs are scoped to their trip, avoiding false independence from
 * adjacent 2-3 Hz DME samples.
 */
object BaselineLearning {
    const val PREF_ENABLED = "baseline_learning_enabled"
    const val PREF_DATASET = "baseline_learning_dataset"
    const val PREF_FUEL_ID = "baseline_learning_fuel_session"
    const val PREF_LABEL = "baseline_learning_label"

    enum class Phase { STEADY, ACCEL }
    data class Cell(val phase:Phase, val rpmIndex:Int, val loadIndex:Int)
    data class Sample(
        val wallTimeMs:Long, val tripId:Long, val fuelSessionId:Int,
        val phase:Phase, val segmentId:Int, val rpm:Double, val load:Double,
        val map:Double, val iat:Double, val coolant:Double, val stft:Double?,
        val ltft:Double?, val knock:Double, val ignitionSpread:Double
    )
    data class CellStats(
        val count:Int,val segments:Int,val trips:Int,val iatBuckets:Int,
        val provisionalMedianVms:Double?=null,
        val betweenTripMadPct:Double?=null
    ) {
        // Sufficient for a HOLD-OUT experiment, never "calibrated/verified octane".
        val readyForReview:Boolean get()=count>=24 && segments>=4 && trips>=2
        val progress:Int get()=minOf(
            (count*100/24).coerceAtMost(100),
            (segments*100/4).coerceAtMost(100),
            (trips*100/2).coerceAtMost(100)
        )
    }
    data class Advice(val cell:Cell, val text:String)

    val steadyRpm=listOf(1300,2000,2500,3000,3500,4500)
    val accelRpm=listOf(2500,3000,3500,4000,4500)
    val steadyLoad=listOf(15,35,50,69)
    val accelLoad=listOf(65,80,90,101)
    const val HEADER="wall_time_ms,trip_id,fuel_session_id,phase,segment_id,rpm,load_pct,map_kpa_abs,iat_c,coolant_c,stft1_pct,ltft1_pct,knock_mean_vms,ign_spread_deg"

    fun filename(id:Long)="bmw_baseline_learning_v1730_$id.csv"

    private fun index(bounds:List<Int>,value:Double):Int {
        if(!value.isFinite() || value<bounds.first() || value>=bounds.last())return -1
        return (0 until bounds.size-1).firstOrNull { value<bounds[it+1] } ?: -1
    }

    fun cell(phase:Phase,rpm:Double,load:Double,map:Double):Cell? {
        if(!map.isFinite())return null
        if(phase==Phase.STEADY && map !in 88.0..150.0)return null
        if(phase==Phase.ACCEL && map<200.0)return null
        val rb=index(if(phase==Phase.STEADY)steadyRpm else accelRpm,rpm)
        val lb=index(if(phase==Phase.STEADY)steadyLoad else accelLoad,load)
        return if(rb>=0 && lb>=0)Cell(phase,rb,lb) else null
    }

    fun label(cell:Cell):String {
        val rpm=if(cell.phase==Phase.STEADY)steadyRpm else accelRpm
        val load=if(cell.phase==Phase.STEADY)steadyLoad else accelLoad
        return "${rpm[cell.rpmIndex]}–${rpm[cell.rpmIndex+1]-1} об/мин · " +
            "нагрузка ${load[cell.loadIndex]}–${load[cell.loadIndex+1]-1}%"
    }

    // This is our own numeric-only CSV schema, never arbitrary user CSV.
    fun serialize(s:Sample)=listOf(
        s.wallTimeMs,s.tripId,s.fuelSessionId,s.phase.name,s.segmentId,
        s.rpm,s.load,s.map,s.iat,s.coolant,s.stft?:"",s.ltft?:"",
        s.knock,s.ignitionSpread
    ).joinToString(",")

    fun parse(line:String):Sample? {
        if(line.startsWith("#") || line.startsWith("wall_time") || line.isBlank())return null
        val f=line.split(",")
        if(f.size!=14)return null
        val s=Sample(
            f[0].toLongOrNull()?:return null,f[1].toLongOrNull()?:return null,
            f[2].toIntOrNull()?:return null,
            Phase.entries.firstOrNull{it.name==f[3]}?:return null,
            f[4].toIntOrNull()?:return null,f[5].toDoubleOrNull()?:return null,
            f[6].toDoubleOrNull()?:return null,f[7].toDoubleOrNull()?:return null,
            f[8].toDoubleOrNull()?:return null,f[9].toDoubleOrNull()?:return null,
            f[10].takeIf{it.isNotBlank()}?.toDoubleOrNull(),
            f[11].takeIf{it.isNotBlank()}?.toDoubleOrNull(),
            f[12].toDoubleOrNull()?:return null,f[13].toDoubleOrNull()?:return null
        )
        if(s.tripId<=0 || s.fuelSessionId<=0 || s.segmentId<=0 ||
            !s.iat.isFinite() || s.iat !in 10.0..65.0 ||
            !s.coolant.isFinite() || s.coolant<65.0 ||
            !s.knock.isFinite() || s.knock<=0.0 ||
            !s.ignitionSpread.isFinite() || s.ignitionSpread<0.0 ||
            listOfNotNull(s.stft,s.ltft).any{!it.isFinite()||abs(it)>15.0} ||
            cell(s.phase,s.rpm,s.load,s.map)==null) return null
        return s
    }

    private fun median(values:Collection<Double>):Double? {
        if(values.isEmpty())return null
        val sorted=values.sorted()
        val m=sorted.size/2
        return if(sorted.size%2==1) sorted[m]
            else (sorted[m-1]+sorted[m])/2.0
    }
    class Coverage {
        private class Counter {
            var n=0
            val tripIds=mutableSetOf<Long>()
            val segmentIds=mutableSetOf<Pair<Long,Int>>()
            val temperatures=mutableSetOf<Int>()
            val segmentKnock=mutableMapOf<Pair<Long,Int>,MutableList<Double>>()
            fun snapshot():CellStats {
                // Each trip contributes exactly one median, irrespective of
                // sample count, and every segment is first reduced to a median.
                val tripMedians=segmentKnock.entries.groupBy{it.key.first}
                    .mapNotNull { (_,entries)->
                        median(entries.mapNotNull{median(it.value)})
                    }
                val center=if(tripMedians.size>=2)median(tripMedians) else null
                val mad=if(center!=null && center>0.0)
                    median(tripMedians.map{kotlin.math.abs(it-center)})?.let{100.0*it/center}
                    else null
                return CellStats(n,segmentIds.size,tripIds.size,temperatures.size,
                    center,mad)
            }
        }
        private val counters=mutableMapOf<Cell,Counter>()
        var accepted:Int=0
            private set

        fun accept(s:Sample) {
            val c=cell(s.phase,s.rpm,s.load,s.map)?:return
            // Do not count invalid manually constructed rows.
            if(s.tripId<=0 || s.segmentId<=0 || s.knock<=0.0 || !s.knock.isFinite() ||
                s.iat !in 10.0..65.0 || s.coolant<65.0)return
            val v=counters.getOrPut(c){Counter()}
            v.n++; v.tripIds.add(s.tripId);v.segmentIds.add(s.tripId to s.segmentId)
            v.segmentKnock.getOrPut(s.tripId to s.segmentId){mutableListOf()}.add(s.knock)
            v.temperatures.add(kotlin.math.floor(s.iat/6.0).toInt())
            accepted++
        }
        fun stats(c:Cell)=counters[c]?.snapshot()?:CellStats(0,0,0,0)
        fun cells(phase:Phase):List<Cell> {
            val r=if(phase==Phase.STEADY)steadyRpm else accelRpm
            val l=if(phase==Phase.STEADY)steadyLoad else accelLoad
            return (0 until r.size-1).flatMap{i->
                (0 until l.size-1).map{j->Cell(phase,i,j)}
            }
        }
        fun reviewed(phase:Phase)=cells(phase).count{stats(it).readyForReview}

        fun exportCandidate():String {
            val header="phase,rpm_min,rpm_max,load_min,load_max,samples,segments,logger_starts,iat_buckets,provisional_knock_median_vms,between_trip_mad_pct,ron_verified"
            val rows=(cells(Phase.STEADY)+cells(Phase.ACCEL)).mapNotNull{c->
                val x=stats(c)
                if(!x.readyForReview || x.provisionalMedianVms==null)null
                else {
                    val rs=if(c.phase==Phase.STEADY)steadyRpm else accelRpm
                    val ls=if(c.phase==Phase.STEADY)steadyLoad else accelLoad
                    listOf(c.phase.name,rs[c.rpmIndex],rs[c.rpmIndex+1]-1,
                        ls[c.loadIndex],ls[c.loadIndex+1]-1,
                        x.count,x.segments,x.trips,x.iatBuckets,
                        x.provisionalMedianVms,x.betweenTripMadPct?:"",false)
                        .joinToString(",")
                }
            }
            return (listOf(
                "# observational_candidate_only=true;RON_unverified=true;Fuel_Score_unchanged=true",
                header
            )+rows).joinToString("\n")+"\n"
        }

        fun advice(phase:Phase,currentRpm:Double?=null,currentLoad:Double?=null):Advice {
            val cells=cells(phase)
            val targets=cells.filter{!stats(it).readyForReview}
            val chosen=(if(targets.isNotEmpty())targets else cells).minByOrNull { c ->
                val x=stats(c)
                val rpm=if(phase==Phase.STEADY)steadyRpm else accelRpm
                val load=if(phase==Phase.STEADY)steadyLoad else accelLoad
                val centerR=(rpm[c.rpmIndex]+rpm[c.rpmIndex+1])/2.0
                val centerL=(load[c.loadIndex]+load[c.loadIndex+1])/2.0
                // Finish started cells first, and prefer cells close to the
                // actual operating point. No speed/gear advice is inferred.
                (if(x.count==0)10.0 else 0.0)+
                    (if(x.count>=24)7.0 else 0.0)+
                    (if(currentRpm!=null)abs(centerR-currentRpm)/180.0 else abs(centerR-2650)/180.0)+
                    (if(currentLoad!=null)abs(centerL-currentLoad)/8.0 else 0.0)
            }!!
            val s=stats(chosen)
            val instruction=if(s.count>=24 && s.trips<2)
                "Нужен повтор при следующем запуске LOGGER."
            else if(s.count>=24 && s.segments<4)
                "Нужны новые независимые участки, не соседние отсчёты."
            else if(phase==Phase.STEADY)
                "На ровной дороге при разрешённой скорости удерживай подходящий режим 40–60 с; MAP 88–150 кПа."
            else
                "Собирай при допустимом безопасном разгоне; MAP ≥200 кПа. Не гонись за пустыми ячейками."
            return Advice(chosen,
                "${label(chosen)}. ${s.count}/24 точек, ${s.segments}/4 участков, ${s.trips}/2 запусков. $instruction")
        }
    }
}
