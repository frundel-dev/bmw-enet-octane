package com.example.bmwenettest

import kotlin.math.abs

/**
 * Within-logger-session survey statistics only: this class does not
 * create an AI-95 reference or assume samples from one segment are
 * independent trips. Results may be compared with future sessions.
 */
class SteadyCellStats {
    data class Summary(
        val observations:Int,
        val segments:Int,
        val medianVms:Double?,
        val dispersionPct:Double?,
        val repeatableWithinSession:Boolean
    )
    private data class Point(val segment:Int,val knock:Double)
    private val observations=linkedMapOf<String,MutableList<Point>>()

    fun record(key:String,segment:Int,knock:Double):Summary {
        if(!knock.isFinite() || knock<=0.0)return summary(key)
        val cell=observations.getOrPut(key){ mutableListOf() }
        cell.add(Point(segment,knock))
        // Bound resource usage for multi-hour driving sessions.
        if(cell.size>500)cell.removeAt(0)
        return summary(key)
    }
    fun summary(key:String):Summary {
        val points=observations[key] ?: return Summary(0,0,null,null,false)
        val sorted=points.map{it.knock}.sorted()
        val median=if(sorted.size%2==1)sorted[sorted.size/2]
                   else (sorted[sorted.size/2-1]+sorted[sorted.size/2])/2.0
        val deviations=sorted.map { abs(it-median) }.sorted()
        val mad=if(deviations.size%2==1)deviations[deviations.size/2]
                else (deviations[deviations.size/2-1]+deviations[deviations.size/2])/2.0
        val segments=points.map{it.segment}.distinct().size
        val dispersion=if(median>0.0)100.0*mad/median else null
        val repeatable=sorted.size>=10 && segments>=3 && dispersion!=null && dispersion<=8.0
        return Summary(points.size,segments,median,dispersion,repeatable)
    }
    fun cells():Int=observations.size
    fun repeatableCells():Int=observations.keys.count { summary(it).repeatableWithinSession }
    fun clear()=observations.clear()
}
