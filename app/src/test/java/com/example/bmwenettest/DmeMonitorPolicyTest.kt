package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DmeMonitorPolicyTest {
    private fun choose(p:DmeMonitorPolicy,time:Long,cycle:Long=400,
                       complete:Boolean=true,rpm:Double=1800.0,
                       load:Double=35.0,map:Double=110.0,speed:Double?=40.0)=
        p.choose(time,complete,cycle,rpm,load,map,speed)

    @Test fun startsOnlyAfterFirstFastWindowAndStaggersDids() {
        val p=DmeMonitorPolicy()
        p.reset(1000)
        assertNull(choose(p,12999))
        val first=choose(p,13000)
        assertEquals("554A",first?.label)
        p.issued(first!!,13000)
        assertNull(choose(p,13100))
        val second=choose(p,26000)
        assertEquals("4A85",second?.label)
        p.issued(second!!,26000)
        // Eight-second minimum spacing takes precedence over due times.
        assertNull(choose(p,28000))
        val refreshed=choose(p,34000)
        assertEquals("554A",refreshed?.label)
        p.issued(refreshed!!,34000)
        assertEquals("4A9D",choose(p,42000)?.label)
    }

    @Test fun skipsFastIncompleteSlowCyclesAndHeavyLoad() {
        val p=DmeMonitorPolicy()
        p.reset(0)
        assertNull(choose(p,12000,complete=false))
        assertNull(choose(p,12000,cycle=651))
        assertNull(choose(p,12000,load=65.0))
        assertNull(choose(p,12000,map=160.0))
        assertNull(choose(p,12000,rpm=4001.0))
        assertEquals("554A",choose(p,12000)?.label)
    }

    @Test fun rejectsLongReplyRetriesAndSuspendsAfterTransportError() {
        val p=DmeMonitorPolicy()
        p.reset(0)
        val task=choose(p,12000)!!
        p.issued(task,12000)
        assertNull(choose(p,12001))
        p.unsupported(task)
        assertFalse(choose(p,35000)?.label=="554A")
        p.suspend()
        assertNull(choose(p,900000))
        assertTrue(p.suspended)
    }

    @Test fun idleOnlyDtcMonitorNeverRunsWhileDriving() {
        val p=DmeMonitorPolicy()
        p.reset(0)
        // Make all adaptation DIDs unsupported so DTC status read is next.
        for(t in DmeMonitorPolicy.tasks.filter { it.service==0x22 })p.unsupported(t)
        assertNull(choose(p,480000,speed=20.0))
        assertNull(choose(p,480000,speed=null))
        assertNull(choose(p,480000,rpm=1500.0,speed=0.0))
        val dtc=choose(p,480000,rpm=900.0,speed=0.0)
        assertEquals("DTC_CONFIRMED",dtc?.label)
        assertArrayEquals(byteArrayOf(0x19,0x02,0x08),dtc!!.request())
    }

    @Test fun onlyReadOnlyServicesAndPerDidCadence() {
        assertTrue(DmeMonitorPolicy.tasks.all { it.service==0x22 || it.service==0x19 })
        val p=DmeMonitorPolicy()
        p.reset(0)
        val task=choose(p,12000)!!
        assertArrayEquals(byteArrayOf(0x22,0x55,0x4A),task.request())
        p.issued(task,12000)
        assertEquals(15000L,task.intervalMs)
        // At 27000 the first DID becomes due again; other adaptations
        // might take priority because their scheduled start is earlier.
        val next=choose(p,27000)
        assertEquals("4A85",next?.label)
    }
}
