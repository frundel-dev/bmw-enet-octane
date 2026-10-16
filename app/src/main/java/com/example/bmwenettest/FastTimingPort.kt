package com.example.bmwenettest

/** Host-side timing observation port. No MCU/ECU sample-clock claims. */
internal interface FastTimingPort {
    data class Timing(val requestStartNs:Long,val sendStartNs:Long,
        val sendEndNs:Long,val firstByteNs:Long?,
        val responseCompleteNs:Long?,val decodeEndNs:Long?,
        val valid:Boolean,val error:String="")
    data class Outcome(val complete:Boolean,val hostSkewMs:Long?)
    fun begin(trip:Long,fuel:Int,epoch:Int,cycle:Long,profile:String,
              mode:String,atNs:Long,transport:String)
    fun transaction(channel:String,dids:List<Int>,wall:Long,time:Timing)
    fun finish(atNs:Long,actual:Map<Int,Boolean>):Outcome
    fun abort(atNs:Long,reason:String)
}
