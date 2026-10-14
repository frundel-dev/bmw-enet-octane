package com.example.bmwenettest

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * Allocation-free frame BODY parsing for the RAM hot path.
 * Reused only by the single owner of the primary ENET socket.
 * Higher-level return values must not retain a view after the next read.
 */
internal class HsfzReusableReader {
    private val header=ByteArray(6)
    private val body=ByteArray(HsfzCodec.MAX_FRAME_BODY_BYTES)
    private var lastPayloadSize=0

    private fun exact(input:InputStream,out:ByteArray,length:Int,idleHeader:Boolean):Boolean {
        var got=0
        var zeroReads=0
        while(got<length) {
            val size=try { input.read(out,got,length-got) }
                catch(e:SocketTimeoutException) {
                    if(got!=0 || !idleHeader)
                        throw IOException("Incomplete HSFZ frame on timeout ($got/$length)",e)
                    throw e
                }
            if(size<0) {
                if(got!=0 || !idleHeader)
                    throw IOException("Incomplete HSFZ frame on EOF ($got/$length)")
                return false
            }
            if(size==0) {
                if(++zeroReads>3)throw IOException("HSFZ read returned zero repeatedly")
                continue
            }
            got+=size
            zeroReads=0
        }
        return true
    }

    /** Single-owner RAM receive path. No concurrent read or allocated body copies. */
    fun readDiagnostic(
        input:InputStream,maxFrames:Int=2,
        expectedRequest:ByteArray?=null,
        onAlive:((ByteArray)->Unit)?=null
    ):Int? {
        require(maxFrames in 1..8)
        lastPayloadSize=0
        repeat(8) {
            try {
                if(!exact(input,header,6,true))return null
                val size=((header[0].toInt() and 255) shl 24) or
                    ((header[1].toInt() and 255) shl 16) or
                    ((header[2].toInt() and 255) shl 8) or
                    (header[3].toInt() and 255)
                if(size !in 0..HsfzCodec.MAX_FRAME_BODY_BYTES)
                    throw IOException("Invalid HSFZ frame length $size")
                if(!exact(input,body,size,false))
                    throw IOException("Incomplete HSFZ body")
                val type=((header[4].toInt() and 255) shl 8) or
                    (header[5].toInt() and 255)
                when(type) {
                    0x01 -> {
                        if(size<2)throw IOException("Diagnostic addresses missing")
                        lastPayloadSize=size-2
                        return lastPayloadSize
                    }
                    0x02 -> {
                        if(size+6>13 || expectedRequest==null ||
                            size+6>expectedRequest.size ||
                            (0 until size).any { body[it]!=expectedRequest[it+6] })
                            throw IOException("RAM HSFZ ACK mismatch")
                    }
                    0xFF -> throw IOException("RAM HSFZ NACK; reconnect")
                    0x12 -> {
                        if(size!=2 || onAlive==null)
                            throw IOException("RAM HSFZ alive-check cannot be answered")
                        onAlive(byteArrayOf(0,0,0,2,0,0x13,0,0xF4.toByte()))
                    }
                    else -> throw IOException("Unexpected RAM HSFZ frame type $type")
                }
            } catch(e:SocketTimeoutException) {
                // A late RAM reply would corrupt transaction attribution.
                throw IOException("RAM HSFZ response timeout; reconnect",e)
            }
        }
        throw IOException("Too many RAM HSFZ control frames")
    }

    fun unsigned(index:Int):Int {
        require(index in 0 until lastPayloadSize)
        return body[index+2].toInt() and 255
    }

    fun isRamPositiveRpm():Boolean =
        lastPayloadSize==3 && unsigned(0)==RamMemoryProtocol.RESPONSE

    fun rpm():Int? {
        if(!isRamPositiveRpm())return null
        val raw=unsigned(1) or (unsigned(2) shl 8)
        val signed=if(raw>=0x8000)raw-0x10000 else raw
        return signed.takeIf { it in 0..10000 }
    }

    fun ramNegativeCode():Int? =
        if(lastPayloadSize==3 && unsigned(0)==0x7F &&
            unsigned(1)==RamMemoryProtocol.SERVICE)unsigned(2) else null
}
