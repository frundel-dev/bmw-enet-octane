package com.example.bmwenettest

/**
 * Strict parser for UDS ReadDataByIdentifier (service 0x22) with multiple DIDs.
 * Success never gets inferred from a single-DID response or truncated payload.
 * Only fixed-length DME8FF_R records are accepted.
 */
object DmeBatchParser {
    val knockDids=listOf(0x4A37,0x4A38,0x4A39,0x4A3A)
    val ignitionDids=listOf(0x4A49,0x4A4A,0x4A4C,0x4A4D)

    fun request(dids:List<Int>):ByteArray {
        require(dids.isNotEmpty() && dids.size<=8)
        val bytes=ArrayList<Byte>(1+dids.size*2)
        bytes.add(0x22.toByte())
        dids.forEach { id ->
            bytes.add((id ushr 8).toByte())
            bytes.add(id.toByte())
        }
        return bytes.toByteArray()
    }

    fun parse(payload:ByteArray,dids:List<Int>,sizeEach:Int):Map<Int,ByteArray>? {
        if(dids.isEmpty() || payload.size!=1+dids.size*(2+sizeEach))return null
        if((payload[0].toInt() and 255)!=0x62)return null
        val records=linkedMapOf<Int,ByteArray>()
        var pos=1
        for(did in dids) {
            val returned=((payload[pos].toInt() and 255) shl 8) or
                (payload[pos+1].toInt() and 255)
            if(returned!=did)return null
            pos+=2
            records[did]=payload.copyOfRange(pos,pos+sizeEach)
            pos+=sizeEach
        }
        return records
    }
}
