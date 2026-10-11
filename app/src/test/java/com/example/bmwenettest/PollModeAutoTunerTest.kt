package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class PollModeAutoTunerTest {
    private fun observe(
        tuner:PollModeAutoTuner,time:Long,
        batch:Boolean=true,knock:Int=4,ignition:Int=4,engine:Boolean=true
    ):PollModeAutoTuner.Transition?=tuner.observe(
        time,engine,1000L,knock,ignition,batch,"CLOSED",null
    )

    private fun trial(tuner:PollModeAutoTuner,start:Long,interval:Long=2000L,
                      count:Int=15,knock:Int=4):PollModeAutoTuner.Transition {
        // First stage starts on the first sample; finish() starts subsequent stages.
        if(start==1000L)observe(tuner,start)
        observe(tuner,start+5000L)
        var result:PollModeAutoTuner.Transition?=null
        for(i in 1..count) {
            val transition=observe(tuner,start+5000L+i*interval,knock=knock)
            if(transition!=null)result=transition
        }
        assertNotNull("A/B trial must finish",result)
        return result!!
    }

    @Test fun onlyAThenBAndNoSecondTcpMode() {
        val tuner=PollModeAutoTuner()
        assertEquals("A",tuner.currentMode)
        assertEquals(1,tuner.stageNumber())
        val a=trial(tuner,1000L)
        assertEquals("A",a.finished.mode)
        assertEquals("B",a.nextMode)
        assertEquals(15,a.finished.complete)
        assertEquals(100.0,a.finished.completePercent,0.0001)
        assertTrue(a.finished.usable)
        assertEquals(2,tuner.stageNumber())
        val b=trial(tuner,36000L)
        assertNull(b.nextMode)
        assertEquals("A",b.selectedMode) // equal frequency: conservative A
        assertEquals(listOf("A","B"),tuner.trials.map { it.mode })
    }

    @Test fun selectsBWhenAtLeastTwelvePercentFasterAndComplete() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        val b=trial(tuner,36000L,interval=1000L,count=30)
        assertEquals("B",b.selectedMode)
        assertEquals("B",tuner.chosenMode)
        assertTrue(b.finished.completeHz>0.8)
    }

    @Test fun batchRejectionFallsBackToAWithoutTestingCOrD() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        val b=observe(tuner,41000L,batch=false)
        assertNotNull(b)
        assertFalse(b!!.finished.usable)
        assertEquals("A",b.selectedMode)
        assertEquals(listOf("A","B"),tuner.trials.map { it.mode })
    }

    @Test fun incompleteFastSamplesNeverWin() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        val b=trial(tuner,36000L,knock=3)
        assertFalse(b.finished.usable)
        assertEquals(0,b.finished.complete)
        assertEquals("A",b.selectedMode)
    }

    @Test fun resetAfterReconnectDiscardsInterruptedBenchmark() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        tuner.restartAfterReconnect()
        assertEquals("A",tuner.currentMode)
        assertTrue(tuner.trials.isEmpty())
        assertNull(tuner.chosenMode)
    }

    @Test fun ignoresSecondaryStateBecauseAutoDoesNotUseSecondTcp() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        // A stale/failed secondary socket is irrelevant to single-TCP B.
        tuner.observe(41000L,true,1000L,4,4,true,"FAILED",999999L)
        val b=trial(tuner,36000L)
        assertNotNull(b.selectedMode)
        assertEquals(listOf("A","B"),tuner.trials.map { it.mode })
    }
}
