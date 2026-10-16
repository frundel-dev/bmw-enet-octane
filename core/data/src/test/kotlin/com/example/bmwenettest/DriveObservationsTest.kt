package com.example.bmwenettest

import java.io.IOException
import java.io.StringWriter
import org.junit.Assert.*
import org.junit.Test

class DriveObservationsTest {
    private fun sample()=DriveObservation(
        wallTimeMs=1791812345678L,elapsedMs=5500L,tripId=1791812000000L,
        fuelSessionId=2,connectionEpoch=3,event="FAST",transport="WIFI",
        pollMode="B+",rpm=3221.25,mapKpa=115.7,loadPct=46.0,
        speedKmh=75.0,iatC=33.0,coolantC=89.0,
        stftPct=-2.1,ltftPct=0.5,knockVms=0.082,
        ignSpreadDeg=2.2,fastAgeMs=401L,speedAgeMs=800L,
        iatAgeMs=1000L,coolantAgeMs=2000L,stftAgeMs=1550L,
        ltftAgeMs=5500L
    )

    @Test fun fullRoundtripRetainsExactProvenanceAndIndependentCsvSchema() {
        val original=sample()
        val line=DriveObservationCsv.encode(original)
        assertEquals(24,line.split(',').size)
        assertEquals(original,DriveObservationCsv.decode(line))
        assertNull(DriveObservationCsv.decode(DriveObservationCsv.HEADER))
        assertNull(DriveObservationCsv.decode("# observational_only=true"))
        assertEquals("bmw_observations_v1_123.csv",DriveObservationCsv.filename(123L))
    }

    @Test fun missingAndNonFiniteValuesStayMissingNotZero() {
        val s=sample().copy(rpm=Double.NaN,iatC=null,
            knockVms=Double.POSITIVE_INFINITY,iatAgeMs=null)
        val decoded=DriveObservationCsv.decode(DriveObservationCsv.encode(s))!!
        assertNull(decoded.rpm)
        assertNull(decoded.iatC)
        assertNull(decoded.knockVms)
        assertNull(decoded.iatAgeMs)
        assertEquals(s.fuelSessionId,decoded.fuelSessionId)
    }

    @Test fun corruptRecordsFailClosedAndDoNotMasqueradeAsValidMeasurements() {
        val good=DriveObservationCsv.encode(sample())
        assertNull(DriveObservationCsv.decode(good.replace("3221.25","NaN")))
        assertNull(DriveObservationCsv.decode(good.replace("5500","oops")))
        assertNull(DriveObservationCsv.decode(good.replace("401","-1")))
        assertNull(DriveObservationCsv.decode(good.substringBeforeLast(',')))
        assertNull(DriveObservationCsv.decode(good.replace("FAST,WIFI","FAST,,")))
    }

    @Test fun journalWritesOneRecordPerAppendAndClosesBufferedStream() {
        val buffer=StringWriter()
        val journal=DriveObservationJournal(buffer,100000L)
        assertTrue(journal.append(sample()))
        assertTrue(journal.append(sample().copy(elapsedMs=5900L)))
        journal.close()
        val lines=buffer.toString().trimEnd().lines()
        assertEquals(3,lines.size)
        assertEquals(DriveObservationCsv.HEADER,lines[0])
        assertEquals(5500L,DriveObservationCsv.decode(lines[1])!!.elapsedMs)
        assertEquals(5900L,DriveObservationCsv.decode(lines[2])!!.elapsedMs)
        assertFalse(journal.failed)
    }

    @Test fun writerFailureStopsJournalWithoutThrowingIntoPrimaryLogger() {
        val broken=object:StringWriter() {
            override fun flush() { throw IOException("device full") }
        }
        val journal=DriveObservationJournal(broken,0L)
        assertFalse(journal.append(sample()))
        assertTrue(journal.failed)
        assertFalse(journal.append(sample().copy(elapsedMs=8000L)))
        journal.close()
        assertTrue(journal.failed)
    }
}
