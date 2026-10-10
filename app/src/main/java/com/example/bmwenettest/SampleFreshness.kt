package com.example.bmwenettest

/**
 * Reject stale values when FAST and SLOW reads are combined. Always use
 * monotonic elapsedRealtime timestamps from the same Android boot.
 */
internal object SampleFreshness {
    const val SLOW_TTL_MS=10000L

    fun isFresh(nowMs:Long,measuredAtMs:Long?,ttlMs:Long=SLOW_TTL_MS):Boolean =
        measuredAtMs!=null && measuredAtMs>=0L && ttlMs>=0L &&
        nowMs>=measuredAtMs && nowMs-measuredAtMs<=ttlMs
}
