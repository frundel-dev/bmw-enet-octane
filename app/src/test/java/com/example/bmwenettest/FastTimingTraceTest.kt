package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class FastTimingTraceTest {
    private data class Sink(val tx:MutableList<String>,val signal:MutableList<String>,
                            val cycle:MutableList<String>,val trace:FastTimingTrace)
    private fun sink():Sink {
        val tx=mutableListOf<String>()
        val sig=mutableListOf<String>()
        val cycle=mutableListOf<String>()
        return Sink(tx,sig,cycle,FastTimingTrace(
            onTransactions={ tx.add(it); Unit },
            onSignals={ sig.add(it); Unit },
            onCycle={ cycle.add(it); Unit }))
    }
    private val ids=listOf(0x0C,0x04,0x0B,0x4A37,0x4A38,0x4A39,
        0x4A3A,0x4A49,0x4A4A,0x4A4C,0x4A4D)
    private fun valid(startMs:Long)=FastTimingTrace.Timing(
        requestStartNs=startMs*1_000_000,
        sendStartNs=(startMs+1)*1_000_000,
        sendEndNs=(startMs+2)*1_000_000,
        firstByteNs=(startMs+10)*1_000_000,
        responseCompleteNs=(startMs+20)*1_000_000,
        decodeEndNs=(startMs+21)*1_000_000,
        valid=true)
    private fun emit(s:Sink,id:Int,timeMs:Long)=s.trace.transaction(
        if(id<256)"OBD_%02X".format(id) else "DID_%04X".format(id),
        listOf(id),1792000000L,valid(timeMs))
    private fun sign(s:Sink):List<List<String>> =
        s.signal.flatMap { it.lines().filter(String::isNotBlank) }.map{it.split(",")}
    private fun cycle(s:Sink):List<String> =s.cycle.single().split(",")

    @Test fun fullFreshCycleWithMonotonicBoundaries() {
        val s=sink();s.trace.begin(11,2,3,4,"HIGH_SPEED_CORE","B+",1_000_000_000)
        ids.forEachIndexed { i,id -> emit(s,id,1_010L+i*7) }
        val result=s.trace.finish(1_150_000_000,ids.associateWith { true })
        assertTrue(result.complete)
        assertEquals(11,result.completeCount)
        assertTrue(result.steadyCandidate)
        assertTrue(result.dynamicCandidate)
        assertEquals(11,s.tx.single().lines().count{it.isNotBlank()})
        assertEquals(11,sign(s).size)
        assertTrue(sign(s).all{it[9]=="VALID" && it[16]=="false" && it[17]=="false"})
        assertEquals("3",cycle(s)[3])
        assertEquals("4",cycle(s)[4])
        assertEquals("11",cycle(s)[9])
        assertEquals("11",cycle(s)[10])
        assertEquals(FastTimingTrace.TX_HEADER.split(",").size,
            s.tx.single().lines().first().split(",").size)
        assertEquals(FastTimingTrace.SIGNAL_HEADER.split(",").size,sign(s).first().size)
        assertEquals(FastTimingTrace.CYCLE_HEADER.split(",").size,cycle(s).size)
    }

    @Test fun freshKnockCannotHideOldRpmMapOrLoad() {
        val s=sink();s.trace.begin(11,2,3,1,"HIGH_SPEED_CORE","B+",1_000_000_000)
        emit(s,0x0C,1010);emit(s,0x04,1025);emit(s,0x0B,1040)
        s.trace.finish(1_100_000_000,ids.take(3).associateWith{true})
        s.trace.begin(11,2,3,2,"HIGH_SPEED_CORE","B+",2_000_000_000)
        ids.drop(3).forEachIndexed{i,id->emit(s,id,2010L+i*4)}
        val out=s.trace.finish(2_100_000_000,ids.drop(3).associateWith{true})
        assertFalse(out.complete)
        val statuses=s.signal.last().lines().map{it.split(",")}.associate{it[7] to it[9]}
        assertEquals("STALE",statuses["RPM"])
        assertEquals("STALE",statuses["MAP"])
        assertEquals("STALE",statuses["LOAD"])
        assertEquals("VALID",statuses["KNOCK1"])
        assertTrue(s.signal.last().lines().all{ it.split(",")[16]=="false" })
    }

    @Test fun batchReplyDoesNotProveSimultaneousEcuAcquisition() {
        val s=sink();s.trace.begin(11,2,3,1,"HIGH_SPEED_EXTENDED","B+",1_000_000_000)
        s.trace.transaction("DID_BATCH_8",ids.drop(3),1792000000L,valid(1010))
        val out=s.trace.finish(1_050_000_000,ids.drop(3).associateWith{true})
        assertFalse(out.complete)
        assertEquals(8,out.completeCount)
        assertTrue(s.signal.single().lines().filter{it.isNotBlank()}.all {
            it.split(",")[17]=="false"
        })
        assertEquals(1,s.tx.size)
    }

    @Test fun longTimeSkewIsNotDynamicCandidateDespiteFullCoverage() {
        val s=sink();s.trace.begin(11,2,3,1,"LEGACY","A",1_000_000_000)
        ids.forEachIndexed{i,id->emit(s,id,1_010L+i*55)}
        val out=s.trace.finish(1_650_000_000,ids.associateWith{true})
        assertTrue(out.complete)
        assertTrue(out.steadyCandidate)
        assertFalse(out.dynamicCandidate)
        assertTrue(out.hostSkewMs!!>=500)
    }

    @Test fun truncatedOrInvalidDiagnosticDoesNotCount() {
        val s=sink();s.trace.begin(11,2,3,1,"LEGACY","A",1_000_000_000)
        val t=valid(1010).copy(valid=false,error="truncated")
        s.trace.transaction("DID_4A37",listOf(0x4A37),1792000000L,t)
        val out=s.trace.finish(1_050_000_000,mapOf(0x4A37 to false))
        assertFalse(out.complete)
        assertEquals(0,out.completeCount)
        assertEquals("INVALID",sign(s)[3][9])
        assertTrue(s.tx.single().contains("truncated"))
    }

    @Test fun reconnectSeparatesEpochAndAbortedCycle() {
        val s=sink();s.trace.begin(11,2,3,1,"HIGH_SPEED_CORE","B+",1_000_000_000)
        emit(s,0x0C,1010)
        assertTrue(s.trace.abort(1_060_000_000,"SocketException").aborted)
        s.trace.begin(11,2,4,2,"HIGH_SPEED_CORE","B+",2_000_000_000)
        val out=s.trace.finish(2_010_000_000,emptyMap())
        assertEquals(0,out.completeCount)
        assertEquals("MISSING",s.signal.last().lines().first().split(",")[9])
        assertEquals("4",s.cycle.last().split(",")[3])
        assertTrue(s.cycle.first().contains("SocketException"))
    }

    @Test fun timestampOrderAndEmptyQueueCallbacksDoNotThrow() {
        val s=sink();s.trace.begin(11,2,3,1,"LEGACY","A",1_000_000_000)
        emit(s,0x0C,1010);emit(s,0x04,1030)
        s.trace.finish(1_100_000_000,mapOf(0x0C to true,0x04 to true))
        val all=s.tx.single().lines().map{it.split(",")}
        assertEquals(listOf("1","2"),all.map{it[7]})
        assertEquals(listOf("0C","04"),all.map{it[9]})
        val disconnected=s.trace.abort(1_200_000_000,"already flushed")
        assertEquals(0,disconnected.completeCount)
        assertEquals(1,s.cycle.size)
    }

    @Test fun failureOfResearchQueueNeverSuppressesLiveData() {
        val t=FastTimingTrace(
            onTransactions={throw IllegalStateException("queue full")},
            onSignals={throw IllegalStateException("writer failed")},
            onCycle={throw IllegalStateException("disk full")})
        t.begin(11,1,0,1,"LEGACY","A",1_000_000_000)
        t.transaction("OBD_0C",listOf(0x0C),1792000000,valid(1010))
        val out=t.finish(1_050_000_000,mapOf(0x0C to true))
        assertEquals(1,out.completeCount)
    }
}
