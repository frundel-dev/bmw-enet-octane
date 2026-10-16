package com.example.bmwenettest

/**
 * In-process presentation/capture port. The sole DME poll coroutine calls it
 * AFTER complete diagnostic exchanges; it never owns a TCP socket.
 * Implemented only by the lab variant; octane variant has a null factory.
 */
internal interface DmeBenchmarkRuntime {
    data class Stage(val id:Int,val mode:String,
        val profile:HighSpeedProfilePolicy.Profile,val background:Boolean)
    data class Cycle(val nowMs:Long,val wallMs:Long,val speedKmh:Double?,
        val speedAgeMs:Long?,val coolantC:Double?,val coolantAgeMs:Long?,
        val rpm:Double?,val actualMode:String,
        val actualProfile:HighSpeedProfilePolicy.Profile,val epoch:Int,
        val complete:Boolean,val skewMs:Long?,val cycleMs:Long,
        val visible:Boolean,val csvLost:Long)
    data class Wire(val wallMs:Long,val channel:String,val requestStartNs:Long,
        val sendStartNs:Long,val sendEndNs:Long,
        val ackVerifiedNs:Long?,val dataHeaderFirstReadNs:Long?,
        val dataCompleteNs:Long?,val decodeEndNs:Long?,
        val valid:Boolean,val error:String)
    enum class Termination { COMPLETED, ABORTED }
    val stage:Stage?
    val terminated:Boolean
    /** Exact terminal cause from the suite, not the coroutine completion cause. */
    val termination:Termination?
    val stopReason:String?
    fun status(nowMs:Long):String
    fun recordWire(tx:Wire)
    fun discardWire()
    fun flushWire(epoch:Int,cycle:Long,publishNs:Long,writer:AsyncCsvWriter)
    suspend fun onCycle(cycle:Cycle,writer:AsyncCsvWriter):Boolean
    suspend fun abort(nowMs:Long,reason:String,writer:AsyncCsvWriter)
    suspend fun finish(nowMs:Long,writer:AsyncCsvWriter)
}
