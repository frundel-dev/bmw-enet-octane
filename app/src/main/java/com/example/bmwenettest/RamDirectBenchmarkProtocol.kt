package com.example.bmwenettest

/**
 * RAM LAB 6 — bounded, conservatively paced direct RPM RAM benchmark.
 * Uses ONLY previously validated UDS 0x23 Nkw, standard OBD 01 0C RPM
 * and OBD 01 0D speed. No dynamic DIDs or session-changing commands.
 */
internal object RamDirectBenchmarkProtocol {
    const val ROUNDS=4
    const val SAMPLES_PER_BLOCK=4
    const val TOTAL_PER_SOURCE=ROUNDS*SAMPLES_PER_BLOCK
    const val PACE_MS=150L
    const val MAX_RUN_MS=35_000L
    const val MAX_IDLE_DRIFT_RPM=150.0

    enum class Source { DIRECT_RAM, OBD }

    fun order(round:Int):List<Source> {
        require(round in 0 until ROUNDS)
        return if(round%2==0)
            listOf(Source.DIRECT_RAM,Source.OBD)
        else listOf(Source.OBD,Source.DIRECT_RAM)
    }

    fun rpmRequest(source:Source):ByteArray=when(source) {
        Source.DIRECT_RAM -> {
            val req=RamMemoryProtocol.approvedVehicleRequest(
                RamMemoryProtocol.APPROVED_A2L,RamMemoryProtocol.RPM_SIGNAL)
            check(req?.contentEquals(byteArrayOf(0x23,0x14,0x60,0,0x3B,0x64,2))==true) {
                "Verified RPM RAM request not available"
            }
            req
        }
        Source.OBD -> byteArrayOf(0x01,0x0C)
    }

    fun speedRequest():ByteArray=byteArrayOf(0x01,0x0D)

    /** NULL means rejected/unrelated/truncated response, not zero RPM. */
    fun decodeRpm(source:Source,response:ByteArray):Double?=when(source) {
        Source.DIRECT_RAM ->
            RamMemoryProtocol.decodePositive(response,2)
                ?.let(RamMemoryProtocol::decodeNkwRpm)?.toDouble()
        Source.OBD -> DiagnosticResponse.obd(response,0x0C)
            ?.takeIf { it.size==2 }
            ?.let { (((it[0].toInt() and 255) shl 8) or
                (it[1].toInt() and 255))/4.0 }
    }

    fun isValidIdleSample(rpm:Double?,reference:Double):Boolean =
        rpm!=null && RamMemoryProtocol.parkedIdleRpm(rpm) &&
            RamMemoryProtocol.parkedIdleRpm(reference) &&
            kotlin.math.abs(rpm-reference)<=MAX_IDLE_DRIFT_RPM

    /** Rate includes pacing AND diagnostic round trips for each block. */
    fun observedRateHz(samples:Int,elapsedMs:Long):Double? =
        if(samples<=0 || samples>SAMPLES_PER_BLOCK || elapsedMs<=0L)
            null else samples*1000.0/elapsedMs
}
