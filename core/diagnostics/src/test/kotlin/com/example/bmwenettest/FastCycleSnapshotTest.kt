package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FastCycleSnapshotTest {
    @Test fun allElevenSignalsArePresentOnlyWhenActuallyDecoded() {
        val inputs=FastCycleSnapshot.IDS.associateWith { 1.0 }
        val snapshot=FastCycleSnapshot.create(4,2,3,10,100,500,inputs)
        assertEquals(11,snapshot.completeCount)
        assertTrue(snapshot.complete)
        assertTrue(snapshot.sample(0x0C)!!.present)
        assertEquals(2,snapshot.fuelSessionId)
        assertEquals(3,snapshot.connectionEpoch)
        assertEquals(10,snapshot.cycleId)
    }

    @Test fun unsupportedAndStaleValuesAreMissingNotFabricatedAsZero() {
        val prior=FastCycleSnapshot.create(4,2,3,10,100,500,
            mapOf(0x0C to 850.0,0x0B to 101.0))
        val next=FastCycleSnapshot.create(4,2,4,11,1000,1400,
            mapOf(0x04 to 40.0,0x4A37 to Double.NaN))
        assertEquals(2,prior.completeCount)
        assertEquals(1,next.completeCount)
        assertFalse(next.complete)
        assertNull(next.sample(0x0C)!!.value)
        assertNull(next.sample(0x4A37)!!.value)
        assertFalse(next.samples.any { it.present && it.did==0x0C })
        assertEquals(4,next.connectionEpoch)
    }

    @Test fun sourceMapMutationCannotChangeCapturedSample() {
        val source=mutableMapOf<Int,Double?>(0x0C to 800.0)
        val snap=FastCycleSnapshot.create(1,1,1,1,100,200,source)
        source[0x0C]=3000.0
        assertEquals(800.0,snap.sample(0x0C)!!.value!!,0.0)
        assertEquals(0.0,
            FastCycleSnapshot.create(1,1,1,2,200,300,
                mapOf(0x0C to 0.0)).sample(0x0C)!!.value!!,0.0)
    }
}
