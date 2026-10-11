package com.example.bmwenettest

/**
 * Pure, deterministic low-priority planner for DME history reads on the
 * *same* HSFZ socket, and ONLY between completed FAST cycles.
 *
 * Never runs a request concurrently with FAST and never probes detailed
 * DTC snapshots during driving. Suppress all monitoring after a transport
 * ambiguity until a new logger session.
 */
internal class DmeMonitorPolicy {
    data class Task(val service:Int,val did:Int,val intervalMs:Long,val idleOnly:Boolean=false) {
        val label:String get()=if(service==0x22)"%04X".format(did) else "DTC_CONFIRMED"
        fun request():ByteArray=if(service==0x22)byteArrayOf(
            0x22,(did ushr 8).toByte(),did.toByte())
        else byteArrayOf(0x19,0x02,0x08)
    }

    companion object {
        const val MIN_GAP_MS=8000L
        const val MAX_FAST_CYCLE_MS=650L
        val tasks=listOf(
            Task(0x22,0x554A,15000L),
            Task(0x22,0x4A85,45000L),
            Task(0x22,0x4A9D,45000L),
            Task(0x19,0,480000L,true)
        )
    }

    private val due=mutableMapOf<String,Long>()
    private val unsupported=mutableSetOf<String>()
    var suspended=false
        private set
    var lastAttemptMs=0L
        private set

    fun reset(nowMs:Long) {
        due.clear()
        unsupported.clear()
        suspended=false
        lastAttemptMs=nowMs
        // Stagger startup so three adaptation reads never arrive together.
        tasks.forEachIndexed { index,task ->
            due[task.label]=nowMs+when(index) {
                0 -> 12000L
                1 -> 25000L
                2 -> 35000L
                else -> 480000L
            }
        }
    }

    fun choose(nowMs:Long,completeFast:Boolean,cycleMs:Long,
               rpm:Double?,load:Double?,map:Double?,speed:Double?):Task? {
        if(suspended || !completeFast ||
            cycleMs !in 1L..MAX_FAST_CYCLE_MS ||
            nowMs-lastAttemptMs<MIN_GAP_MS ||
            rpm==null || load==null || map==null ||
            rpm<600.0 || rpm>4000.0 || load>=65.0 || map>=160.0)return null
        val eligible=tasks.filter {
            it.label !in unsupported &&
            nowMs>=(due[it.label] ?: Long.MAX_VALUE) &&
            (!it.idleOnly || (speed!=null && speed<=3.0 && rpm<=1100.0))
        }
        return eligible.minByOrNull { due[it.label] ?: Long.MAX_VALUE }
    }

    fun issued(task:Task,nowMs:Long) {
        lastAttemptMs=nowMs
        due[task.label]=nowMs+task.intervalMs
    }

    fun unsupported(task:Task) {
        unsupported.add(task.label)
    }

    fun suspend() { suspended=true }

    fun remaining(nowMs:Long):Int=tasks.count {
        it.label !in unsupported && nowMs<(due[it.label] ?: Long.MAX_VALUE)
    }
}
