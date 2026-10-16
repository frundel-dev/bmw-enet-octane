package com.example.bmwenettest

/**
 * B/B+ share one TCP socket. Read at most two optional SLOW values in
 * each FAST loop rather than pausing FAST for a burst of ten requests.
 *
 * Scheduling a request advances its deadline even when the ECU rejects it,
 * avoiding repeated timeouts and starving other PIDs.
 */
class SlowPollScheduler {
    data class Entry(val did:Int,val intervalMs:Long,val ttlMs:Long)
    companion object {
        val entries=listOf(
            Entry(0x0D,1500,5000),   // vehicle speed
            Entry(0x5728,1500,5000), // superknock indication (UDS)
            Entry(0x11,2000,6000),   // throttle
            Entry(0x06,2500,7000),   // STFT
            Entry(0x0F,3000,9000),   // IAT
            Entry(0x44,3000,9000),   // lambda
            Entry(0x0E,4000,10000),  // ignition advance
            Entry(0x05,6000,16000),  // coolant
            Entry(0x5C,20000,45000), // oil
            Entry(0x07,25000,55000)  // LTFT
        )
    }
    private val deadlines=HashMap<Int,Long>()
    private val received=HashMap<Int,Long>()
    // Diagnostic evidence: retain the time of the last successful guard read
    // even after an invalid response (which must NOT remain a valid reading).
    private val lastValid=HashMap<Int,Long>()

    fun reset() {
        deadlines.clear()
        received.clear()
        lastValid.clear()
    }

    fun due(nowMs:Long,limit:Int=2):List<Int> {
        require(limit in 1..entries.size)
        val available=entries.filter { nowMs>= (deadlines[it.did] ?: 0L) }
            .sortedWith(compareByDescending<Entry> {
                val next=deadlines[it.did] ?: 0L
                // Newly requested items start with equally high priority;
                // list order keeps high-cadence safety signals first.
                if(next==0L) Long.MAX_VALUE else nowMs-next
            }.thenBy { entries.indexOf(it) })
            .take(limit)
        for(e in available)deadlines[e.did]=nowMs+e.intervalMs
        return available.map { it.did }
    }

    /**
     * A stationary DME benchmark MUST observe speed and coolant even if CORE
     * would normally budget zero SLOW reads. All requests remain serialized on
     * the existing polling owner, with at most TWO SLOW transactions per cycle.
     * Optional SLOW budget may be 0; it never cancels mandatory safety polling.
     *
     * Bounded scheduling is not a guarantee of ECU/network response time:
     * the suite separately aborts when either guard signal becomes stale.
     */
    fun dueForBenchmark(nowMs:Long,optionalBudget:Int):List<Int> {
        require(optionalBudget in 0..2)
        val guards=listOf(0x0D,0x05)
        val requested=ArrayList<Int>(2)
        for(did in guards) {
            val e=entries.first { it.did==did }
            if(nowMs >= (deadlines[did]?:0L)) {
                requested.add(did)
                deadlines[did]=nowMs+e.intervalMs
            }
        }
        // Only the remaining slots may be used by ordinary optional signals.
        val extra=entries.filter { it.did !in guards &&
            nowMs >= (deadlines[it.did]?:0L) }
            .sortedWith(compareByDescending<Entry> {
                val next=deadlines[it.did]?:0L
                if(next==0L)Long.MAX_VALUE else nowMs-next
            }.thenBy { entries.indexOf(it) })
            .take(minOf(optionalBudget,2-requested.size))
        for(e in extra) {
            requested.add(e.did)
            deadlines[e.did]=nowMs+e.intervalMs
        }
        return requested
    }

    fun record(did:Int,atMs:Long,valid:Boolean) {
        if(valid) {
            received[did]=atMs
            lastValid[did]=atMs
        } else received.remove(did)
    }

    /** Actual age since the last successful reply, INCLUDING stale/failed data.
     * Null means no successful reply in this stage/connection, not zero age.
     * Not a substitute for fresh(): a failed read is always invalid.
     */
    fun lastValidAgeMs(did:Int,nowMs:Long):Long? =
        lastValid[did]?.takeIf { it>0L && nowMs>=it }?.let{ nowMs-it }

    /** Age of the last valid read, or null if missing/stale. */
    fun ageMs(did:Int,nowMs:Long):Long? =
        received[did]?.takeIf { fresh(did,nowMs) }?.let { nowMs-it }

    fun fresh(did:Int,nowMs:Long):Boolean {
        val ttl=entries.firstOrNull { it.did==did }?.ttlMs ?: return false
        return SampleFreshness.isFresh(nowMs,received[did],ttl)
    }
}
