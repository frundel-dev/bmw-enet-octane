package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class Octane2UiStateTest {
    private fun arr(points:Int,segments:Int,starts:Int)=intArrayOf(
        points,segments,starts,1,99 // malicious/old UI percentage ignored
    )

    @Test fun onlyVerifiedDiskCountsAdvanceCoverage() {
        val empty=Octane2UiState.target(null)
        assertEquals(0,empty.progress)
        assertEquals(Octane2UiState.Stage.NOT_STARTED,empty.stage)
        val partial=Octane2UiState.target(arr(24,1,1))
        assertTrue(partial.progress<100)
        assertEquals(Octane2UiState.Stage.COLLECTING,partial.stage)
        val ready=Octane2UiState.target(arr(24,4,2))
        assertEquals(100,ready.progress)
        assertEquals(Octane2UiState.Stage.SUFFICIENT,ready.stage)
        assertFalse(Octane2UiState.target(intArrayOf(100)).sufficient)
    }

    @Test fun nextModeTracksLeastCoveredWithoutTimeBasedProgress() {
        val empty=Octane2UiState.target(null)
        val half=Octane2UiState.target(arr(12,2,1))
        val enough=Octane2UiState.target(arr(24,4,2))
        assertEquals(0,Octane2UiState.guidance(empty,half,true,false).nextTarget)
        assertEquals(1,Octane2UiState.guidance(enough,half,true,false).nextTarget)
        val finished=Octane2UiState.guidance(enough,enough,true,false)
        assertTrue(finished.allCovered)
        assertEquals(-1,finished.nextTarget)
    }

    @Test fun connectionAndCaptureStatesDoNotClaimAReadingOnDisconnect() {
        val empty=Octane2UiState.target(null)
        assertEquals("Ошибка подключения",
            Octane2UiState.stateText(true,Octane2UiState.Connection.ERROR,
                true,empty,empty,false))
        assertEquals("Ожидание DME",
            Octane2UiState.stateText(true,Octane2UiState.Connection.DISCONNECTED,
                true,empty,empty,false))
        assertEquals("Сбор приостановлен",
            Octane2UiState.stateText(true,Octane2UiState.Connection.CONNECTED,
                true,empty,empty,true))
        val full=Octane2UiState.target(arr(24,4,2))
        assertTrue(Octane2UiState.stateText(true,Octane2UiState.Connection.CONNECTED,
            true,full,full,false).startsWith("Данные собраны"))
    }

    @Test fun olderRefuelNeverIncreasesNewRefuelProgress() {
        fun rec(wall:Long,session:Int)=SteadyTargets.Record(
            wall,wall,session,1,2650.0,115.0,40.0,28.0,90.0,0.083)
        val old=rec(1791900001000L,1)
        val fresh=rec(1791901002000L,1)
        val different=rec(1791902000000L,2)
        val records=listOf(old,fresh,different)
        val scoped=Octane2UiState.recordsForCurrentRefuel(
            records,1,1791901000000L,1)
        assertEquals(listOf(fresh),scoped)
        assertEquals(1,SteadyTargets.Tracker(1,scoped).snapshot().first.points)
        assertEquals(2,Octane2UiState.recordsForCurrentRefuel(
            records,1,0L,-1).size)
    }
}
