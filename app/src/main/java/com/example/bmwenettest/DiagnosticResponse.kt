package com.example.bmwenettest

/**
 * Payload-level response correlation. HSFZ carries no transaction identifier:
 * only the requested service and PID/DID may be accepted as that measurement.
 * Transport framing is validated independently by HsfzCodec.
 */
internal object DiagnosticResponse {
    fun obd(payload:ByteArray,pid:Int):ByteArray? {
        // Some supported gateways add a transport prefix before the OBD bytes.
        // Retain legacy framing compatibility, but NEVER accept another PID.
        val id=pid and 0xFF
        val expected=when(id) {
            0x0C,0x44 -> 2
            0x04,0x0B,0x0D,0x0F,0x0E,0x05,0x11,0x06,0x07,0x5C,0x2F -> 1
            else -> return null
        }
        // Reject negative and unrelated-service replies before any search.
        if(payload.isEmpty() || (payload[0].toInt() and 255) in listOf(0x7F,0x62))return null
        val position=(0 until minOf(2,payload.size-1)).firstOrNull { i ->
            (payload[i].toInt() and 255)==0x41 &&
                (payload[i+1].toInt() and 255)==id
        } ?: return null
        if(payload.size-position-2!=expected)return null
        return payload.copyOfRange(position+2,payload.size)
    }

    fun uds(payload:ByteArray,did:Int):ByteArray? {
        if(payload.size<3 || (payload[0].toInt() and 255)!=0x62 ||
            (payload[1].toInt() and 255)!=(did ushr 8 and 255) ||
            (payload[2].toInt() and 255)!=(did and 255))return null
        return payload.copyOfRange(3,payload.size)
    }
}
