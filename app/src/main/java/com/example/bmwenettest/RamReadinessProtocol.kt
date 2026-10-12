package com.example.bmwenettest

/**
 * Explicitly allowlisted read-only DME survey for the RAM Logging feasibility
 * experiment. These are conventional UDS 0x22 DIDs, NOT RAM memory addresses.
 * No 0x23, 0x2C, 0x10, 0x27, coding, flashing, or diagnostic state changes.
 */
internal object RamReadinessProtocol {
    data class Probe(val did:Int,val title:String)

    val probes=listOf(
        Probe(0xF180,"Boot software identification"),
        Probe(0xF181,"Application software identification"),
        Probe(0xF187,"Manufacturer spare part number"),
        Probe(0xF189,"Manufacturer software version"),
        Probe(0x4A37,"Existing knock DID — cylinder 1"),
        Probe(0x4A49,"Existing ignition DID — cylinder 1")
    )

    fun request(did:Int):ByteArray {
        require(probes.any { it.did==did }) { "DID not allowlisted for read-only probe" }
        return byteArrayOf(0x22,(did ushr 8).toByte(),did.toByte())
    }

    fun positiveMatches(did:Int,response:ByteArray):Boolean =
        probes.any { it.did==did } &&
        response.size in 4..512 &&
        (response[0].toInt() and 0xFF)==0x62 &&
        (response[1].toInt() and 0xFF)==(did ushr 8) &&
        (response[2].toInt() and 0xFF)==(did and 0xFF)
}
