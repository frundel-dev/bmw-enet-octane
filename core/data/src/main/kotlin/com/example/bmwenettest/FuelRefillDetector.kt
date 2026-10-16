package com.example.bmwenettest

/**
 * Conservative tank-level change detector. A refuel is inferred, never guaranteed:
 * OBD fuel level can move with parking angle, fuel slosh, and sensor filtering.
 * Invoke only on fresh, valid PID 0x2F readings spaced about 10-15 seconds apart.
 */
class FuelRefillDetector(previousLevel: Double?) {
    data class Refill(val fromPercent: Double, val toPercent: Double)
    data class Update(val pending: Boolean, val refill: Refill?)

    var stableLevel: Double? = previousLevel?.takeIf { it.isFinite() && it in 0.0..100.0 }
        private set

    private var candidateLevel: Double? = null
    private var candidateAt = 0L
    private var candidateCount = 0

    val pending: Boolean get() = stableLevel != null && candidateCount > 0

    fun observe(percent: Double?, elapsedRealtimeMs: Long): Update {
        if (percent == null || !percent.isFinite() || percent !in 0.0..100.0)
            return Update(pending, null)

        val base = stableLevel
        if (base == null) {
            // No persisted level: establish a reference, without claiming a refuel.
            if (candidateLevel == null || kotlin.math.abs(percent - candidateLevel!!) > 3.0) {
                candidateLevel = percent; candidateAt = elapsedRealtimeMs; candidateCount = 1
            } else candidateCount++
            if (candidateCount >= 3 && elapsedRealtimeMs - candidateAt >= 20000L) {
                stableLevel = percent
                clearCandidate()
            }
            return Update(false, null)
        }

        if (percent - base >= 12.0) {
            if (candidateLevel == null || kotlin.math.abs(percent - candidateLevel!!) > 3.0) {
                candidateLevel = percent; candidateAt = elapsedRealtimeMs; candidateCount = 1
            } else candidateCount++
            if (candidateCount >= 3 && elapsedRealtimeMs - candidateAt >= 20000L) {
                val event = Refill(base, percent)
                stableLevel = percent
                clearCandidate()
                return Update(false, event)
            }
            return Update(true, null)
        }

        clearCandidate()
        // Permit normal consumption and small sensor drift; do not follow large increases.
        if (percent <= base + 3.0) stableLevel = percent
        return Update(false, null)
    }

    private fun clearCandidate() {
        candidateLevel = null; candidateAt = 0L; candidateCount = 0
    }
}
