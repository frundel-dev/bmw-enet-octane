package com.example.bmwenettest

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class HighSpeedMetricsTest {
    @Test fun percentilesAndMissingFirstByteAreNeverFabricated() {
        val engine=HighSpeedMetrics(historySize=8)
        for(i in 1..8)engine.record("OBD_0C",i*1_000_000_000L,
            HighSpeedMetrics.Request(
                setupNs=1_000_000L,sendNs=2_000_000L,
                firstByteNs=i*1_000_000L,
                receiveRemainderNs=i*1_000_000L,
                decodeNs=3_000_000L,valid=true,fingerprint=i))
        val s=engine.snapshot().single()
        assertEquals(8L,s.requests)
        assertEquals(8L,s.successful)
        assertEquals(0L,s.errors)
        assertEquals(7L,s.responseChanges)
        assertEquals(4.0,s.firstByteMedianMs!!,0.001)
        assertEquals(8.0,s.firstByteP95Ms!!,0.001)
        assertEquals(8.0,s.firstByteP99Ms!!,0.001)
        assertEquals(1.0,s.successHz!!,0.001)
        engine.record("OBD_0C",9_000_000_000L,
            HighSpeedMetrics.Request(1,1,null,null,2,false,null))
        val after=engine.snapshot().single()
        assertEquals(9L,after.requests)
        assertEquals(8L,after.successful)
        assertEquals(1L,after.errors)
        assertEquals(8.0,after.firstByteP95Ms!!,0.001) // failed request cannot fake a latency
    }

    @Test fun unchangedResponseDoesNotMeanStaleEcu() {
        val stats=HighSpeedMetrics()
        stats.record("DID",100L,HighSpeedMetrics.Request(0,0,10,5,1,true,13))
        stats.record("DID",200L,HighSpeedMetrics.Request(0,0,10,5,1,true,13))
        val row=stats.snapshot().single()
        assertEquals(1L,row.responseUnchanged)
        assertEquals(0L,row.responseChanges)
        stats.reset()
        assertTrue(stats.snapshot().isEmpty())
    }

    @Test fun firstByteWrapperDoesNotConsumeAdditionalBytes() {
        val input=ByteArrayInputStream(byteArrayOf(3,4,5))
        val reported=mutableListOf<Long>()
        val wrapped=FirstByteMeasuredStream(input,{reported.add(it)},{44L})
        val chunk=ByteArray(2)
        assertEquals(2,wrapped.read(chunk,0,2))
        assertEquals(3,chunk[0].toInt())
        assertEquals(1,reported.size)
        assertEquals(44L,reported[0])
        assertEquals(5,wrapped.read())
        assertEquals(-1,wrapped.read())
        assertEquals(1,reported.size)
    }

    @Test fun percentileWindowIsBounded() {
        val stats=HighSpeedMetrics(4)
        for(i in 1..10)stats.record("RPM",i.toLong()*100,
            HighSpeedMetrics.Request(0,0,i.toLong()*1_000_000,0,0,true,i))
        assertEquals(8.0,stats.snapshot().single().firstByteMedianMs!!,0.001)
        assertEquals(10.0,stats.snapshot().single().firstByteMaxMs!!,0.001)
    }
}
