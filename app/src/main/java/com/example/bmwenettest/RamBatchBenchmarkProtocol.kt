package com.example.bmwenettest

import kotlin.math.ceil

/**
 * RAM LAB 2: an allowlisted comparison of ordinary UDS ReadDataByIdentifier
 * transactions. Nothing in this module reads raw RAM or defines dynamic DIDs.
 * No requests outside the tested 0x4A37 and 0x4A49 identifiers are generated.
 */
internal object RamBatchBenchmarkProtocol {
    const val ITERATIONS = 24
    const val MAX_RUN_MS = 30_000L

    enum class Kind { KNOCK, IGNITION, BATCH }

    fun request(kind:Kind):ByteArray = when(kind) {
        Kind.KNOCK -> byteArrayOf(0x22,0x4A,0x37)
        Kind.IGNITION -> byteArrayOf(0x22,0x4A,0x49)
        Kind.BATCH -> byteArrayOf(0x22,0x4A,0x37,0x4A,0x49)
    }

    /** The ECU must return every requested DID with the known fixed width and order. */
    fun matches(kind:Kind,reply:ByteArray):Boolean {
        val length=when(kind) {
            Kind.KNOCK -> 7 // 0x62 DID(2) uint32(4)
            Kind.IGNITION -> 5 // 0x62 DID(2) int16(2)
            Kind.BATCH -> 11 // 0x62 DID1(2) uint32(4) DID2(2) int16(2)
        }
        if(reply.size!=length || reply[0]!=0x62.toByte() ||
            reply[1]!=0x4A.toByte())return false
        return when(kind) {
            Kind.KNOCK -> reply[2]==0x37.toByte()
            Kind.IGNITION -> reply[2]==0x49.toByte()
            Kind.BATCH -> reply[2]==0x37.toByte() &&
                reply[7]==0x4A.toByte() && reply[8]==0x49.toByte()
        }
    }

    /** Alternation reduces the order bias of the two measurement modes. */
    fun order(index:Int):List<Kind> {
        require(index in 0 until ITERATIONS)
        return if(index%2==0)
            listOf(Kind.KNOCK,Kind.IGNITION,Kind.BATCH)
        else listOf(Kind.BATCH,Kind.KNOCK,Kind.IGNITION)
    }

    data class Stats(val count:Int,val medianMs:Double,val p95Ms:Long)

    fun stats(values:List<Long>):Stats? {
        if(values.isEmpty() || values.any { it<0 })return null
        val sorted=values.sorted()
        val mid=sorted.size/2
        val median=if(sorted.size%2==1)sorted[mid].toDouble()
            else sorted[mid-1]/2.0+sorted[mid]/2.0
        val p95Index=(ceil(sorted.size*0.95).toInt()-1).coerceIn(0,sorted.lastIndex)
        return Stats(sorted.size,median,sorted[p95Index])
    }
}
