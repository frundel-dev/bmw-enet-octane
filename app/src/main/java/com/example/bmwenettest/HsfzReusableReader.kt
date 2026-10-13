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

    /** Returns payload length, or null after a clean idle EOF/timeout. */
    fun readDiagnostic(input:InputStream,maxFrames:Int=2):Int? {
        require(maxFrames in 1..8)
        lastPayloadSize=0
        repeat(maxFrames) {
            try {
                if(!exact(input,header,6,true))return null
                val size=((header[0].toInt() and 255) shl 24) or
                    ((header[1].toInt() and 255) shl 16) or
                    ((header[2].toInt() and 255) shl 8) or
                    (header[3].toInt() and 255)
                if(size !in 2..HsfzCodec.MAX_FRAME_BODY_BYTES)
                    throw IOException("Invalid HSFZ frame length $size")
                if(!exact(input,body,size,false))
                    throw IOException("Incomplete HSFZ body")
                if(header[4].toInt()==0 && header[5].toInt()==1) {
                    lastPayloadSize=size-2
                    return lastPayloadSize
                }
            } catch(_:SocketTimeoutException) {
                return null
            }
        }
        return null
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
