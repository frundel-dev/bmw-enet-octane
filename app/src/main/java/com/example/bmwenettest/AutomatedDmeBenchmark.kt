package com.example.bmwenettest

import java.util.Locale
import kotlin.math.ceil

/**
 * Read-only, stationary experiment sequencer. Owned exclusively by the existing
 * single primary DME polling coroutine. No networking, sleep or file I/O here.
 *
 * Android elapsedRealtime() is wall elapsed, NOT proof that the process ran
 * continuously. Per-cycle gaps and background visibility are reported separately.
 */
internal class AutomatedDmeBenchmark(private val startedAtMs:Long) {
    data class Stage(
        val id:Int,val label:String,val pollMode:String,
        val profile:HighSpeedProfilePolicy.Profile,
        val durationMs:Long,val background:Boolean=false
    )
    companion object {
        val stages=listOf(
            Stage(1,"B+ / LEGACY","B+",HighSpeedProfilePolicy.Profile.LEGACY,120_000L),
            Stage(2,"B+ / CORE","B+",HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,120_000L),
            Stage(3,"B+ / EXTENDED","B+",HighSpeedProfilePolicy.Profile.HIGH_SPEED_EXTENDED,120_000L),
            Stage(4,"B / CORE","B",HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,120_000L),
            Stage(5,"B+ / CORE • повтор","B+",HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,120_000L),
            Stage(6,"CORE • фоновая работа","B+",HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,300_000L,true)
        )
        const val MAX_WAIT_FOR_PARKED_MS=90_000L
        const val MAX_SPEED_AGE_MS=5_000L
        const val MAX_COOLANT_AGE_MS=16_000L
        const val GAP_WARNING_MS=1_500L
        const val HEADER="event,wall_time_ms,stage_id,stage_name,poll_mode,profile,requested_ms,elapsed_ms,cycles,full_fast_sets,completeness,cycle_p95_ms,host_skew_p95_ms,observed_hz,stalls,max_gap_ms,background_cycles,background_gaps,csv_lost_delta,epoch_changes,notes"
        fun csvText(s:String)=s.replace(",", ";").replace("\n"," ").replace("\r"," ").take(240)
        fun f(n:Double?):String=n?.takeIf{it.isFinite()}?.let{
            String.format(Locale.US,"%.3f",it)
        }?:""
    }
    enum class State { WAITING, PENDING_STAGE, RUNNING, COMPLETED, ABORTED }
    data class Cycle(
        val nowMs:Long,val wallMs:Long,
        val speedKmh:Double?,val speedAgeMs:Long?,
        val coolantC:Double?,val coolantAgeMs:Long?,
        val rpm:Double?,
        val actualMode:String,val actualProfile:HighSpeedProfilePolicy.Profile,
        val epoch:Int,val fastComplete:Boolean,val hostSkewMs:Long?,
        val cycleMs:Long,val uiVisible:Boolean,val csvLost:Long
    )
    data class Event(val type:String,val summary:String,val terminal:Boolean=false)
    data class StageReport(
        val stage:Stage,val startedMs:Long,val finishedMs:Long,
        val cycles:Int,val complete:Int,val cycleP95:Double?,
        val skewP95:Double?,val gaps:Int,val maxGapMs:Long,
        val backgroundCycles:Int,val backgroundGaps:Int,
        val lostDelta:Long,val epochChanges:Int,val notes:String
    ) {
        val observedHz:Double get()=if(finishedMs>startedMs)
            cycles*1000.0/(finishedMs-startedMs) else 0.0
        val completeness:Double get()=if(cycles>0)complete.toDouble()/cycles else 0.0
    }
    private data class Acc(
        var cycles:Int=0,var complete:Int=0,var incompleteStreak:Int=0,
        var lastCycleMs:Long=0L,
        var gaps:Int=0,var maxGap:Long=0L,var backgroundCycles:Int=0,
        var backgroundGaps:Int=0,var epochChanges:Int=0,var epoch:Int?=null,
        var csvStart:Long?=null,var lastCsvLost:Long=0L,
        val durations:ArrayDeque<Long> = ArrayDeque(),
        val skews:ArrayDeque<Long> = ArrayDeque()
    )
    private var index=0
    private var stageStartMs=0L
    private var pendingSinceMs=startedAtMs
    private var acc=Acc()
    private val completed=mutableListOf<StageReport>()
    var state=State.WAITING
        private set
    var failure:String?=null
        private set
    val current:Stage? get()=if(state==State.COMPLETED || state==State.ABORTED)
        null else stages.getOrNull(index)
    val reports:List<StageReport> get()=completed.toList()
    val terminal:Boolean get()=state==State.COMPLETED || state==State.ABORTED
    fun status(nowMs:Long):String {
        val stage=current
        val prefix=when(state) {
            State.WAITING -> "Проверка стоянки и прогрева"
            State.PENDING_STAGE -> "Переключение • этап ${stage?.id}/6"
            State.RUNNING -> "Этап ${stage?.id}/6 • ${stage?.label}"
            State.COMPLETED -> "Завершено • 6/6"
            State.ABORTED -> "Остановлено • ${failure?:"ошибка"}"
        }
        return prefix + if(state==State.RUNNING)
            " • ${((nowMs-stageStartMs).coerceAtLeast(0L)/1000L)} / ${stage!!.durationMs/1000L} с" else ""
    }
    private fun percentile(values:ArrayDeque<Long>,q:Double):Double? {
        if(values.isEmpty())return null
        val sorted=values.sorted()
        return sorted[(ceil(q*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)]/1.0
    }
    private fun appendBounded(queue:ArrayDeque<Long>,v:Long?) {
        if(v==null || v<0)return
        queue.addLast(v)
        if(queue.size>4096)queue.removeFirst()
    }
    private fun report(nowMs:Long,notes:String):StageReport {
        val s=stages[index]
        return StageReport(s,stageStartMs,nowMs,acc.cycles,acc.complete,
            percentile(acc.durations,.95),percentile(acc.skews,.95),
            acc.gaps,acc.maxGap,acc.backgroundCycles,acc.backgroundGaps,
            (acc.lastCsvLost-(acc.csvStart?:acc.lastCsvLost)).coerceAtLeast(0L),
            acc.epochChanges,notes)
    }
    private fun parked(x:Cycle):Boolean =
        x.speedKmh!=null && x.speedKmh==0.0 &&
        x.speedAgeMs!=null && x.speedAgeMs in 0..MAX_SPEED_AGE_MS &&
        x.coolantC!=null && x.coolantC in 70.0..115.0 &&
        x.coolantAgeMs!=null && x.coolantAgeMs in 0..MAX_COOLANT_AGE_MS &&
        x.rpm!=null && x.rpm in 500.0..1600.0
    fun abort(nowMs:Long,reason:String):Event {
        if(terminal)return Event("ALREADY_TERMINAL",status(nowMs),true)
        failure=csvText(reason)
        if(state==State.RUNNING)completed.add(report(nowMs,"INCOMPLETE: "+failure))
        state=State.ABORTED
        return Event("ABORT",failure!!,true)
    }
    fun onCycle(x:Cycle):Event? {
        if(terminal)return null
        if(x.nowMs<startedAtMs)return abort(x.nowMs,"monotonic clock moved backwards")
        if(x.speedKmh!=null && x.speedKmh>0.0)
            return abort(x.nowMs,"vehicle speed above zero; stationary test cancelled")
        if(x.coolantC!=null && x.coolantC>115.0)
            return abort(x.nowMs,"coolant temperature too high")
        if(state==State.WAITING || state==State.PENDING_STAGE) {
            if(x.nowMs-pendingSinceMs>MAX_WAIT_FOR_PARKED_MS)
                return abort(x.nowMs,"no fresh stationary/warm DME evidence within 90 seconds")
            if(!parked(x))return null
            if(x.actualMode!=current!!.pollMode ||
                x.actualProfile!=current!!.profile)
                return abort(x.nowMs,"requested diagnostic mode unavailable at start")
            stageStartMs=x.nowMs
            acc=Acc()
            state=State.RUNNING
            return Event("START",status(x.nowMs))
        }
        if(!parked(x))return abort(x.nowMs,
            "motion, stale speed/coolant or idle RPM outside safe stationary range")
        val s=current!!
        if(x.actualMode!=s.pollMode || x.actualProfile!=s.profile)
            return abort(x.nowMs,"profile fallback or unexpected poll mode: "+
                x.actualMode+"/"+x.actualProfile.name)
        if(x.nowMs<acc.lastCycleMs)
            return abort(x.nowMs,"non-monotonic cycle time")
        val gap=if(acc.lastCycleMs>0)x.nowMs-acc.lastCycleMs else 0L
        if(gap>GAP_WARNING_MS) {
            acc.gaps++
            acc.maxGap=maxOf(acc.maxGap,gap)
            if(s.background)acc.backgroundGaps++
        }
        acc.lastCycleMs=x.nowMs
        acc.cycles++
        if(x.fastComplete) {
            acc.complete++
            acc.incompleteStreak=0
        } else acc.incompleteStreak++
        if(s.background && !x.uiVisible)acc.backgroundCycles++
        if(acc.epoch!=null && acc.epoch!=x.epoch)acc.epochChanges++
        acc.epoch=x.epoch
        acc.lastCsvLost=x.csvLost
        if(acc.csvStart==null)acc.csvStart=x.csvLost
        appendBounded(acc.durations,x.cycleMs)
        appendBounded(acc.skews,x.hostSkewMs)
        if(acc.incompleteStreak>=25)
            return abort(x.nowMs,"25 consecutive incomplete FAST sets; no valid comparison")
        if(x.nowMs-stageStartMs>=s.durationMs) {
            val summary=report(x.nowMs,if(s.background &&
                acc.backgroundCycles==0)"BACKGROUND_NOT_OBSERVED"
                else "DONE")
            completed.add(summary)
            index++
            if(index==stages.size) {
                state=State.COMPLETED
                return Event("COMPLETE","six stages finished; background execution "+
                    if(summary.backgroundCycles>0)"observed" else "NOT observed",true)
            }
            // All mode changes are applied by the owner before the NEXT cycle.
            state=State.PENDING_STAGE
            pendingSinceMs=x.nowMs
            stageStartMs=0L
            acc=Acc()
            return Event("STAGE_END","finished ${s.id}; next ${current!!.label}")
        }
        return null
    }
    fun csvRow(event:String,nowMs:Long,wallMs:Long,notes:String=""):String {
        val r=completed.lastOrNull()
        val s=if(event=="START" || event=="WAIT" || event=="PROGRESS")
            current else r?.stage ?: current
        val elapsed=if(event=="PROGRESS" && state==State.RUNNING)
            nowMs-stageStartMs else r?.let{it.finishedMs-it.startedMs}?:0L
        val base=listOf(event,wallMs,s?.id?:"",csvText(s?.label?:""),
            s?.pollMode?:"",s?.profile?.name?:"",s?.durationMs?:"",elapsed)
        val metrics=if(event=="START") {
            List<Any>(12){""}
        } else if(event=="PROGRESS") {
            val elapsedSec=(nowMs-stageStartMs).coerceAtLeast(1L)
            listOf(acc.cycles,acc.complete,
                f(if(acc.cycles>0)acc.complete.toDouble()/acc.cycles else null),
                f(percentile(acc.durations,.95)),f(percentile(acc.skews,.95)),
                f(acc.cycles*1000.0/elapsedSec),acc.gaps,acc.maxGap,
                acc.backgroundCycles,acc.backgroundGaps,
                (acc.lastCsvLost-(acc.csvStart?:acc.lastCsvLost)).coerceAtLeast(0),
                acc.epochChanges)
        } else listOf(r?.cycles?:"",r?.complete?:"",f(r?.completeness),
            f(r?.cycleP95),f(r?.skewP95),f(r?.observedHz),
            r?.gaps?:"",r?.maxGapMs?:"",r?.backgroundCycles?:"",
            r?.backgroundGaps?:"",r?.lostDelta?:"",r?.epochChanges?:"")
        return (base+metrics+csvText(notes)).joinToString(",")
    }
    fun reportText():String=buildString {
        append("BMW OCTANE DME BENCHMARK • observational host timing\n")
        append("Status: ").append(state).append("; failure=")
            .append(failure?:"none").append('\n')
        append("No ECU acquisition timestamp. ACK is NOT diagnostic DATA.\n")
        append("Stage timing uses Android elapsedRealtime; gaps and sample counts "+
            "are separately recorded; off-screen time is not guaranteed CPU execution.\n")
        append("Vehicle gear/Parking brake cannot be verified by these OBD values.\n")
        for(r in completed) {
            append(r.stage.id).append(". ").append(r.stage.label)
                .append(" • wall=").append((r.finishedMs-r.startedMs)/1000L)
                .append("s • cycles=").append(r.cycles)
                .append(" • full=").append(r.complete)
                .append(" • hz=").append(f(r.observedHz))
                .append(" • completeness=").append(f(r.completeness))
                .append(" • host skew p95 ms=").append(f(r.skewP95))
                .append(" • gaps=").append(r.gaps)
                .append(" (max ").append(r.maxGapMs).append("ms)")
                .append(" • CSV lost=").append(r.lostDelta)
                .append(" • ").append(r.notes).append('\n')
        }
        fun compare(a:StageReport?,b:StageReport?,title:String) {
            if(a==null || b==null || a.cycles<10 || b.cycles<10) {
                append(title).append(": insufficient cycles for comparison\\n")
                return
            }
            val ratio=if(a.observedHz>0.0)b.observedHz/a.observedHz else null
            append(title).append(": observed host Hz ratio=").append(f(ratio))
                .append(" • completeness delta=")
                .append(f(b.completeness-a.completeness))
                .append(" • host-skew p95 delta ms=")
                .append(f(if(a.skewP95!=null && b.skewP95!=null)
                    b.skewP95-a.skewP95 else null))
                .append("\\n")
        }
        compare(completed.firstOrNull{it.stage.id==1},
            completed.firstOrNull{it.stage.id==2},"B+/CORE versus B+/LEGACY")
        compare(completed.firstOrNull{it.stage.id==2},
            completed.firstOrNull{it.stage.id==3},"B+/EXTENDED versus B+/CORE")
        compare(completed.firstOrNull{it.stage.id==2},
            completed.firstOrNull{it.stage.id==4},"B/CORE versus B+/CORE")
        compare(completed.firstOrNull{it.stage.id==2},
            completed.firstOrNull{it.stage.id==5},"B+/CORE repeatability 2 versus 5")
        append("Comparisons are observational: different engine temperatures, "+
            "DME adaptation and Android scheduling may confound. "+
            "No fuel grade or ECU-internal timing claim.")
    }
}
