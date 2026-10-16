package com.example.bmwenettest

/** Presentation-only freshness check. Uses monotonic elapsedRealtime timestamps. */
internal object EngineReadingsFreshness {
    const val FAST_MAX_AGE_MS = 2_500L
    const val TEMPERATURE_MAX_AGE_MS = 8_000L

    fun isFresh(lastReceivedAtMs: Long, nowMs: Long, maxAgeMs: Long): Boolean =
        lastReceivedAtMs > 0L && maxAgeMs >= 0L && nowMs >= lastReceivedAtMs &&
            nowMs - lastReceivedAtMs <= maxAgeMs
}
