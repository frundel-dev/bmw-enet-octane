package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DriveObservationReplayTest {
    private fun s(t:Long,r:Double=2700.0,m:Double=114.0,l:Double=40.0,
                  session:Int=1,trip:Long=100L,epoch:Int=0,mode:String="B+") =
        DriveObservation(
            wallTimeMs=1791812345000L+t,elapsedMs=t,tripId=trip,
            fuelSessionId=session,connectionEpoch=epoch,
            event="FAST",transport="WIFI",pollMode=mode,
            rpm=r,mapKpa=m,loadPct=l,speedKmh=65.0,iatC=30.0,
            coolantC=91.0,knockVms=0.078,ignSpreadDeg=1.1,
            fastAgeMs=350L,speedAgeMs=450L,iatAgeMs=1500L,
            coolantAgeMs=3500L,stftAgeMs=null,ltftAgeMs=null
        )

    @Test fun canReplaySteadyAccelAndTransientWithoutFuelScore() {
        val data=listOf(
            s(1000L),
            s(2000L,r=2740.0,m=116.0), // steady: low gradients
            s(3000L,r=3200.0,m=170.0,l=72.0), // acceleration
            s(4000L,r=3600.0,m=182.0,l=76.0), // acceleration
            s(5000L,r=3610.0,m=112.0,l=80.0) // transient (out of steady LOAD)
        ).map(DriveObservationCsv::encode)
        val r=DriveObservationReplay.run(sequenceOf(
            "# schema=1",DriveObservationCsv.HEADER)+data.asSequence())
        assertEquals(5,r.samples)
        assertEquals(1,r.steady)
        assertEquals(2,r.accel)
        assertEquals(1,r.transient)
        assertEquals(1,r.insufficient)
        assertEquals(1,r.steadyTargetA)
        assertEquals(0,r.steadyTargetB)
        assertEquals(0,r.malformed)
        assertEquals(1,r.loggerStarts)
        assertEquals(1,r.sessions)
    }

    @Test fun reconnectModeChangeOrFuelChangeNeverStitchesTwoSamples() {
        val disconnected=s(2900L).copy(event="DISCONNECTED",connectionEpoch=1)
        val rows=listOf(
            s(1000L),
            s(2000L), // steady
            disconnected,
            s(3000L,epoch=1), // first after reconnect
            s(3500L,epoch=1,mode="A"), // mode changed
            s(3800L,session=2,epoch=1,mode="A"), // different refuel
            s(3900L,session=2,epoch=1,mode="A",trip=101L) // distinct logger
        )
        val r=DriveObservationReplay.run(rows.map(DriveObservationCsv::encode).asSequence())
        assertEquals(6,r.samples)
        assertEquals(1,r.steady)
        assertEquals(5,r.insufficient)
        assertEquals(1,r.reconnects)
        assertEquals(2,r.sessions)
        assertEquals(2,r.loggerStarts)
    }

    @Test fun staleSlowValueAndMalformedRowsCannotBecomeEvidence() {
        val broken=s(2000L).copy(speedAgeMs=7000L)
        val rows=sequenceOf(
            DriveObservationCsv.encode(s(1000L)),
            DriveObservationCsv.encode(broken),
            "invalid,partial",
            DriveObservationCsv.encode(s(3000L)),
            DriveObservationCsv.encode(s(9000L)) // gap >5s
        )
        val r=DriveObservationReplay.run(rows)
        assertEquals(4,r.samples)
        assertEquals(1,r.malformed)
        assertEquals(0,r.steady)
        assertEquals(4,r.insufficient)
    }

    @Test fun stalePredecessorCannotDefineReliableRpmOrMapGradient() {
        val prior=s(1000L).copy(speedAgeMs=7000L)
        val current=s(1500L)
        assertEquals(DriveObservationReplay.Phase.INSUFFICIENT,
            DriveObservationReplay.classify(current,prior))
    }

    @Test fun delayedFastCycleAndUnknownSpeedAreNotSteady() {
        assertEquals(DriveObservationReplay.Phase.INSUFFICIENT,
            DriveObservationReplay.classify(s(1500L).copy(fastAgeMs=3000L),s(1000L)))
        assertEquals(DriveObservationReplay.Phase.INSUFFICIENT,
            DriveObservationReplay.classify(s(1500L).copy(speedKmh=null),s(1000L)))
        assertEquals(DriveObservationReplay.Phase.INSUFFICIENT,
            DriveObservationReplay.classify(s(1500L).copy(iatC=null),s(1000L)))
    }
}
