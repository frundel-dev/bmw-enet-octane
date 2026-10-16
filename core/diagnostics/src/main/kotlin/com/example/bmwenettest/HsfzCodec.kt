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
object HsfzCodec {
    const val MAX_FRAME_BODY_BYTES=65536

    /** Android host receive state, NOT the ECU's internal response phase. */
    enum class Phase {
        WAIT_ACK_OR_DATA, HEADER_PARTIAL, ACK_BODY, ACK_VERIFIED,
        WAIT_DATA, DATA_HEADER, DATA_BODY, DATA_COMPLETE, OTHER_CONTROL
    }

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
    private fun exact(input:InputStream,length:Int,idleHeader:Boolean=false,
                      firstReceived:((Long)->Unit)?=null):ByteArray? {
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
            if(count==0 && firstReceived!=null) {
                // Monotonic HOST time after the read returns; not ECU sample time.
                try{firstReceived(System.nanoTime())}catch(_:Exception){}
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
    fun readFrames(
        input:InputStream,maxFrames:Int=2,
        onAlive:((ByteArray)->Unit)?=null,
        expectedRequest:ByteArray?=null,
        onAckAtNs:((Long)->Unit)?=null,
        onDataHeaderAtNs:((Long)->Unit)?=null,
        onDataCompleteAtNs:((Long)->Unit)?=null,
        onPhase:((Phase,Long)->Unit)?=null
    ):List<ByteArray> {
        require(maxFrames in 1..8)
        val frames=ArrayList<ByteArray>(maxFrames)
        fun mark(phase:Phase,time:Long?=null) {
            if(onPhase!=null)try { onPhase(phase,time?:System.nanoTime()) }
                catch(_:Exception){} // passive instrumentation never alters framing
        }
        mark(Phase.WAIT_ACK_OR_DATA)
        // Control frames do not consume the diagnostic response allowance.
        // Still bound work to prevent an endless ACK/alive flood.
        repeat(8) {
            try {
                var headerFirstReadNs:Long?=null
                val header=exact(input,6,idleHeader=true,
                    firstReceived=if(onDataHeaderAtNs!=null || onPhase!=null)
                        { t ->
                            headerFirstReadNs=t
                            mark(Phase.HEADER_PARTIAL,t)
                        } else null)
                    ?: throw IOException("HSFZ EOF before diagnostic response")
                val length=bodyLength(header)
                if(length !in 0..MAX_FRAME_BODY_BYTES || length==1)
                    throw IOException("Invalid HSFZ frame size: $length")
                val type=((header[4].toInt() and 255) shl 8) or
                    (header[5].toInt() and 255)
                when(type) {
                    0x01 -> {
                        if(length<2)throw IOException("HSFZ diagnostic addresses absent")
                        // Publish the DATA header BEFORE waiting for the body:
                        // even EOF midway through DATA must remain observable.
                        headerFirstReadNs?.let { t ->
                            try{onDataHeaderAtNs?.invoke(t)}catch(_:Exception){}
                        }
                        mark(Phase.DATA_HEADER,headerFirstReadNs)
                        mark(Phase.DATA_BODY)
                    }
                    0x02 -> mark(Phase.ACK_BODY)
                    else -> mark(Phase.OTHER_CONTROL)
                }
                val body=exact(input,length) ?: throw IOException("Truncated HSFZ body")
                val frame=header+body
                when(type) {
                    0x01 -> {
                        val completedAt=System.nanoTime()
                        try{onDataCompleteAtNs?.invoke(completedAt)}
                            catch(_:Exception){}
                        mark(Phase.DATA_COMPLETE,completedAt)
                        frames.add(frame)
                        if(frames.size>=maxFrames)return frames
                        return frames // a single matched transaction owns this socket
                    }
                    0x02 -> {
                        if(frame.size>13 || expectedRequest==null ||
                            frame.size>expectedRequest.size ||
                            (6 until frame.size).any { frame[it]!=expectedRequest[it] })
                            throw IOException("HSFZ ACK not correlated with request")
                        val verifiedAt=System.nanoTime()
                        try{onAckAtNs?.invoke(verifiedAt)}
                            catch(_:Exception){}
                        mark(Phase.ACK_VERIFIED,verifiedAt)
                        mark(Phase.WAIT_DATA)
                    }
                    0xFF -> throw IOException("HSFZ gateway NACK: reconnect required")
                    0x12 -> {
                        if(length!=2 || onAlive==null)
                            throw IOException("HSFZ alive-check cannot be answered")
                        // Per EdiabasLib EdInterfaceEnet TcpDiagEnetReceiver.
                        onAlive(byteArrayOf(0,0,0,2,0,0x13,0,0xF4.toByte()))
                    }
                    else -> throw IOException("Unexpected HSFZ type $type")
                }
            } catch(e:SocketTimeoutException) {
                // The current request may still complete later. Reusing
                // this stream could misattribute its delayed response.
                throw IOException("HSFZ diagnostic response timeout; reconnect",e)
            }
        }
        throw IOException("Too many HSFZ control frames")
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
