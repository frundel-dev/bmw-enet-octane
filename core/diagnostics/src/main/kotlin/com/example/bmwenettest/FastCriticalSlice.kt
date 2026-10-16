package com.example.bmwenettest

/**
 * Exactly one FAST diagnostic slice, on the existing serialized socket owner.
 * All three OBD callbacks execute before the DME callback. The caller MUST
 * supply only the established read-only OBD 01 and FAST DME operations.
 *
 * No asynchronous work, additional ECU requests, byte-buffer retention,
 * or implicit retry. An exception propagates to the owner reconnect policy.
 */
object FastCriticalSlice {
    data class Obd(
        val rpm:Double?,
        val load:Double?,
        val map:Double?,
        val rpmStartedMs:Long,
        val rpmLatencyMs:Long,
        val obdDurationMs:Long,
        val obdCompleteNs:Long
    ) {
        val complete:Boolean get()=rpm!=null && load!=null && map!=null
    }
    data class Capture<T>(val obd:Obd,val dme:T)

    fun <T> collect(
        clockMs:()->Long,
        clockNs:()->Long={System.nanoTime()},
        readObd:(Int)->ByteArray?,
        afterObd:(Obd)->Unit,
        readFastDme:()->T
    ):Capture<T> {
        val start=clockMs()
        val rpmStarted=clockMs()
        val rpm=readObd(0x0C)?.takeIf{it.size==2}?.let {
            (((it[0].toInt() and 255)*256)+(it[1].toInt() and 255))/4.0
        }
        val rpmLatency=(clockMs()-rpmStarted).coerceAtLeast(0L)
        val load=readObd(0x04)?.firstOrNull()?.let{
            (it.toInt() and 255)*100.0/255.0
        }
        val map=readObd(0x0B)?.firstOrNull()?.let{
            (it.toInt() and 255).toDouble()
        }
        val obd=Obd(rpm,load,map,rpmStarted,rpmLatency,
            (clockMs()-start).coerceAtLeast(0L),clockNs())
        // The OBD no-response gate runs before any additional DME request.
        afterObd(obd)
        return Capture(obd,readFastDme())
    }
}
