package com.example.bmwenettest

/**
 * Controls that remain usable after removing retired one-off laboratories.
 * Neither list changes diagnostic polling, sockets, ECU requests or CSV schemas.
 */
internal object DiagnosticMenu {
    enum class Tool(val label: String) {
        MEASUREMENT_HISTORY("История замеров"),
        CONNECTION_EVENTS("Журнал VXSCAN и Android"),
        ANDROID_EVENTS("События сети Android"),
        TCP_RECONNECTS("TCP-реконнекты"),
        POLLING_PERFORMANCE("Производительность опроса"),
        DME_HISTORY_READ("История и адаптации DME (LOGGER STOP)"),
        DME_HISTORY_FILES("Сохранённая история DME"),
        DME_MONITOR_LOG("Журнал DME Monitor"),
        FUEL_HISTORY("История заправок"),
        COMPARISON_EXPORT("Экспорт сравнительных CSV"),
        FAST_DID_CANDIDATES("FAST DID • кандидаты (offline)"),
        FAST_RAW_EVIDENCE("FAST DID • RAW / достоверность (offline)"),
        RAM_SWFL("RAM / SWFL • экспериментальный доступ")
    }

    val operations = listOf(
        Tool.MEASUREMENT_HISTORY,
        Tool.CONNECTION_EVENTS,
        Tool.ANDROID_EVENTS,
        Tool.TCP_RECONNECTS,
        Tool.POLLING_PERFORMANCE,
        Tool.DME_HISTORY_READ,
        Tool.DME_HISTORY_FILES,
        Tool.DME_MONITOR_LOG,
        Tool.FUEL_HISTORY,
        Tool.COMPARISON_EXPORT
    )

    val research = listOf(
        Tool.FAST_DID_CANDIDATES,
        Tool.FAST_RAW_EVIDENCE,
        Tool.RAM_SWFL
    )

    const val RETIREMENT_MIGRATED_KEY = "legacy_labs_retired_221"
    const val MODE_KEY = "measurement_mode"

    // The retired TEST button must not strand an upgraded installation in
    // TEST, which suppresses the normal AUTO STEADY/ACCEL capture path.
    const val NORMAL_MODE = "AUTO"

    data class RetiredDefaults(val measurementMode: String, val baselineRecording: Boolean)

    /**
     * One-time migration of hidden, previously user-configurable legacy modes.
     * Never edits datasets or CSV; only prevents invisible TEST/manual capture.
     */
    @Suppress("UNUSED_PARAMETER")
    fun retiredDefaults(previousMode: String?, baselineRecording: Boolean): RetiredDefaults =
        RetiredDefaults(NORMAL_MODE, false)
}
