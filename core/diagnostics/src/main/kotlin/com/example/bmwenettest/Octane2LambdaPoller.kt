package com.example.bmwenettest

/**
 * Owner-thread-only optional OBD lambda and fuel-control poller.
 *
 * The caller supplies the already owned, correlated OBD read function. This
 * class NEVER opens a socket, dispatches a parallel request or writes an ECU.
 * Probing, fallback and 4s/5s intervals are identical to the former inline
 * EnetLoggerService.loop() implementation.
 */
internal class Octane2LambdaPoller {
    data class Snapshot(
        val actual:Octane2Lambda.Sample?,
        val actualReceivedAtMs:Long,
        val fuelControlState:String?
    ) {
        fun freshActual(nowMs:Long):Octane2Lambda.Sample? =
            actual?.takeIf {
                actualReceivedAtMs>0L &&
                    SampleFreshness.isFresh(nowMs,actualReceivedAtMs,8000L)
            }
    }

    private var checked=false
    private var pid:Int?=null
    private var backupPid:Int?=null
    private var nextPollMs=0L
    private var fuelControlSupported=false
    private var controlState:String?=null
    private var nextControlPollMs=0L
    private var actual:Octane2Lambda.Sample?=null
    private var actualAtMs=0L

    /** Invalidate both support masks and samples when the TCP epoch changes. */
    fun reset() {
        checked=false
        pid=null
        backupPid=null
        nextPollMs=0L
        fuelControlSupported=false
        controlState=null
        nextControlPollMs=0L
        actual=null
        actualAtMs=0L
    }

    /**
     * Read order is: capability 0x20, capability 0x00 (once per TCP epoch),
     * optional sensor 0x34 or 0x24, then optional fuel status 0x03.
     * An unsuccessful 0x34 causes 0x24 fallback on a LATER cycle only.
     * Exceptions propagate to the existing Service reconnect handler.
     */
    fun poll(clockMs:()->Long,readObd:(Int)->ByteArray?):Snapshot {
        if(!checked) {
            checked=true
            val mask20=readObd(0x20)
            val sensor34=Octane2Lambda.supported(mask20,0x34)
            val sensor24=Octane2Lambda.supported(mask20,0x24)
            pid=when {
                sensor34 -> 0x34
                sensor24 -> 0x24
                else -> null
            }
            backupPid=if(sensor34 && sensor24)0x24 else null
            val mask00=readObd(0x00)
            fuelControlSupported=Octane2Lambda.supportedBase(mask00,0x03)
        }
        val now=clockMs()
        if(pid!=null && now>=nextPollMs) {
            val selected=pid!!
            actual=Octane2Lambda.decode(selected,readObd(selected))
            if(actual==null) {
                pid=backupPid
                backupPid=null
            }
            actualAtMs=if(actual!=null)clockMs() else 0L
            nextPollMs=clockMs()+4000L
        }
        if(fuelControlSupported && now>=nextControlPollMs) {
            controlState=Octane2Lambda.controlStatus(readObd(0x03))
            nextControlPollMs=clockMs()+5000L
        }
        return Snapshot(actual,actualAtMs,controlState)
    }
}
