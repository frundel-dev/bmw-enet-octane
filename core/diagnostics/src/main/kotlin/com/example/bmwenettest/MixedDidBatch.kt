package com.example.bmwenettest

/**
 * Experimental B+ read-only UDS 0x22 batch, four uint32 knock signals and
 * four int16 ignition angles in fixed ECU profile order.
 *
 * Must reject all truncated, reordered, extra and negative replies. It is
 * safe to run B+ only when a full reply can be matched unambiguously.
 */
object MixedDidBatch {
    val widths:List<Pair<Int,Int>> =
        DmeBatchParser.knockDids.map { it to 4 } +
        DmeBatchParser.ignitionDids.map { it to 2 }

    val dids:List<Int> = widths.map { it.first }
    fun request():ByteArray=DmeBatchParser.request(dids)

    fun parse(payload:ByteArray):Map<Int,ByteArray>? {
        if(payload.size!=1+widths.sumOf { 2+it.second } ||
            payload.firstOrNull()!=0x62.toByte())return null
        var offset=1
        val values=linkedMapOf<Int,ByteArray>()
        for((did,size) in widths) {
            val returned=((payload[offset].toInt() and 0xFF) shl 8) or
                (payload[offset+1].toInt() and 0xFF)
            if(returned!=did)return null
            offset+=2
            values[did]=payload.copyOfRange(offset,offset+size)
            offset+=size
        }
        return values
    }
}
