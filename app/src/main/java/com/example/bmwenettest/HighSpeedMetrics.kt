package com.example.bmwenettest

import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.ceil

/**
 * Pure, bounded, monotonic Android-side timings. None is an ECU acquisition timestamp.
 * Byte changes are OBSERVED RESPONSE changes, not proof of DME internal update rate.
 */
internal class HighSpeedMetrics(private val historySize:Int=128) {
    init { require(historySize in 2..4096) }
    data class Request(
        val setupNs:Long, val sendNs:Long, val firstByteNs:Long?,
        val receiveRemainderNs:Long?, val decodeNs:Long,
        val valid:Boolean, val fingerprint:Int?
    )
    data class Summary(
        val channel:String, val requests:Long, val successful:Long, val errors:Long,
        val responseChanges:Long, val responseUnchanged:Long,
        val firstByteMedianMs:Double?, val firstByteP95Ms:Double?,
        val firstByteP99Ms:Double?, val firstByteMaxMs:Double?,
        val sendP95Ms:Double?, val receiveP95Ms:Double?,
        val decodeP95Ms:Double?, val completeP95Ms:Double?,
        val setupP95Ms:Double?,
        val successHz:Double?, val requestHz:Double?
    ) {
        val completeness:Double get()=if(requests==0L)0.0 else successful.toDouble()/requests
    }
    private class Timeline(private val limit:Int) {
        private val recent=ArrayDeque<Long>()
        fun add(v:Long?) {
            if(v==null || v<0L)return
            recent.addLast(v)
            if(recent.size>limit)recent.removeFirst()
        }
        fun ms(q:Double):Double? {
            if(recent.isEmpty())return null
            val sorted=recent.sorted()
            val index=(ceil(q*sorted.size).toInt()-1).coerceIn(0,sorted.lastIndex)
            return sorted[index]/1_000_000.0
        }
    }
    private class Channel(val limit:Int) {
        var requests=0L;var valid=0L;var errors=0L;var changed=0L;var unchanged=0L
        var previousFingerprint:Int?=null
        var firstRequestAt:Long?=null;var lastRequestAt:Long?=null
        var firstSuccessAt:Long?=null;var lastSuccessAt:Long?=null
        val setup=Timeline(limit);val send=Timeline(limit);val first=Timeline(limit)
        val receive=Timeline(limit);val decode=Timeline(limit);val total=Timeline(limit)
    }
    private val channels=linkedMapOf<String,Channel>()
    private fun rate(count:Long,start:Long?,end:Long?):Double? =
        if(count<=1L || start==null || end==null || end<=start)null
        else (count-1)*1_000_000_000.0/(end-start)

    /** Call only from the single polling socket owner. */
    fun record(channel:String,atNs:Long,value:Request) {
        require(channel.isNotBlank() && channel.length<=48)
        val c=channels.getOrPut(channel){Channel(historySize)}
        c.requests++
        if(c.firstRequestAt==null)c.firstRequestAt=atNs
        c.lastRequestAt=atNs
        c.setup.add(value.setupNs);c.send.add(value.sendNs)
        c.first.add(value.firstByteNs);c.receive.add(value.receiveRemainderNs)
        c.decode.add(value.decodeNs)
        c.total.add(
            if(value.firstByteNs!=null && value.receiveRemainderNs!=null)
                value.setupNs+value.sendNs+value.firstByteNs+
                    value.receiveRemainderNs+value.decodeNs else null)
        if(value.valid) {
            c.valid++
            if(c.firstSuccessAt==null)c.firstSuccessAt=atNs
            c.lastSuccessAt=atNs
            value.fingerprint?.let { fp ->
                val old=c.previousFingerprint
                if(old!=null) {
                    if(old==fp)c.unchanged++ else c.changed++
                }
                c.previousFingerprint=fp
            }
        } else c.errors++
    }
    fun snapshot():List<Summary> = channels.map{(name,c)->
        Summary(name,c.requests,c.valid,c.errors,c.changed,c.unchanged,
            c.first.ms(.5),c.first.ms(.95),c.first.ms(.99),c.first.ms(1.0),
            c.send.ms(.95),c.receive.ms(.95),c.decode.ms(.95),
            c.total.ms(.95),c.setup.ms(.95),
            rate(c.valid,c.firstSuccessAt,c.lastSuccessAt),
            rate(c.requests,c.firstRequestAt,c.lastRequestAt))
    }
    fun reset() { channels.clear() }
}

/** Wraps one inbound response to timestamp the FIRST successfully received byte. */
internal class FirstByteMeasuredStream(
    source:InputStream, private val firstRead:(Long)->Unit,
    private val clock:()->Long=System::nanoTime
):FilterInputStream(source) {
    private var signalled=false
    private fun received(n:Int) {
        if(n>0 && !signalled){signalled=true;firstRead(clock())}
    }
    override fun read():Int = inputReadByte()
    private fun inputReadByte():Int {
        val value=`in`.read()
        received(if(value>=0)1 else -1)
        return value
    }
    override fun read(b:ByteArray,off:Int,len:Int):Int {
        val count=`in`.read(b,off,len);received(count);return count
    }
}
