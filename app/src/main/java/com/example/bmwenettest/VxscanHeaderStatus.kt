package com.example.bmwenettest

/**
 * Presentation-only mapping of the Android local-only Wi-Fi reservation.
 * READY means Network + IPv4 are available; it does not attest to DME or
 * Internet connectivity. No network access or ECU commands here.
 */
internal object VxscanHeaderStatus {
    data class Label(val text:String,val tone:Tone)
    enum class Tone { READY, WAITING, FAILED, OFF }

    fun label(phase:VxscanLocalOnlyWifi.Phase):Label=when(phase) {
        VxscanLocalOnlyWifi.Phase.OFF ->
            Label("● VXSCAN Wi-Fi: не активирован",Tone.OFF)
        VxscanLocalOnlyWifi.Phase.REQUESTING ->
            Label("● VXSCAN Wi-Fi: подключение…",Tone.WAITING)
        VxscanLocalOnlyWifi.Phase.WAITING_IP ->
            Label("● VXSCAN Wi-Fi: ожидание IP…",Tone.WAITING)
        VxscanLocalOnlyWifi.Phase.READY ->
            Label("● VXSCAN Wi-Fi: готово · local-only",Tone.READY)
        VxscanLocalOnlyWifi.Phase.LOST ->
            Label("● VXSCAN Wi-Fi: связь потеряна",Tone.FAILED)
        VxscanLocalOnlyWifi.Phase.UNAVAILABLE ->
            Label("● VXSCAN Wi-Fi: недоступно",Tone.FAILED)
        VxscanLocalOnlyWifi.Phase.ERROR ->
            Label("● VXSCAN Wi-Fi: ошибка",Tone.FAILED)
    }
}
