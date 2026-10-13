package com.example.bmwenettest

/**
 * UDS 0x23 ReadMemoryByAddress, prepared for RAM LAB 3.
 *
 * The request encoder and response decoder are usable for OFFLINE fixtures.
 * Live ECU reads are restricted to a single SWFL/A2L-matched Nkw location
 * in the published MG Flasher A2L. Actual ECU identity cannot be auto-read
 * via rejected F18x DIDs and must be manually confirmed before the ONE
 * diagnostic memory read. Do not derive a RAM address from a UDS DID.
 */
internal object RamMemoryProtocol {
    const val MAX_READ_BYTES = 8
    const val SERVICE = 0x23
    const val RESPONSE = 0x63

    data class VerifiedLocation(
        val softwareId: String,
        val sourceReference: String,
        val signal: String,
        val address: Long,
        val addressBytes: Int,
        val sizeBytes: Int
    )

    const val APPROVED_A2L = "R4C9J797B"
    const val APPROVED_SWFL = "00005D55-070.060.007"
    const val A2L_INTERNAL_PROJECT = "R4C2J797B"
    const val RPM_SIGNAL = "Nkw"
    // Published source file matched A2L and SWFL from MG Flasher vehicle
    // diagnostics. Its internal PROJECT ID is the MG1CS201 R4C2 variant
    // also associated with R4C9 externally; no other firmware permitted.
    // Source blob SHA d04645de63325a9db7ea1856088d657fb798551f.
    private val vehicleApprovedLocations: List<VerifiedLocation> = listOf(
        VerifiedLocation(
            softwareId=APPROVED_A2L,
            sourceReference="mgflasher-team/mgflasher-map-packs " +
                "R4C9J797B_5d55-070_060_007.a2l @d04645de",
            signal=RPM_SIGNAL,
            address=0x60003B64L,
            addressBytes=4,
            sizeBytes=2
        )
    )

    /** From A2L SWORD, BYTE_ORDER MSB_LAST, identity conversion: rpm. */
    fun decodeNkwRpm(data:ByteArray):Int? {
        if(data.size!=2)return null
        val unsigned=(data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8)
        val value=if(unsigned>=0x8000)unsigned-0x10000 else unsigned
        return value.takeIf { it in 0..10000 }
    }

    fun liveProfileAvailable(): Boolean = vehicleApprovedLocations.isNotEmpty()

    fun approvedVehicleRequest(softwareId: String, signal: String): ByteArray? {
        if (softwareId.isBlank() || signal.isBlank()) return null
        val matches=vehicleApprovedLocations.filter {
            it.softwareId==softwareId && it.signal==signal &&
                it.sourceReference.isNotBlank()
        }
        val location=matches.singleOrNull() ?: return null
        return encode(location.address,location.addressBytes,location.sizeBytes)
    }

    /** Fail closed when OBD speed or reference RPM cannot confirm safe conditions. */
    fun vehicleStationary(obdSpeedData:ByteArray):Boolean =
        obdSpeedData.size==1 && (obdSpeedData[0].toInt() and 0xFF)==0

    fun parkedIdleRpm(rpm:Double):Boolean =
        rpm.isFinite() && rpm in 450.0..1800.0

    fun matchesObdRpm(before:Double,after:Double,ramRpm:Int):Boolean {
        if(!parkedIdleRpm(before) || !parkedIdleRpm(after) ||
            ramRpm !in 0..10000)return false
        val low=minOf(before,after)
        val high=maxOf(before,after)
        return (high-low)<=120.0 &&
            ramRpm.toDouble() in (low-100.0)..(high+100.0)
    }

    /** Format identifier: high nibble size-length, low nibble address-length. */
    fun encode(address: Long, addressBytes: Int, sizeBytes: Int): ByteArray {
        require(addressBytes in 1..4) { "Unsupported memory address width" }
        require(sizeBytes in 1..MAX_READ_BYTES) { "Out-of-range read size" }
        val maxAddress=(1L shl (8*addressBytes))-1
        require(address>0 && address<=maxAddress) { "Memory address out of range" }
        require(address <= maxAddress-sizeBytes+1) { "Memory range overflows address space" }
        val result=ByteArray(3+addressBytes)
        result[0]=SERVICE.toByte()
        result[1]=((1 shl 4) or addressBytes).toByte() // one byte memorySize
        for (i in 0 until addressBytes) {
            val shift=(addressBytes-1-i)*8
            result[2+i]=(address ushr shift).toByte()
        }
        result[result.lastIndex]=sizeBytes.toByte()
        return result
    }

    /**
     * A positive UDS response must be exactly 0x63 followed by requested
     * bytes; any NRC, unexpected size or SID is NOT a measurement.
     */
    fun decodePositive(response: ByteArray, expectedBytes: Int): ByteArray? {
        if(expectedBytes !in 1..MAX_READ_BYTES) return null
        if(response.size!=1+expectedBytes || (response[0].toInt() and 255)!=RESPONSE)
            return null
        return response.copyOfRange(1,response.size)
    }

    fun negativeCode(response: ByteArray): Int? =
        if(response.size==3 && (response[0].toInt() and 255)==0x7F &&
            (response[1].toInt() and 255)==SERVICE)
            response[2].toInt() and 255 else null

    /** Purely simulated, never sent to any network or car. */
    fun offlineFixture(): Pair<ByteArray,ByteArray> {
        val request=encode(0x12345678L,4,4)
        val response=byteArrayOf(0x63,0x00,0x00,0x06,0xD6.toByte())
        check(decodePositive(response,4)?.contentEquals(byteArrayOf(
            0x00,0x00,0x06,0xD6.toByte()))==true)
        check(approvedVehicleRequest("SYNTHETIC","RPM")==null)
        return request to response
    }
}
