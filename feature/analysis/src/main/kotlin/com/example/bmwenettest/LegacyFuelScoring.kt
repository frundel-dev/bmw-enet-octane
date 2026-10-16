package com.example.bmwenettest

/**
 * Frozen legacy fuel-score computation moved out of the diagnostic polling
 * Service unchanged. These are RELATIVE telemetry metrics, not measured RON.
 */
object LegacyFuelScoring {
    data class Input(
        val runWeight:Double=0.0,val runWeightedRatio:Double=0.0,
        val runKnockEvents:Int=0,val runSuperEvents:Int=0,
        val runHighPoints:Int=0,val runValidPoints:Int=0,
        val autoMode:Boolean=true,
        val autoRatioWeight:Double=0.0,val autoRatioSum:Double=0.0,
        val autoKnockEvents:Int=0,val autoSuperEvents:Int=0,
        val sessionQualitySum:Double=0.0,val sessionScoreSum:Double=0.0,
        val sessionScores:List<Double> = emptyList(),
        val steadyPoints:Int=0,val steadySegments:Int=0,val steadyCells:Int=0,
        val accelPoints:Int=0,val accelSegments:Int=0,val accelCells:Int=0,
        val sessionValidRuns:Int=0,val sessionHighPoints:Int=0,
        val coverageCells:Int=0,
        val steadyRatioWeight:Double=0.0,val steadyRatioSum:Double=0.0,
        val accelRatioWeight:Double=0.0,val accelRatioSum:Double=0.0,
        val accelV08Weight:Double=0.0,val accelV08Sum:Double=0.0,
        val highRpmPoints:Int=0,val highRpmRatioSum:Double=0.0,
        val highRpmBaselineSum:Int=0,
        val pendingRefuel:Boolean=false,val mixingKm:Double=0.0
    )
    data class Output(
        val liveRunScore:Double?,val liveRunQuality:Int,val sessionScore:Double?,
        val steadyConfidence:Int,val accelConfidence:Int,
        val sessionConfidence:Int,val steadyScore:Double?,
        val accelScore:Double?,val accelV08Score:Double?,
        val highRpmMean:Double?,val highRpmBaselineAvg:Double?,
        val resultState:String
    )
    fun phaseConfidence(points:Int,segments:Int,cells:Int):Int {
        if(points==0 || segments==0 || cells==0)return 0
        val estimate=points.coerceAtMost(24) +
            (segments*8).coerceAtMost(40)+(cells*6).coerceAtMost(30)
        return if(points<10 || segments<3 || cells<3)
            estimate.coerceAtMost(49) else estimate.coerceAtMost(94)
    }
    fun evaluate(x:Input):Output {
        val liveRunRatio=if(x.runWeight>0.0)
            x.runWeightedRatio/x.runWeight else null
        val liveRunScore=liveRunRatio?.let{
            (100.0+(1.0-it)*50.0-(x.runKnockEvents-1).coerceAtLeast(0)*1.5-
                x.runSuperEvents*10.0).coerceIn(0.0,120.0)
        }
        val liveRunQuality=((x.runHighPoints*4+x.runValidPoints*2).coerceAtMost(100))
        val sessionScore=if(x.autoMode) {
            if(x.autoRatioWeight>0.0)
                (100.0+(1.0-x.autoRatioSum/x.autoRatioWeight)*50.0-
                    (x.autoKnockEvents-1).coerceAtLeast(0)*1.5-
                    x.autoSuperEvents*10.0).coerceIn(0.0,120.0)
            else null
        } else if(x.sessionQualitySum>0)
            x.sessionScoreSum/x.sessionQualitySum else null
        val consistency=if(x.sessionScores.size>=2) {
            val mean=x.sessionScores.average()
            val sd=kotlin.math.sqrt(x.sessionScores.sumOf{
                (it-mean)*(it-mean)}/x.sessionScores.size)
            (15.0-sd*3.0).toInt().coerceIn(0,15)
        }else 0
        val steadyConfidence=if(x.autoMode)
            phaseConfidence(x.steadyPoints,x.steadySegments,x.steadyCells)
                .coerceAtMost(49)
            else 0
        val accelConfidence=if(x.autoMode)
            phaseConfidence(x.accelPoints,x.accelSegments,x.accelCells)
            else 0
        val sessionConfidence=if(x.autoMode) {
            val primary=maxOf(steadyConfidence,accelConfidence)
            when {
                steadyConfidence>=60 && accelConfidence>=60 ->
                    (primary+10).coerceAtMost(95)
                x.steadyPoints>=4 && x.accelPoints>=4 ->
                    primary.coerceAtMost(82)
                else -> primary.coerceAtMost(74)
            }
        } else ((x.sessionValidRuns*8).coerceAtMost(40)+
            (x.sessionHighPoints*2).coerceAtMost(25)+
            (x.coverageCells*2).coerceAtMost(20)+consistency)
                .coerceAtMost(100)
        val steadyScore=if(x.steadyRatioWeight>0.0)
            (100.0+(1.0-x.steadyRatioSum/x.steadyRatioWeight)*50.0)
                .coerceIn(0.0,120.0) else null
        val accelScore=if(x.accelRatioWeight>0.0)
            (100.0+(1.0-x.accelRatioSum/x.accelRatioWeight)*50.0)
                .coerceIn(0.0,120.0) else null
        val accelV08Score=if(x.accelV08Weight>0.0)
            OctaneCalibration.experimentalScore(x.accelV08Sum/x.accelV08Weight)
            else null
        val highRpmMean=if(x.highRpmPoints>0)
            x.highRpmRatioSum/x.highRpmPoints else null
        val highRpmBaselineAvg=if(x.highRpmPoints>0)
            x.highRpmBaselineSum.toDouble()/x.highRpmPoints else null
        val resultState=when {
            x.pendingRefuel -> "REFUEL CHECK"
            x.mixingKm>0.0 -> "MIXING"
            sessionConfidence<60 -> "COLLECTING"
            sessionScore==null -> "COLLECTING"
            sessionScore>=95.0 -> "NORMAL"
            sessionScore>=92.0 -> "BORDERLINE"
            else -> "BELOW BASELINE"
        }
        return Output(liveRunScore,liveRunQuality,sessionScore,
            steadyConfidence,accelConfidence,sessionConfidence,
            steadyScore,accelScore,accelV08Score,highRpmMean,
            highRpmBaselineAvg,resultState)
    }
}
