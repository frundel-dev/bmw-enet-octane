package com.example.bmwenettest

import kotlin.math.abs

/**
 * RAM LAB Suite 1.0. One tested Nkw address and ONE candidate Dzw_tot_kr
 * address from the car-specific MG Flasher A2L; not a generic RAM scanner.
 *
 * Stage completion is a TECHNICAL result (valid frames, safety guards and
 * plausible values). It is not proof of signal semantics or fuel quality.
 */
internal object RamSuiteProtocol {
    const val VERSION="suite1-a2l-R4C9J797B"
    const val RPM_ADDRESS=0x60003B64L
    const val RETARD_ADDRESS=0x60003618L
    const val PACE_MS=300L
    const val MAX_RUN_MS=180_000L

    enum class Stage(val number:Int,val label:String,val seconds:Int) {
        SIGNAL(7,"RAM: угол зажигания",20),
        RPM_DYNAMICS(8,"Отклик RPM / OBD",20),
        SEQUENTIAL(9,"Последовательные RAM",20),
        RECONNECT(10,"Переподключение",35),
        REPEATABILITY(11,"Воспроизводимость",35),
        SOAK(12,"Длительный сбор",130)
    }

    /** Only allow immediately following the previously completed stage. */
    fun allowed(stage:Stage,completed:Set<Int>):Boolean =
        stage==Stage.SIGNAL ||
            (Stage.SIGNAL.number until stage.number).all { it in completed }

    /** Picker options are always six visible items; no AlertDialog message is used. */
    fun menuItems(completed:Set<Int>):Array<String> =
        Stage.entries.map { stage ->
            val state=when {
                stage.number in completed -> "✓ Технически завершён"
                allowed(stage,completed) -> "Можно запускать"
                else -> "Заблокирован предыдущим этапом"
            }
            "LAB ${stage.number} • ${stage.label}\\n$state"
        }.toTypedArray()

    fun nextStage(completed:Set<Int>):Stage? =
        Stage.entries.firstOrNull { it.number !in completed &&
            allowed(it,completed) }

    fun memoryRequest(signal:String):ByteArray=when(signal) {
        "Nkw" -> RamMemoryProtocol.approvedVehicleRequest(
            RamMemoryProtocol.APPROVED_A2L,RamMemoryProtocol.RPM_SIGNAL)
            ?.takeIf { it.contentEquals(byteArrayOf(
                0x23,0x14,0x60,0,0x3B,0x64,0x02)) }
            ?: error("Nkw address/source changed")
        "Dzw_tot_kr" -> RamMemoryProtocol.encode(RETARD_ADDRESS,4,2)
            .also { check(it.contentEquals(byteArrayOf(
                0x23,0x14,0x60,0,0x36,0x18,0x02))) }
        else -> throw IllegalArgumentException("RAM signal not allowlisted: $signal")
    }

    /** A2L: SWORD, MSB_LAST, BMW_q0p1_deg, COEFFS 0 10 0 0 0 1. */
    fun retardDegrees(payload:ByteArray):Double? {
        val data=RamMemoryProtocol.decodePositive(payload,2)?:return null
        val raw=(data[0].toInt() and 0xff) or
            ((data[1].toInt() and 0xff) shl 8)
        val signed=if(raw>=32768)raw-65536 else raw
        val deg=signed/10.0
        // Conservative sanity cap for parked, steady idle, not an
        // assertion of total retard semantics or cylinder corrections.
        return deg.takeIf { it in -45.0..45.0 }
    }

    fun rpm(payload:ByteArray):Double?=
        RamMemoryProtocol.decodePositive(payload,2)
            ?.let(RamMemoryProtocol::decodeNkwRpm)?.toDouble()

    fun obdRpm(payload:ByteArray):Double?=
        RamDirectBenchmarkProtocol.decodeRpm(
            RamDirectBenchmarkProtocol.Source.OBD,payload)

    fun speedStationary(payload:ByteArray):Boolean=
        DiagnosticResponse.obd(payload,0x0D)
            ?.let(RamMemoryProtocol::vehicleStationary)==true

    fun plausible(rpm:Double?,base:Double):Boolean =
        rpm!=null && RamMemoryProtocol.parkedIdleRpm(base) &&
            RamMemoryProtocol.parkedIdleRpm(rpm) && abs(rpm-base)<=150.0

    fun packedIgnitionReply(payload:ByteArray):Boolean =
        DmeBatchParser.parse(payload,listOf(0x4A49),2)!=null

    fun gapMs(prevMs:Long,nowMs:Long):Long? =
        if(nowMs>=prevMs)nowMs-prevMs else null

    fun count(stage:Stage):Int = when(stage) {
        Stage.SIGNAL->4
        Stage.RPM_DYNAMICS->8
        Stage.SEQUENTIAL->6
        Stage.RECONNECT->2
        Stage.REPEATABILITY->12
        Stage.SOAK->90
    }
}
