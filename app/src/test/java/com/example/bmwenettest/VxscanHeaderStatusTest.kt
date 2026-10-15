package com.example.bmwenettest

import com.example.bmwenettest.VxscanLocalOnlyWifi.Phase as P
import com.example.bmwenettest.VxscanHeaderStatus.Tone as T
import org.junit.Assert.*
import org.junit.Test

class VxscanHeaderStatusTest {

    @Test fun readyIsLocalNetworkOnlyNotProofOfDmeOrMobileInternet() {
        val state=VxscanHeaderStatus.label(P.READY)
        assertEquals(T.READY,state.tone)
        assertTrue(state.text.contains("готово"))
        assertTrue(state.text.contains("Wi-Fi"))
        assertFalse(state.text.contains("DME"))
        assertFalse(state.text.contains("интернет"))
    }

    @Test fun requestingAndWaitingIpv4AreNotReady() {
        for(phase in listOf(P.REQUESTING,P.WAITING_IP)) {
            val state=VxscanHeaderStatus.label(phase)
            assertEquals(T.WAITING,state.tone)
            assertFalse(state.text.contains("готово"))
        }
    }

    @Test fun disconnectedLostAndUnavailableAreDistinct() {
        assertEquals(T.OFF,VxscanHeaderStatus.label(P.OFF).tone)
        assertTrue(VxscanHeaderStatus.label(P.OFF).text.contains("выключен"))
        assertTrue(VxscanHeaderStatus.label(P.LOST).text.contains("потеряна"))
        assertTrue(VxscanHeaderStatus.label(P.UNAVAILABLE).text.contains("недоступно"))
        for(phase in listOf(P.LOST,P.UNAVAILABLE,P.ERROR))
            assertEquals(T.FAILED,VxscanHeaderStatus.label(phase).tone)
    }

    @Test fun connectionAndLoggerLabelsRemainIndependentFromWifi() {
        val wifi=VxscanHeaderStatus.label(P.READY)
        val dme=Octane2HeaderStatus.labels(false,
            Octane2UiState.Connection.DISCONNECTED)
        assertEquals(T.READY,wifi.tone)
        assertTrue(dme.dme.contains("нет связи"))
        assertTrue(dme.logger.contains("остановлен"))
    }
}
