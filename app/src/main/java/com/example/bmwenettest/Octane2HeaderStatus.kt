package com.example.bmwenettest

/** Small status labels for the permanent header, independent of the scrollable Home tab. */
internal object Octane2HeaderStatus {
    data class Labels(
        val dme:String,
        val logger:String,
        val effectiveConnection:Octane2UiState.Connection
    )

    fun labels(logging:Boolean,connection:Octane2UiState.Connection):Labels {
        // The live ENET socket belongs to LOGGER. A cached CONNECTED value
        // cannot survive a stopped Service.
        val state=if(logging)connection else Octane2UiState.Connection.DISCONNECTED
        val dme=when(state) {
            Octane2UiState.Connection.CONNECTED -> "● DME: подключено"
            Octane2UiState.Connection.CONNECTING -> "● DME: подключение"
            Octane2UiState.Connection.RECONNECTING -> "● DME: переподключение"
            Octane2UiState.Connection.ERROR -> "● DME: ошибка"
            Octane2UiState.Connection.DISCONNECTED -> "● DME: нет связи"
        }
        val logger=when {
            !logging -> "LOGGER: остановлен"
            state==Octane2UiState.Connection.CONNECTED -> "LOGGER: сбор"
            state==Octane2UiState.Connection.RECONNECTING -> "LOGGER: восстановление"
            state==Octane2UiState.Connection.ERROR -> "LOGGER: ошибка"
            else -> "LOGGER: ожидание"
        }
        return Labels(dme,logger,state)
    }
}
