package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class VxscanHeaderStatusTest {
    private val Phase=VxscanLocalOnlyWifi.Phase
    private val Tone=VxscanHeaderStatus.Tone

    @Test fun readyIsLocalNetworkOnlyNotProofOfDmeOrMobileInternet() {
        val state=VxscanHeaderStatus.label(Phase.READY)
        assertEquals(Tone.READY,state.tone)
        assertTrue(state.text.contains("готово"))
        assertTrue(state.text.contains("local-only"))
        assertFalse(state.text.contains("DME"))
        assertFalse(state.text.contains("интернет"))
    }

    @Test fun requestingAndWaitingIpv4AreNotReady() {
        for(phase in listOf(Phase.REQUESTING,Phase.WAITING_IP)) {
            val state=VxscanHeaderStatus.label(phase)
            assertEquals(Tone.WAITING,state.tone)
            assertFalse(state.text.contains("готово"))
        }
    }

    @Test fun disconnectedLostAndUnavailableAreDistinct() {
        assertEquals(Tone.OFF,VxscanHeaderStatus.label(Phase.OFF).tone)
        assertTrue(VxscanHeaderStatus.label(Phase.OFF).text.contains("не активирован"))
        assertTrue(VxscanHeaderStatus.label(Phase.LOST).text.contains("потеряна"))
        assertTrue(VxscanHeaderStatus.label(Phase.UNAVAILABLE).text.contains("недоступно"))
        for(phase in listOf(Phase.LOST,Phase.UNAVAILABLE,Phase.ERROR))
            assertEquals(Tone.FAILED,VxscanHeaderStatus.label(phase).tone)
    }

    @Test fun connectionAndLoggerLabelsRemainIndependentFromWifi() {
        val wifi=VxscanHeaderStatus.label(Phase.READY)
        val dme=Octane2HeaderStatus.labels(false,
            Octane2UiState.Connection.DISCONNECTED)
        assertEquals(Tone.READY,wifi.tone)
        assertTrue(dme.dme.contains("нет связи"))
        assertTrue(dme.logger.contains("остановлен"))
    }
}
