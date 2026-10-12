package com.example.bmwenettest

import kotlin.math.abs

/**
 * Deterministic offline replay of the observational CSV. Deliberately separate
 * from Fuel Score, OctaneCalibration and production STEADY acceptance.
 * Counts indicate replayable coverage, not statistically independent evidence.
 */
internal object DriveObservationReplay {
    enum class Phase { STEADY, ACCEL, TRANSIENT, INSUFFICIENT }
    data class Result(
        val rows:Int,val malformed:Int,val samples:Int,val steady:Int,
        val accel:Int,val transient:Int,val insufficient:Int,
        val steadyTargetA:Int,val steadyTargetB:Int,
        val reconnects:Int,val sessions:Int,val loggerStarts:Int
    )
    private const val MAX_GAP_MS=5000L

    class Analyzer {
        private var previous:DriveObservation?=null
        private var rows=0
        private var malformed=0
        private var samples=0
        private var steady=0
        private var accel=0
        private var transient=0
        private var insufficient=0
        private var targetA=0
        private var targetB=0
        private var reconnects=0
        private val sessionIds=mutableSetOf<Int>()
        private val tripIds=mutableSetOf<Long>()

        fun accept(line:String) {
            if(line.isBlank() || line.startsWith("#") || line==DriveObservationCsv.HEADER)
                return
            rows++
            val s=DriveObservationCsv.decode(line)
            if(s==null) { malformed++;previous=null;return }
            if(s.event!="FAST") {
                if(s.event=="DISCONNECTED")reconnects++
                previous=null
                return
            }
            samples++
            sessionIds.add(s.fuelSessionId)
            tripIds.add(s.tripId)
            val prior=previous?.takeIf { p ->
                p.tripId==s.tripId && p.fuelSessionId==s.fuelSessionId &&
                p.connectionEpoch==s.connectionEpoch && p.pollMode==s.pollMode &&
                s.elapsedMs>p.elapsedMs && s.elapsedMs-p.elapsedMs<=MAX_GAP_MS
            }
            val phase=classify(s,prior)
            when(phase) {
                Phase.STEADY -> {
                    steady++
                    when(SteadyTargets.targetIndex(s.rpm,s.mapKpa)) {
                        0 -> targetA++
                        1 -> targetB++
                    }
                }
                Phase.ACCEL -> accel++
                Phase.TRANSIENT -> transient++
                Phase.INSUFFICIENT -> insufficient++
            }
            // Every FAST row is the next temporal candidate; never stitch
            // across malformed rows, network events, refills or topology changes.
            previous=s
        }

        fun summary()=Result(rows,malformed,samples,steady,accel,transient,
            insufficient,targetA,targetB,reconnects,sessionIds.size,tripIds.size)
    }

    fun run(lines:Sequence<String>):Result =
        Analyzer().also { a -> lines.forEach(a::accept) }.summary()

    fun classify(s:DriveObservation,p:DriveObservation?):Phase {
        if(s.event!="FAST")return Phase.INSUFFICIENT
        val r=s.rpm ?: return Phase.INSUFFICIENT
        val m=s.mapKpa ?: return Phase.INSUFFICIENT
        val l=s.loadPct ?: return Phase.INSUFFICIENT
        val speed=s.speedKmh ?: return Phase.INSUFFICIENT
        val iat=s.iatC ?: return Phase.INSUFFICIENT
        val coolant=s.coolantC ?: return Phase.INSUFFICIENT
        if(r !in 500.0..8000.0 || m !in 10.0..350.0 ||
            l !in 0.0..100.0 || speed !in 0.0..300.0 ||
            iat !in -30.0..95.0 || coolant !in 65.0..130.0 ||
            !fresh(s.fastAgeMs,2500L) ||
            !fresh(s.speedAgeMs,5000L) ||
            !fresh(s.iatAgeMs,9000L) ||
            !fresh(s.coolantAgeMs,16000L))return Phase.INSUFFICIENT
        if(p==null)return Phase.INSUFFICIENT
        val lastR=p.rpm ?: return Phase.INSUFFICIENT
        val lastM=p.mapKpa ?: return Phase.INSUFFICIENT
        val dt=(s.elapsedMs-p.elapsedMs)/1000.0
        if(dt<=0.0 || dt>5.0)return Phase.INSUFFICIENT
        val rRate=(r-lastR)/dt
        val mRate=(m-lastM)/dt
        if(speed<15.0)return Phase.TRANSIENT
        if(r in 1300.0..<4500.0 && l in 15.0..68.0 &&
            m in 88.0..150.0 && iat in 10.0..65.0 &&
            abs(rRate)<=250.0 && abs(mRate)<=16.0)return Phase.STEADY
        if(r in 2000.0..4500.0 && l>=65.0 && m>=150.0 &&
            iat in 10.0..65.0 && rRate in 130.0..1800.0 &&
            mRate>=-65.0)return Phase.ACCEL
        return Phase.TRANSIENT
    }

    private fun fresh(ageMs:Long?,limitMs:Long)=
        ageMs!=null && ageMs>=0L && ageMs<=limitMs
}
