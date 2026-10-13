package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class HighSpeedBenchmarkTest {
    private fun summary()=HighSpeedMetrics.Summary(
        "OBD_0C",10,9,1,3,5,1.0,2.0,3.0,4.0,
        1.0,4.0,2.0,6.0,0.2,2.0,1.8)
    @Test fun versionedRowsRoundTripWithoutDiscardingEpochOrFuelBoundary() {
        val writer=AsyncCsvWriter.QueueSnapshot(3,100,98,2,1,1.2,5.0,7)
        val record=HighSpeedBenchmark.Sample(
            3000,222,6,3,"HIGH_SPEED_CORE","B+",summary(),writer,45,18.5,4,4)
        val encoded=HighSpeedBenchmark.encode(record)
        assertEquals(HighSpeedBenchmark.HEADER.split(",").size,encoded.split(",").size)
        assertTrue(HighSpeedBenchmark.validRow(encoded))
        val result=HighSpeedBenchmark.analyze(sequenceOf(
            "# comment",HighSpeedBenchmark.HEADER,encoded,encoded.replace(",6,3,",",7,4,"),
            "bad,row"))
        assertEquals(3,result.rows)
        assertEquals(2,result.validRows)
        assertEquals(setOf(6,7),result.sessions)
        assertEquals(setOf("HIGH_SPEED_CORE"),result.modes)
        assertEquals(1,result.incompleteRows)
    }
    @Test fun neverAcceptMalformedOrNonConservedResponseCounts() {
        val valid=HighSpeedBenchmark.encode(HighSpeedBenchmark.Sample(
            4000,22,1,0,"LEGACY","A",summary(),
            AsyncCsvWriter.QueueSnapshot(0,0,0,0,0,null,null,0),100,8.0,4,1))
        assertTrue(HighSpeedBenchmark.validRow(valid))
        val tokens=valid.split(",").toMutableList()
        tokens[7]="5" // requests=5 but successful=9/errors=1
        assertFalse(HighSpeedBenchmark.validRow(tokens.joinToString(",")))
        tokens[7]="10"
        tokens[4]="UNSUPPORTED"
        assertFalse(HighSpeedBenchmark.validRow(tokens.joinToString(",")))
    }
    @Test fun priorityProfilesNeverIncreaseOptionalEcuTransactions() {
        val policy=HighSpeedProfilePolicy
        assertEquals(HighSpeedProfilePolicy.Profile.LEGACY,policy.resolve("BAD"))
        assertEquals(2,policy.optionalBudget(policy.resolve(null),5,250,false))
        assertEquals(1,policy.optionalBudget(HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,2,75,false))
        assertEquals(0,policy.optionalBudget(HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,3,125,false))
        assertEquals(0,policy.optionalBudget(HighSpeedProfilePolicy.Profile.HIGH_SPEED_CORE,3,50,true))
        assertEquals(2,policy.optionalBudget(HighSpeedProfilePolicy.Profile.HIGH_SPEED_EXTENDED,6,45,false))
    }
}
