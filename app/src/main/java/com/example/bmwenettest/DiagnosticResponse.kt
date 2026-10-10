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
        val position=(0 until payload.size-1).firstOrNull { i ->
            (payload[i].toInt() and 255)==0x41 &&
                (payload[i+1].toInt() and 255)==id
        } ?: return null
        return payload.copyOfRange(position+2,payload.size)
    }

    fun uds(payload:ByteArray,did:Int):ByteArray? {
        if(payload.size<3 || (payload[0].toInt() and 255)!=0x62 ||
            (payload[1].toInt() and 255)!=(did ushr 8 and 255) ||
            (payload[2].toInt() and 255)!=(did and 255))return null
        return payload.copyOfRange(3,payload.size)
    }
}
