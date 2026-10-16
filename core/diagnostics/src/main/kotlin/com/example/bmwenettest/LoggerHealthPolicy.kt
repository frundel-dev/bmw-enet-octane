package com.example.bmwenettest

/**
 * Monotonic-clock watchdog policy. Transport reads remain owned by the
 * diagnostic coroutine; the watchdog may only close its current socket.
 * Times in milliseconds are from SystemClock.elapsedRealtime().
 */
object LoggerHealthPolicy {
    const val FRESH_MAX_AGE_MS=5_000L
    const val WARN_AFTER_MS=8_000L
    const val RECOVER_AFTER_MS=18_000L
    enum class Phase { OFF, CONNECTING, POLLING, POST_CYCLE }
    enum class Action { NONE, WARN, CLOSE_SOCKET }
    data class Snapshot(
        val phase:Phase,
        val phaseSinceMs:Long,
        val lastCycleAtMs:Long=0L,
        val lastValidAtMs:Long=0L,
        val epoch:Int=0
    )
    fun start(now:Long)=Snapshot(Phase.CONNECTING,now)
    fun connected(old:Snapshot,now:Long,epoch:Int)=old.copy(
        phase=Phase.POST_CYCLE,phaseSinceMs=now,epoch=epoch)
    fun cycleStarted(old:Snapshot,now:Long)=old.copy(
        phase=Phase.POLLING,phaseSinceMs=now)
    fun cycleFinished(old:Snapshot,now:Long,validDme:Boolean)=old.copy(
        phase=Phase.POST_CYCLE,
        phaseSinceMs=now,
        lastCycleAtMs=now,
        lastValidAtMs=if(validDme)now else old.lastValidAtMs)
    fun reconnecting(old:Snapshot,now:Long)=old.copy(
        phase=Phase.CONNECTING,phaseSinceMs=now,lastValidAtMs=0L)
    fun freshness(active:Boolean,lastValidAtMs:Long,now:Long):Boolean =
        active && lastValidAtMs>0L && now>=lastValidAtMs &&
            now-lastValidAtMs<=FRESH_MAX_AGE_MS
    fun action(snapshot:Snapshot,now:Long):Action {
        if(snapshot.phase!=Phase.POLLING &&
            snapshot.phase!=Phase.POST_CYCLE)return Action.NONE
        if(now<snapshot.phaseSinceMs)return Action.NONE
        val age=now-snapshot.phaseSinceMs
        return when {
            age>=RECOVER_AFTER_MS -> Action.CLOSE_SOCKET
            age>=WARN_AFTER_MS -> Action.WARN
            else -> Action.NONE
        }
    }
}
