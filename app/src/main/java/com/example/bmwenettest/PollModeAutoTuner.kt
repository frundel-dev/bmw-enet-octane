package com.example.bmwenettest

import java.util.Locale

/**
 * Single-logger, in-session comparison of read-only DME polling strategies.
 *
 * Only one strategy is active at a time. No TCP reconnection is required to
 * advance stages: the logger retains its primary HSFZ socket and starts/stops
 * an independent secondary SLOW socket only at a stage boundary.
 *
 * A 5 s settling period is excluded, followed by >=30 s of valid engine
 * operation per mode. Ranking uses complete FAST data sets per wall second,
 * not a potentially optimistic raw loop/sample counter.
 */
class PollModeAutoTuner {
    data class Trial(
        val mode:String,
        val durationMs:Long,
        val cycles:Int,
        val complete:Int,
        val completeHz:Double,
        val completePercent:Double,
        val medianCycleMs:Long,
        val p95CycleMs:Long,
        val usable:Boolean,
        val reason:String
    ) {
        fun csv():String=listOf(
            mode,durationMs,cycles,complete,
            String.format(Locale.US,"%.3f",completeHz),
            String.format(Locale.US,"%.1f",completePercent),
            medianCycleMs,p95CycleMs,if(usable)1 else 0,reason.replace(",",";")
        ).joinToString(",")
    }

    data class Transition(
        val finished:Trial,
        val nextMode:String?,
        val selectedMode:String?,
        val note:String
    )

    companion object {
        const val SETTLE_MS=5000L
        const val MEASURE_MS=30000L
        const val MAX_MEASURE_MS=65000L
        const val MIN_CYCLES=12
        const val MIN_COMPLETE=10
    }

    val trials=mutableListOf<Trial>()
    var currentMode="A"
        private set
    var chosenMode:String?=null
        private set
    var status="Ожидание двигателя"
        private set
    private var stageStartMs=0L
    private var measureStartMs=0L
    private var cycles=0
    private var complete=0
    private val cycleDurations=ArrayList<Long>()
    private var batchUnsupported=false
    private var secondUnsupported=false

    fun reset() {
        trials.clear()
        currentMode="A"
        chosenMode=null
        status="Ожидание двигателя"
        stageStartMs=0
        measureStartMs=0
        cycles=0
        complete=0
        cycleDurations.clear()
        batchUnsupported=false
        secondUnsupported=false
    }

    fun stageNumber():Int=listOf("A","B","C","D").indexOf(currentMode)+1

    fun observe(
        nowMs:Long,
        engineOn:Boolean,
        cycleMs:Long,
        knockCount:Int,
        ignitionCount:Int,
        batchActive:Boolean,
        secondState:String,
        secondAgeMs:Long?
    ):Transition? {
        if(chosenMode!=null)return null
        if(!engineOn) {
            // The timer is not advanced with the engine off; extended gaps
            // make the current stage invalid rather than distorting its Hz.
            if(measureStartMs>0L && nowMs-measureStartMs>MAX_MEASURE_MS)
                return finish(nowMs,"engine stopped / telemetry gap")
            status="Ожидание двигателя • "+currentMode
            return null
        }
        if(stageStartMs==0L) {
            stageStartMs=nowMs
            status="AUTO • "+currentMode+" • стабилизация"
            return null
        }
        val stageAge=nowMs-stageStartMs
        if(stageAge<SETTLE_MS) {
            status="AUTO • "+currentMode+" • стабилизация"
            return null
        }
        val wantsBatch=currentMode=="B" || currentMode=="D"
        val wantsSecond=currentMode=="C" || currentMode=="D"
        if(wantsBatch && !batchActive) {
            batchUnsupported=true
            return finish(nowMs,"batch UDS unsupported or rejected")
        }
        if(wantsSecond && secondState=="FAILED") {
            secondUnsupported=true
            return finish(nowMs,"second TCP failed")
        }
        if(wantsSecond && stageAge>=14000L &&
            (secondState!="ACTIVE" || secondAgeMs==null || secondAgeMs>10000L)) {
            secondUnsupported=true
            return finish(nowMs,"second TCP missing/stale SLOW data")
        }
        if(measureStartMs==0L) {
            measureStartMs=nowMs
            status="AUTO • "+currentMode+" • сбор данных"
            return null
        }
        val gap=nowMs-measureStartMs
        if(gap>MAX_MEASURE_MS && cycles<MIN_CYCLES)
            return finish(nowMs,"insufficient cycles / slow responses")
        cycles++
        cycleDurations.add(cycleMs.coerceAtLeast(0))
        if(knockCount==4 && ignitionCount==4 &&
            (!wantsSecond || (secondAgeMs!=null && secondAgeMs<=10000L))) {
            complete++
        }
        status="AUTO • "+currentMode+" ("+stageNumber()+"/4) • "+
            cycles+" циклов • "+complete+" полных"
        if(gap>=MEASURE_MS && cycles>=MIN_CYCLES) return finish(nowMs,"measured")
        return null
    }

    private fun finish(nowMs:Long,reason:String):Transition {
        val elapsed=if(measureStartMs>0L)
            (nowMs-measureStartMs).coerceAtLeast(1L) else 0L
        val hz=if(elapsed>0L) complete*1000.0/elapsed else 0.0
        val share=if(cycles>0) complete*100.0/cycles else 0.0
        val latency=cycleDurations.sorted()
        fun at(percentage:Double):Long {
            if(latency.isEmpty())return 0
            val index=kotlin.math.ceil(latency.size*percentage).toInt()-1
            return latency[index.coerceIn(0,latency.lastIndex)]
        }
        val usable=reason=="measured" && cycles>=MIN_CYCLES &&
            complete>=MIN_COMPLETE && share>=90.0
        val trial=Trial(currentMode,elapsed,cycles,complete,hz,share,
            at(0.5),at(0.95),usable,reason)
        trials.add(trial)

        val modes=listOf("A","B","C","D")
        val next=modes.drop(modes.indexOf(currentMode)+1).firstOrNull { mode ->
            !(batchUnsupported && (mode=="B" || mode=="D")) &&
            !(secondUnsupported && (mode=="C" || mode=="D"))
        }
        if(next==null) {
            // Require a material performance improvement to justify a more
            // complex polling topology. If none is demonstrated, choose A.
            val candidates=trials.filter { it.usable }
            var best=candidates.firstOrNull() ?: trials.firstOrNull { it.mode=="A" }
            if(best!=null) for(t in candidates) {
                if(t.completeHz>best.completeHz*1.12) best=t
            }
            chosenMode=best?.mode ?: "A"
            currentMode=chosenMode!!
            status="AUTO • выбран "+currentMode+" • "+String.format(
                Locale.US,"%.2f",best?.completeHz ?: 0.0)+" полных/с"
            return Transition(trial,null,currentMode,
                "Завершено сравнение; консервативный выбор "+currentMode)
        }
        currentMode=next
        stageStartMs=nowMs
        measureStartMs=0L
        cycles=0
        complete=0
        cycleDurations.clear()
        status="AUTO • "+currentMode+" • стабилизация"
        return Transition(trial,next,null,"Переключение "+trial.mode+" → "+next)
    }

    /** Reconnect invalidates the unfinished experiment; rerun on the new link. */
    fun restartAfterReconnect()=reset()

    fun summary():String {
        val stats=trials.joinToString(" • ") { t ->
            t.mode+":"+String.format(Locale.US,"%.2f",t.completeHz)+
                (if(t.usable)"" else "!")
        }
        return status+(if(stats.isNotEmpty())"\nПолных наборов/с: "+stats else "")
    }
}
