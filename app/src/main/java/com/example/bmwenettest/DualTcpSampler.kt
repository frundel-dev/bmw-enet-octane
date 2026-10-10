package com.example.bmwenettest

import android.net.Network
import android.os.SystemClock
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional independent TCP/HSFZ connection for SLOW read-only diagnostics.
 * This worker owns its socket completely. The FAST DME thread never reads or
 * writes this socket; snapshots are published atomically and are timestamped.
 * Any failure causes the main logger to revert to single-socket polling.
 */
class DualTcpSampler(
    private val network:Network,
    private val gatewayIp:String,
    private val event:(String,String)->Unit
) {
    data class Snapshot(
        val whenMs:Long,
        val obd:Map<Int,ByteArray>,
        val superKnock:Int?,
        val durationMs:Long,
        val responses:Int
    )

    @Volatile var latest:Snapshot?=null
        private set
    @Volatile var state:String="CONNECTING"
        private set
    private val alive=AtomicBoolean(true)
    @Volatile private var socket:Socket?=null
    private val thread=Thread({work()},"BMW-DME-SLOW-TCP").apply {
        isDaemon=true
        start()
    }

    fun close() {
        alive.set(false)
        try{socket?.close()}catch(_:Exception){}
        thread.interrupt()
    }

    private fun exact(input:InputStream,size:Int):ByteArray? {
        val dst=ByteArray(size)
        var count=0
        while(count<size) {
            val n=input.read(dst,count,size-count)
            if(n<0)return null
            count+=n
        }
        return dst
    }

    private fun hsfz(data:ByteArray):ByteArray {
        val length=2+data.size
        return byteArrayOf(
            (length ushr 24).toByte(),(length ushr 16).toByte(),
            (length ushr 8).toByte(),length.toByte(),
            0,1,0xF4.toByte(),0x12
        )+data
    }

    private fun send(s:Socket,body:ByteArray):ByteArray? {
        s.getOutputStream().write(hsfz(body))
        s.getOutputStream().flush()
        // HSFZ can include a preliminary non-data frame.
        repeat(2) {
            try {
                val header=exact(s.getInputStream(),6)?:return null
                val length=((header[0].toInt() and 255) shl 24) or
                    ((header[1].toInt() and 255) shl 16) or
                    ((header[2].toInt() and 255) shl 8) or
                    (header[3].toInt() and 255)
                if(length !in 2..65536)throw java.io.IOException("Invalid HSFZ length")
                val payload=exact(s.getInputStream(),length)?:return null
                if(header[4].toInt()==0 && header[5].toInt()==1 &&
                    payload.size>=2) {
                    return payload.copyOfRange(2,payload.size)
                }
            } catch(_:SocketTimeoutException) { return null }
        }
        return null
    }

    private fun obd(s:Socket,pid:Int):ByteArray? {
        val p=send(s,byteArrayOf(0x01,pid.toByte())) ?: return null
        val idx=(0 until p.size-1).firstOrNull {
            p[it]==0x41.toByte() && (p[it+1].toInt() and 255)==pid
        } ?: return null
        return p.copyOfRange(idx+2,p.size)
    }

    private fun superKnock(s:Socket):Int? {
        val p=send(s,byteArrayOf(0x22,0x57,0x28)) ?: return null
        return if(p.size>=4 && (p[0].toInt() and 255)==0x62 &&
            (p[1].toInt() and 255)==0x57 && (p[2].toInt() and 255)==0x28)
            p[3].toInt() and 255 else null
    }

    private fun work() {
        try {
            val s=network.socketFactory.createSocket()
            socket=s
            s.soTimeout=1100
            s.connect(InetSocketAddress(gatewayIp,6801),1800)
            state="ACTIVE"
            event("SECOND_TCP_CONNECTED","Independent SLOW session to "+gatewayIp)
            var consecutiveEmpty=0
            val slowPids=intArrayOf(0x0D,0x0F,0x0E,0x05,0x11,0x06,0x07,0x44,0x5C,0x2F)
            while(alive.get()) {
                val started=SystemClock.elapsedRealtime()
                val values=linkedMapOf<Int,ByteArray>()
                for(pid in slowPids) {
                    if(!alive.get())break
                    obd(s,pid)?.let { values[pid]=it }
                }
                if(!alive.get())break
                val superKnock=superKnock(s)
                if(values.isEmpty() && superKnock==null) consecutiveEmpty++
                else consecutiveEmpty=0
                if(consecutiveEmpty>=3)throw java.io.IOException("No valid SLOW DME replies")
                if(values.isNotEmpty()) {
                    latest=Snapshot(SystemClock.elapsedRealtime(),values.toMap(),
                        superKnock,SystemClock.elapsedRealtime()-started,
                        values.size+(if(superKnock!=null)1 else 0))
                }
                val timeLeft=(2000L-(SystemClock.elapsedRealtime()-started)).coerceAtLeast(0L)
                if(timeLeft>0)Thread.sleep(timeLeft)
            }
        } catch(e:Exception) {
            if(alive.get()) {
                state="FAILED"
                event("SECOND_TCP_FAILED",(e.javaClass.simpleName+": "+(e.message?:"error")).take(180))
            }
        } finally {
            try{socket?.close()}catch(_:Exception){}
            socket=null
            if(state!="FAILED")state="CLOSED"
        }
    }
}
