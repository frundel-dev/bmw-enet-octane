package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class Octane2HeaderStatusTest {
    private val C=Octane2UiState.Connection

    @Test fun stoppedLoggerCannotDisplayStaleConnectedDme() {
        val labels=Octane2HeaderStatus.labels(false,C.CONNECTED)
        assertEquals("● DME: нет связи",labels.dme)
        assertEquals("LOGGER: остановлен",labels.logger)
        assertEquals(C.DISCONNECTED,labels.effectiveConnection)
    }

    @Test fun connectedLoggerDisplaysLiveCapture() {
        val labels=Octane2HeaderStatus.labels(true,C.CONNECTED)
        assertEquals("● DME: подключено",labels.dme)
        assertEquals("LOGGER: сбор",labels.logger)
    }

    @Test fun connectionTransitionIsVisibleWithoutOverstatingCapture() {
        val waiting=Octane2HeaderStatus.labels(true,C.CONNECTING)
        assertEquals("● DME: подключение",waiting.dme)
        assertEquals("LOGGER: ожидание",waiting.logger)
        val retry=Octane2HeaderStatus.labels(true,C.RECONNECTING)
        assertEquals("● DME: переподключение",retry.dme)
        assertEquals("LOGGER: восстановление",retry.logger)
    }

    @Test fun diagnosticFailureGetsDistinctState() {
        val failed=Octane2HeaderStatus.labels(true,C.ERROR)
        assertEquals("● DME: ошибка",failed.dme)
        assertEquals("LOGGER: ошибка",failed.logger)
    }

    @Test fun activeLoggerWithoutSocketIsNotShownAsConnected() {
        val disconnected=Octane2HeaderStatus.labels(true,C.DISCONNECTED)
        assertEquals("● DME: нет связи",disconnected.dme)
        assertEquals("LOGGER: ожидание",disconnected.logger)
    }
}
