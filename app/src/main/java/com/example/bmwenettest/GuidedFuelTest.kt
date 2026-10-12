package com.example.bmwenettest

import kotlin.math.abs

/**
 * Guided per-refill driving test, not automatic fuel self-calibration.
 * Measurement coverage may be sufficient while fuel quality remains UNKNOWN.
 * Only approved immutable historical 95-RON knock references are used for
 * a caveated *relative signal* comparison, never laboratory RON.
 */
internal object GuidedFuelTest {
    const val PREF_ID = "guided_fuel_test_id"
    const val PREF_ACTIVE = "guided_fuel_test_active"
    const val PREF_DONE = "guided_fuel_test_done"
    const val PREF_FUEL_SESSION = "guided_fuel_test_fuel_session"
    const val PREF_GRADE = "guided_fuel_test_grade"
    const val PREF_STATION = "guided_fuel_test_station"
    val grades = listOf("АИ-92", "АИ-95", "АИ-98", "АИ-100", "Другое")
    fun filename(id:Long)="bmw_fuel_test_v1731_$id.csv"

    data class CellProgress(val count:Int,val segments:Int) {
        val reached:Boolean get()=count>=4 && segments>=2
    }
    data class Overview(
        val steadyReady:Int, val accelReady:Int,
        val steadyPoints:Int,val accelPoints:Int,
        val progress:Int,val captureReady:Boolean,
        val relativeKnockDeltaPct:Double?,val comparableAccelBins:Int,
        val nextStep:String
    )
    private class Bucket {
        var count=0
        val segments=mutableMapOf<Pair<Long,Int>,MutableList<Double>>()
        fun add(sample:BaselineLearning.Sample, ratio:Double?=null) {
            count++
            segments.getOrPut(sample.tripId to sample.segmentId){mutableListOf()}
                .add(ratio?:sample.knock)
        }
        fun stat()=CellProgress(count,segments.size)
        fun segmentMedians()=segments.values.mapNotNull{ median(it) }
    }
    private fun median(items:Collection<Double>):Double? {
        if(items.isEmpty())return null
        val s=items.sorted()
        val n=s.size/2
        return if(s.size%2==1)s[n] else (s[n-1]+s[n])/2.0
    }

    /**
     * Snapshots computed only from distinct segments and bins.
     * No cell becomes a "good gasoline" result merely by collecting points.
     */
    class Coverage {
        private val steady=mutableMapOf<BaselineLearning.Cell,Bucket>()
        private val accel=mutableMapOf<Int,Bucket>()
        private val comparable=mutableMapOf<Int,Bucket>()
        var accepted=0
            private set

        fun accept(s:BaselineLearning.Sample) {
            // Re-run validation on disk reload; never include foreign/corrupt rows.
            if(BaselineLearning.parse(BaselineLearning.serialize(s))==null)return
            val cell=BaselineLearning.cell(s.phase,s.rpm,s.load,s.map)?:return
            accepted++
            if(s.phase==BaselineLearning.Phase.STEADY) {
                steady.getOrPut(cell){Bucket()}.add(s)
            } else {
                accel.getOrPut(cell.rpmIndex){Bucket()}.add(s)
                val ref=OctaneCalibration.accelerationReference(s.rpm,s.map)
                if(ref!=null && ref.validated && ref.trainingTrips>=3 && ref.knockMeanVms>0.0)
                    comparable.getOrPut(cell.rpmIndex){Bucket()}.add(
                        s,s.knock/ref.knockMeanVms)
            }
        }
        fun captureReady():Boolean =
            steady.values.count{it.stat().reached}>=2 &&
                accel.values.count{it.stat().reached}>=3

        fun snapshot():Overview {
            // At most three well-observed distinct STEADY cells and three
            // distinct ACCEL RPM bins, not completion of every theoretical
            // (sometimes unreachable) RPM x load cell.
            val sReady=steady.values.count{it.stat().reached}
            val aReady=accel.values.count{it.stat().reached}
            val sPoints=steady.values.sumOf{it.count}
            val aPoints=accel.values.sumOf{it.count}
            // Smooth progress respects independent segments: hundreds of
            // adjacent samples from one pass cannot fill one cell.
            fun fraction(b:Bucket):Double {
                val x=b.stat()
                return minOf(x.count/4.0,x.segments/2.0,1.0)
            }
            val scoreS=(steady.values.map(::fraction).sortedDescending()
                .take(2).sum()/2.0*100).toInt().coerceIn(0,100)
            val scoreA=(accel.values.map(::fraction).sortedDescending()
                .take(3).sum()/3.0*100).toInt().coerceIn(0,100)
            val progress=(scoreS*45+scoreA*55)/100
            val enough=sReady>=2 && aReady>=3
            // Compare equal-weight medians across RPM bins, then independent
            // acceleration segments within each RPM bin. Avoid bias from long
            // high-rate runs and avoid treating successive samples as trips.
            val means=comparable.filter{(_,bucket)->bucket.stat().reached}
                .values.mapNotNull{median(it.segmentMedians())}
            val relative=if(means.size>=2) median(means)?.let{(it-1.0)*100.0} else null
            val hint=when {
                enough -> "Достаточно точек для предварительного сравнения. Прямое RON не измерено."
                sReady<2 -> {
                    val candidates=BaselineLearning.Coverage()
                    // Recommend an incomplete CELL (RPM × engine LOAD),
                    // preferring the historically accessible 1300–2999 range.
                    val desired=BaselineLearning.Phase.STEADY
                    val bins= listOf(
                        BaselineLearning.Cell(desired,1,1),
                        BaselineLearning.Cell(desired,2,1),
                        BaselineLearning.Cell(desired,0,1),
                        BaselineLearning.Cell(desired,1,0),
                        BaselineLearning.Cell(desired,2,0),
                        BaselineLearning.Cell(desired,0,0)
                    )
                    val c=bins.firstOrNull{steady[it]?.stat()?.reached!=true}
                        ?: candidates.advice(desired).cell
                    "STEADY: ${BaselineLearning.label(c)}. Если позволяет дорога, удерживай плавно 30–60 с, MAP 88–150 кПа."
                }
                else -> {
                    val rpm=BaselineLearning.accelRpm
                    val rb=(0..3).firstOrNull{accel[it]?.stat()?.reached!=true}?:0
                    "ACCEL: нужны повторяемые участки ${rpm[rb]}–${rpm[rb+1]-1} об/мин, MAP ≥200 кПа. Только при безопасных и разрешённых условиях."
                }
            }
            return Overview(sReady,aReady,sPoints,aPoints,progress,enough,
                relative,means.size,hint)
        }
    }
}
