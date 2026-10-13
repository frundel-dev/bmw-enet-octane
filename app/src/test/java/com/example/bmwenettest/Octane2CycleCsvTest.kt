package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class Octane2CycleCsvTest {
    private fun cycle()=Octane2Cycle(
        wallTimeMs=1791900001000L,elapsedMs=1000L,
        tripId=1791900000000L,fuelSession=1,epoch=0,pollMode="B+",
        obdRpm=2700.0,ramRpm=2698.0,effectiveRpm=2698.0,
        rpmSource="RAM_NKW",ramState="ACTIVE",
        obdLatencyMs=80L,ramLatencyMs=60L,ramAfterObdMs=150L,
        cycleMs=380L,gapMs=25L,mapKpa=115.0,loadPct=43.0,
        speed=75.0,iat=29.0,coolant=95.0,
        knockMean=0.085,ignitionSpread=2.1,
        actualLambda=null,lambdaPid=null,lambdaAgeMs=null,
        lambdaAux=null,commandedEq=1.0,commandedAgeMs=1500L,
        stft=1.2,ltft=-2.1,controlState="CLOSED_LOOP",
        steadyIndex=0,steadyHoldMs=5000L,
        legacySessionScore=null,legacyConfidence=0
    )

    @Test fun exactSchemaPreservesMissingActualLambdaAndPairedRpm() {
        val encoded=Octane2CycleCsv.encode(cycle())
        val row=encoded.split(',')
        assertEquals(Octane2CycleCsv.HEADER.split(',').size,row.size)
        assertEquals(36,row.size)
        assertEquals("2700.0",row[6])
        assertEquals("2698.0",row[7])
        assertEquals("",row[23]) // unavailable actual sensor never becomes guessed 1.0
        assertEquals("1.0",row[27]) // commanded PID 44 stored distinctly
        assertEquals("",row[34]) // no validated score
    }

    @Test fun countsOnlyCompleteRows() {
        val valid=Octane2CycleCsv.encode(cycle())
        assertEquals(1,Octane2CycleCsv.acceptedRows(
            sequenceOf("# observational_only",Octane2CycleCsv.HEADER,valid,"broken,partial")))
    }
}
