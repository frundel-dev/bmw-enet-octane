package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FastPhaseTimingTest {
    @Test fun phasesUseRealMonotonicBoundariesAndDoNotInventMissingMonitor() {
        val row=FastPhaseTiming.Sample(
            wallMs=1700000L,tripId=123L,fuelSessionId=8,epoch=2,
            cycleId=14,profile="HIGH_SPEED_CORE",mode="B+",
            transport="VXSCAN",fastStartNs=1_000_000_000L,
            obdCompleteNs=1_060_000_000L,fastEndNs=1_320_000_000L,
            slowStartNs=1_320_000_001L,slowEndNs=1_500_000_000L,
            firstPublishNs=1_650_000_000L,monitorStartNs=null,
            monitorEndNs=null,cycleEndNs=1_660_000_000L,
            previousCycleEndNs=800_000_000L,fullFast=true)
        val header=FastPhaseTiming.HEADER.split(",")
        val values=FastPhaseTiming.encode(row).split(",")
        assertEquals(header.size,values.size)
        val data=header.zip(values).toMap()
        assertEquals("320.000",data["fast_ms"])
        assertEquals("60.000",data["obd_ms"])
        assertEquals("180.000",data["slow_ms"])
        assertEquals("330.000",data["post_fast_publish_ms"])
        assertEquals("",data["monitor_ms"])
        assertEquals("",data["monitor_start_ns"])
        assertEquals("200.000",data["gap_before_ms"])
        assertEquals("true",data["full_fast_11"])
        assertEquals("false",data["ecu_sample_time_known"])
    }

    @Test fun anInvalidChronologyIsBlankRatherThanFabricatedNegativeDuration() {
        val row=FastPhaseTiming.Sample(1,1,1,1,1,
            "CORE","B","ENET",100L,90L,80L,100L,80L,
            110L,200L,190L,120L,null,false)
        val values=FastPhaseTiming.encode(row).split(",")
        val data=FastPhaseTiming.HEADER.split(",").zip(values).toMap()
        assertEquals("",data["fast_ms"])
        assertEquals("",data["obd_ms"])
        assertEquals("",data["slow_ms"])
        assertEquals("",data["monitor_ms"])
        assertEquals("",data["gap_before_ms"])
        assertEquals("false",data["full_fast_11"])
    }
}
