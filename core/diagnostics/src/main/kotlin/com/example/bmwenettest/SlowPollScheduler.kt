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

    fun reset() {
        deadlines.clear()
        received.clear()
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

    fun record(did:Int,atMs:Long,valid:Boolean) {
        if(valid)received[did]=atMs else received.remove(did)
    }

    /** Age of the last valid read, or null if missing/stale. */
    fun ageMs(did:Int,nowMs:Long):Long? =
        received[did]?.takeIf { fresh(did,nowMs) }?.let { nowMs-it }

    fun fresh(did:Int,nowMs:Long):Boolean {
        val ttl=entries.firstOrNull { it.did==did }?.ttlMs ?: return false
        return SampleFreshness.isFresh(nowMs,received[did],ttl)
    }
}
