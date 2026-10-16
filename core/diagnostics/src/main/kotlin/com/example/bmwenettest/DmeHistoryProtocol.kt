package com.example.bmwenettest

/** Safe, bounded UDS 0x19 DTC history decoding; DTCs are not knock events. */
object DmeHistoryProtocol {
    const val MAX_DTCS = 12
    const val MAX_RESPONSE_BYTES = 32768

    data class Dtc(val code:Int, val status:Int) {
        val hex:String get() = "%06X".format(java.util.Locale.US,code)
        val statusHex:String get() = "%02X".format(java.util.Locale.US,status)
    }

    // 0x19 0x02: response 0x59 0x02 availability-mask then 4-byte
    // [3-byte DTC, 1-byte status] records; unlike 0x19 0x01 there is
    // NO DTCFormatIdentifier field in this response.
    fun parseDtcList(response:ByteArray):List<Dtc>? {
        if(response.size<3 || response.size>MAX_RESPONSE_BYTES ||
            (response[0].toInt() and 255)!=0x59 ||
            (response[1].toInt() and 255)!=0x02 ||
            (response.size-3)%4!=0)return null
        return (3 until response.size step 4).map { index ->
            val code=((response[index].toInt() and 255) shl 16) or
                ((response[index+1].toInt() and 255) shl 8) or
                (response[index+2].toInt() and 255)
            Dtc(code,response[index+3].toInt() and 255)
        }
    }

    fun byDtcRequest(subfunction:Int,code:Int):ByteArray {
        require(subfunction==0x04 || subfunction==0x06)
        require(code in 0..0xFFFFFF)
        // All record numbers, if supported. The ECU may reject 0xFF.
        return byteArrayOf(0x19,subfunction.toByte(),
            (code ushr 16).toByte(),(code ushr 8).toByte(),code.toByte(),
            0xFF.toByte())
    }

    fun dtcResponseMatches(subfunction:Int,code:Int,response:ByteArray):Boolean {
        if(subfunction!=0x04 && subfunction!=0x06)return false
        if(response.size<6 || response.size>MAX_RESPONSE_BYTES ||
            (response[0].toInt() and 255)!=0x59 ||
            (response[1].toInt() and 255)!=subfunction)return false
        val returned=((response[2].toInt() and 255) shl 16) or
            ((response[3].toInt() and 255) shl 8) or
            (response[4].toInt() and 255)
        return returned==code
    }

    fun classifyNrc(requestService:Int,response:ByteArray):String? {
        if(response.size!=3 ||
            (response[0].toInt() and 255)!=0x7F ||
            (response[1].toInt() and 255)!=requestService)return null
        return when(response[2].toInt() and 255) {
            0x11,0x12,0x31 -> "UNSUPPORTED"
            0x22 -> "CONDITIONS_NOT_CORRECT"
            0x33 -> "SECURITY_DENIED"
            0x78 -> "RESPONSE_PENDING"
            else -> "NEGATIVE"
        }
    }

    fun hex(bytes:ByteArray):String=bytes.joinToString("") {
        "%02X".format(java.util.Locale.US,it.toInt() and 255)
    }

    fun csv(vararg values:Any?):String=values.joinToString(",") { raw ->
        val str=raw?.toString() ?: ""
        if(str.contains(',') || str.contains('"') ||
            str.contains('\n') || str.contains('\r'))
            "\"" + str.replace("\"","\"\"") + "\""
        else str
    }
    /** A physical newline is required; literal backslash-n corrupts the next CSV row. */
    fun completeCsvRow(csv:String):String=csv+"\n"
}
