package com.example.bmwenettest

/**
 * Stationary LAB benchmark guard ordering. At every new stage/reconnect,
 * missing or stale speed/coolant MUST be attempted BEFORE the FAST slice.
 * Uses existing SLOW scheduler, due windows and read-only requests; never
 * adds a second SLOW pass to the same cycle.
 */
object FastGuardPreflight {
    fun required(benchmark:Boolean,scheduler:SlowPollScheduler,nowMs:Long):Boolean =
        benchmark && (!scheduler.fresh(0x0D,nowMs) ||
            !scheduler.fresh(0x05,nowMs))
}
