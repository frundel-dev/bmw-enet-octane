package com.example.bmwenettest

/**
 * UDS 0x23 ReadMemoryByAddress, prepared for RAM LAB 3.
 *
 * The request encoder and response decoder are usable for OFFLINE fixtures.
 * Live ECU requests MUST pass through [approvedVehicleRequest], which is
 * deliberately disabled until a firmware-exact, independently verified
 * address/size/encoding profile exists. Never derive an ECU RAM address from
 * a UDS DID, an arbitrary user input or a generic vehicle model.
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

    // Intentionally empty. Neither MG Flasher binaries nor RAM LAB 1's
    // negative F18x responses establish the car's exact firmware/addresses.
    private val vehicleApprovedLocations: List<VerifiedLocation> = emptyList()

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
