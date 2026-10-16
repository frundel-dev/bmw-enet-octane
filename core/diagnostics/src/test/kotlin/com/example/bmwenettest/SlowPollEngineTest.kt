package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class SlowPollEngineTest {
    @Test fun coreZeroBudgetStillSeriallyPollsGuardSignalsInLab() {
        val scheduler=SlowPollScheduler()
        val requested=mutableListOf<Int>()
        var time=1_000L
        val r=SlowPollEngine.poll(
            mode="B+",profile=HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,
            cycle=4L,previousCycleMs=250L,hadFailures=false,
            benchmark=true,scheduler=scheduler,nowMs=time,
            previous=SlowPollEngine.Values(),lastSlowMs=0L,
            sensorReceivedAtMs=0L,clockMs={time},
            readObd={id ->
                requested.add(id)
                time+=75L
                when(id){0x0D->byteArrayOf(0);0x05->byteArrayOf(130.toByte())
                    else->byteArrayOf(100)}
            },
            readUds={id->requested.add(id);byteArrayOf(0)})
        assertEquals(listOf(0x0D,0x05),requested)
        assertEquals(2,r.slowRequests)
        assertEquals(0.0,r.values.speed!!,0.0)
        assertEquals(90.0,r.values.coolant!!,0.0)
        assertTrue(scheduler.fresh(0x0D,time))
        assertTrue(scheduler.fresh(0x05,time))
    }

    @Test fun legacySlowOrderAndConversionsAreUnchanged() {
        val ids=mutableListOf<Int>()
        var time=5000L
        val r=SlowPollEngine.poll(
            mode="A",profile=HighSpeedProfilePolicy.Profile.LEGACY,
            cycle=1L,previousCycleMs=0L,hadFailures=false,benchmark=false,
            scheduler=SlowPollScheduler(),nowMs=time,
            previous=SlowPollEngine.Values(),lastSlowMs=1000L,
            sensorReceivedAtMs=0L,clockMs={time},
            readObd={id ->
                ids.add(id)
                when(id) {
                    0x0D -> byteArrayOf(0)
                    0x0F -> byteArrayOf(70)
                    0x0E -> byteArrayOf(128.toByte())
                    0x05 -> byteArrayOf(120.toByte())
                    0x11 -> byteArrayOf(255.toByte())
                    0x06,0x07 -> byteArrayOf(128.toByte())
                    0x44 -> byteArrayOf(0,0)
                    0x5C -> byteArrayOf(100)
                    else -> null
                }
            },
            readUds={id->ids.add(id);byteArrayOf(0)})
        assertEquals(listOf(0x0D,0x0F,0x0E,0x05,0x11,0x06,0x07,
            0x44,0x5C,0x5728),ids)
        assertEquals(0,r.slowRequests) // Original A-path only counts scheduled B+ reads.
        assertEquals(30.0,r.values.iat!!,0.0)
        assertEquals(80.0,r.values.coolant!!,0.0)
        assertEquals(0.0,r.values.stft!!,0.0)
        assertEquals(0.0,r.values.lambda!!,0.0)
        assertEquals(100.0,r.values.throttle!!,0.0)
        assertEquals(5000L,r.lastSlowMs)
        assertEquals(5000L,r.sensorReceivedAtMs)
        assertFalse(r.optimized)
    }

    @Test fun noSlowPollDuePreservesPreviousValues() {
        val prev=SlowPollEngine.Values(speed=12.0,coolant=84.0,iat=40.0)
        val r=SlowPollEngine.poll(
            mode="A",profile=HighSpeedProfilePolicy.Profile.LEGACY,
            cycle=1L,previousCycleMs=0L,hadFailures=false,
            benchmark=false,scheduler=SlowPollScheduler(),nowMs=1_999L,
            previous=prev,lastSlowMs=1000L,sensorReceivedAtMs=1000L,
            clockMs={1_999L},readObd={error("must not query")},
            readUds={error("must not query")})
        assertEquals(prev,r.values)
        assertEquals(1000L,r.lastSlowMs)
        assertEquals(1000L,r.sensorReceivedAtMs)
    }

    @Test fun invalidSafetyReplyReturnsNullRatherThanCachedZero() {
        val scheduler=SlowPollScheduler()
        val r=SlowPollEngine.poll(
            mode="B+",profile=HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,
            cycle=4L,previousCycleMs=250L,hadFailures=false,benchmark=true,
            scheduler=scheduler,nowMs=10_000L,
            previous=SlowPollEngine.Values(speed=0.0,coolant=90.0),
            lastSlowMs=0L,sensorReceivedAtMs=0L,
            clockMs={10_000L},readObd={_ ->null},readUds={null})
        assertNull(r.values.speed)
        assertNull(r.values.coolant)
        assertFalse(scheduler.fresh(0x0D,10_000L))
        assertFalse(scheduler.fresh(0x05,10_000L))
    }
}
