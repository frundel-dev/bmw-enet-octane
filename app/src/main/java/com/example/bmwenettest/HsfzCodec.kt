package com.example.bmwenettest

import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException

/**
 * BMW ENET HSFZ stream framing shared by the logger and secondary TCP worker.
 *
 * Length is the number of bytes following the 6-byte header, including the
 * two diagnostic addresses. A TCP read is not a complete protocol frame.
 * Parsing and encoding are pure JVM code so framing is tested without a car.
 */
internal object HsfzCodec {
    const val MAX_FRAME_BODY_BYTES=65536

    fun encode(request:ByteArray):ByteArray {
        require(request.size<=MAX_FRAME_BODY_BYTES-2) { "HSFZ request too large" }
        val length=2+request.size
        return byteArrayOf(
            (length ushr 24).toByte(),(length ushr 16).toByte(),
            (length ushr 8).toByte(),length.toByte(),
            0x00,0x01,0xF4.toByte(),0x12
        )+request
    }

    /**
     * A timed-out or disconnected stream may only be reused if no part of
     * the next HSFZ header has been consumed. Otherwise framing is lost and
     * the owning socket MUST be closed/reconnected by the caller.
     */
    private fun exact(input:InputStream,length:Int,idleHeader:Boolean=false):ByteArray? {
        val out=ByteArray(length)
        var count=0
        var zeroReads=0
        while(count<length) {
            val received=try {
                input.read(out,count,length-count)
            } catch(e:SocketTimeoutException) {
                if(count>0 || !idleHeader)
                    throw IOException("Incomplete HSFZ frame on timeout ($count/$length)",e)
                throw e
            }
            if(received<0) {
                if(count>0 || !idleHeader)
                    throw IOException("Incomplete HSFZ frame on EOF ($count/$length)")
                return null
            }
            if(received==0) {
                if(++zeroReads>3)throw IOException("HSFZ input repeatedly returned zero bytes")
                continue
            }
            zeroReads=0
            count+=received
        }
        return out
    }

    /**
     * Read at most [maxFrames] complete messages. Non-data HSFZ frames may
     * precede the diagnostic answer. Truncated messages are never returned.
     */
    fun readFrames(input:InputStream,maxFrames:Int=2):List<ByteArray> {
        require(maxFrames in 1..8)
        val frames=ArrayList<ByteArray>(maxFrames)
        repeat(maxFrames) {
            try {
                val header=exact(input,6,idleHeader=true) ?: return frames
                val length=bodyLength(header)
                if(length !in 2..MAX_FRAME_BODY_BYTES)
                    throw IOException("Invalid HSFZ frame size: $length")
                val body=exact(input,length) ?: return frames
                frames.add(header+body)
                if(isDiagnosticType(header))return frames
            } catch(_:SocketTimeoutException) {
                return frames
            }
        }
        return frames
    }

    fun payload(frame:ByteArray):ByteArray? {
        if(frame.size<8 || !isDiagnosticType(frame))return null
        val length=bodyLength(frame)
        if(length !in 2..MAX_FRAME_BODY_BYTES || frame.size!=6+length)return null
        // Two address bytes belong to HSFZ, not to the OBD/UDS response.
        return frame.copyOfRange(8,frame.size)
    }

    private fun isDiagnosticType(header:ByteArray):Boolean =
        header.size>=6 && header[4].toInt()==0 && header[5].toInt()==1

    private fun bodyLength(frame:ByteArray):Int =
        ((frame[0].toInt() and 255) shl 24) or
            ((frame[1].toInt() and 255) shl 16) or
            ((frame[2].toInt() and 255) shl 8) or
            (frame[3].toInt() and 255)
}
