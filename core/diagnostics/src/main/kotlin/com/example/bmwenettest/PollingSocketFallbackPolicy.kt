package com.example.bmwenettest

/**
 * Last-resort fallback after a SocketException ESCAPES the normal serialized
 * reconnect loop. The outer coroutine calls this only after the previous
 * loop() has unwound its writers, never from a second socket reader.
 */
object PollingSocketFallbackPolicy {
    const val WINDOW_MS=300_000L
    const val MAX_ATTEMPTS=2
    enum class Action { RETRY, FAIL, STOP }
    data class Decision(val action:Action,val windowStart:Long,val attempt:Int)

    fun decide(running:Boolean,explicitStop:Boolean,nowMs:Long,
               previousWindowStart:Long,previousAttempts:Int):Decision {
        if(!running || explicitStop)return Decision(Action.STOP,previousWindowStart,previousAttempts)
        val fresh=previousWindowStart<=0L || nowMs<previousWindowStart ||
            nowMs-previousWindowStart>WINDOW_MS
        val count=if(fresh)1 else previousAttempts+1
        return Decision(if(count<=MAX_ATTEMPTS)Action.RETRY else Action.FAIL,
            if(fresh)nowMs else previousWindowStart,count)
    }
}
