package com.example.bmwenettest

import java.util.Collections

/**
 * Immutable host-side logical FAST set. All eleven values are decoded scalars;
 * it deliberately retains NO mutable transport buffers.
 *
 * Request/response/host-age provenance remains in the matching LAB
 * FastTimingTrace keyed by (trip, fuel, connection epoch, cycle, DID).
 * This is never a timestamp of ECU-internal acquisition.
 */
class FastCycleSnapshot private constructor(
    val tripId:Long,
    val fuelSessionId:Int,
    val connectionEpoch:Int,
    val cycleId:Long,
    val fastBeginNs:Long,
    val fastEndNs:Long,
    val samples:List<Sample>
) {
    data class Sample(val did:Int,val value:Double?) {
        val present:Boolean get()=value!=null && value.isFinite()
    }
    val completeCount:Int get()=samples.count{it.present}
    val complete:Boolean get()=completeCount==IDS.size
    fun sample(did:Int):Sample?=samples.firstOrNull{it.did==did}

    companion object {
        val IDS=listOf(0x0C,0x04,0x0B,0x4A37,0x4A38,0x4A39,0x4A3A,
            0x4A49,0x4A4A,0x4A4C,0x4A4D)
        fun create(tripId:Long,fuelSessionId:Int,connectionEpoch:Int,
                   cycleId:Long,fastBeginNs:Long,fastEndNs:Long,
                   values:Map<Int,Double?>):FastCycleSnapshot {
            require(fastBeginNs>0L && fastEndNs>=fastBeginNs)
            val copied=IDS.map { id->
                val v=values[id]
                Sample(id,v?.takeIf{it.isFinite()})
            }
            return FastCycleSnapshot(tripId,fuelSessionId,connectionEpoch,
                cycleId,fastBeginNs,fastEndNs,
                Collections.unmodifiableList(copied))
        }
    }
}
