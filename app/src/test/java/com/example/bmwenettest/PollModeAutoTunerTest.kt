package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class PollModeAutoTunerTest {
    private fun observe(
        tuner:PollModeAutoTuner,time:Long,mode:String=tuner.currentMode,
        batch:Boolean=true,second:String="ACTIVE",secondAge:Long?=1000L,
        knock:Int=4,ignition:Int=4,engine:Boolean=true
    ):PollModeAutoTuner.Transition?=tuner.observe(
        time,engine,1000L,knock,ignition,batch,
        if(mode=="A" || mode=="B")"CLOSED" else second,
        if(mode=="A" || mode=="B")null else secondAge
    )

    private fun trial(tuner:PollModeAutoTuner,start:Long,interval:Long=2000L,
                      count:Int=15,knock:Int=4):PollModeAutoTuner.Transition {
        val mode=tuner.currentMode
        // First observation starts settle timer; second starts measured window.
        if(start==1000L)observe(tuner,start,mode)
        observe(tuner,start+5000L,mode)
        var result:PollModeAutoTuner.Transition?=null
        for(i in 1..count) {
            val transition=observe(tuner,start+5000L+i*interval,mode,knock=knock)
            if(transition!=null)result=transition
        }
        assertNotNull("stage must complete",result)
        return result!!
    }

    @Test fun advancesWithoutRequiringTcpReconnect() {
        val tuner=PollModeAutoTuner()
        val a=trial(tuner,1000L)
        assertEquals("A",a.finished.mode)
        assertEquals("B",a.nextMode)
        assertEquals(15,a.finished.complete)
        assertEquals(100.0,a.finished.completePercent,0.0001)
        assertTrue(a.finished.usable)
        val b=trial(tuner,36000L)
        assertEquals("C",b.nextMode)
        val c=trial(tuner,71000L)
        assertEquals("D",c.nextMode)
        val d=trial(tuner,106000L)
        assertEquals("A",d.selectedMode) // equal speeds: prefer proven serial
        assertEquals("A",tuner.chosenMode)
    }

    @Test fun selectsMateriallyFasterCompleteMode() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        trial(tuner,36000L)
        trial(tuner,71000L)
        val best=trial(tuner,106000L,interval=1000L,count=30)
        assertEquals("D",best.selectedMode)
        assertEquals("D",tuner.currentMode)
        assertTrue(best.finished.completeHz>0.8)
    }

    @Test fun rejectsUnsupportedBatchAndSkipsDependentModeD() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        val b=observe(tuner,41000L,mode="B",batch=false)
        assertNotNull(b)
        assertFalse(b!!.finished.usable)
        assertEquals("C",b.nextMode)
        val c=trial(tuner,41000L)
        assertEquals("A",c.selectedMode)
        assertEquals(listOf("A","B","C"),tuner.trials.map { it.mode })
    }

    @Test fun secondTcpFailureRemovesCAndD() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        trial(tuner,36000L)
        val c=observe(tuner,76000L,mode="C",second="FAILED")
        assertNotNull(c)
        assertEquals("A",c!!.selectedMode)
        assertEquals(listOf("A","B","C"),tuner.trials.map { it.mode })
    }

    @Test fun incompleteFastSamplesNeverWin() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        val bad=trial(tuner,36000L,knock=3)
        assertFalse(bad.finished.usable)
        assertEquals(0,bad.finished.complete)
        assertEquals("C",bad.nextMode)
    }

    @Test fun resetAfterReconnectDiscardsInterruptedBenchmark() {
        val tuner=PollModeAutoTuner()
        trial(tuner,1000L)
        tuner.restartAfterReconnect()
        assertEquals("A",tuner.currentMode)
        assertTrue(tuner.trials.isEmpty())
        assertNull(tuner.chosenMode)
    }
}
