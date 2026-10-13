package com.example.bmwenettest

/**
 * RAM LAB 5: a single, named, temporary dynamic DID. Not a general UDS
 * request builder: the ONLY memory source is the Nkw RPM address proven by
 * RAM LAB 4 on this A2L/SWFL. No arbitrary DID/address or broad clear.
 */
internal object RamDynamicDidProtocol {
    const val DID = 0xF3EF
    const val SAMPLES = 6
    const val MAX_EXPERIMENT_MS = 30_000L

    fun sourceMatches(): Boolean =
        RamMemoryProtocol.APPROVED_A2L == "R4C9J797B" &&
        RamMemoryProtocol.APPROVED_SWFL == "00005D55-070.060.007" &&
        RamMemoryProtocol.approvedVehicleRequest(
            RamMemoryProtocol.APPROVED_A2L, RamMemoryProtocol.RPM_SIGNAL
        )?.contentEquals(byteArrayOf(0x23, 0x14, 0x60, 0, 0x3B, 0x64, 2)) == true

    /** Probe before defining: only an exact NRC 0x31 proves "not readable". */
    fun preflightRequest(): ByteArray = byteArrayOf(0x22, 0xF3.toByte(), 0xEF.toByte())
    fun isVacantRead(response: ByteArray): Boolean =
        response.contentEquals(byteArrayOf(0x7F, 0x22, 0x31))

    fun defineRequest(): ByteArray {
        check(sourceMatches()) { "Unverified SWFL/A2L memory mapping" }
        // 0x02 defineByMemoryAddress; ALFID 0x14: 4-byte address,
        // 1-byte memory length; 2 bytes at proven Nkw address.
        return byteArrayOf(0x2C, 0x02, 0xF3.toByte(), 0xEF.toByte(),
            0x14, 0x60, 0, 0x3B, 0x64, 0x02)
    }

    fun defineResponseMatches(response:ByteArray):Boolean =
        response.contentEquals(byteArrayOf(0x6C, 0x02, 0xF3.toByte(), 0xEF.toByte()))

    fun readRequest():ByteArray = preflightRequest()

    /** A dynamic read has EXACTLY 2 data bytes. */
    fun readRpm(response: ByteArray):Int? {
        if(response.size!=5 ||
            response[0]!=0x62.toByte() ||
            response[1]!=0xF3.toByte() ||
            response[2]!=0xEF.toByte()) return null
        return RamMemoryProtocol.decodeNkwRpm(response.copyOfRange(3,5))
    }

    /** Targeted clear ONLY: never send 2C 03 (which clears all DDDIs). */
    fun clearRequest():ByteArray =
        byteArrayOf(0x2C, 0x03, 0xF3.toByte(), 0xEF.toByte())

    fun clearResponseMatches(response:ByteArray):Boolean =
        response.contentEquals(byteArrayOf(0x6C, 0x03, 0xF3.toByte(), 0xEF.toByte()))
}
