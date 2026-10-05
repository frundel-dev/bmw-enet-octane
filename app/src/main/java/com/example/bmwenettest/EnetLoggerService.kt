package com.example.bmwenettest

import android.app.*
import android.content.*
import android.net.*
import android.os.*
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class EnetLoggerService : Service() {
    companion object {
        const val ACTION_START = "enet.START"
        const val ACTION_STOP = "enet.STOP"
        const val ACTION_STATUS = "enet.STATUS"
        const val EXTRA_STATUS = "status"
        const val CHANNEL = "enet_logger"
    }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var socket: Socket? = null
    private var logFile: File? = null

    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate()
        val nm=getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL,"ENET logger",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags:Int, startId:Int):Int {
        when(intent?.action) {
            ACTION_STOP -> stopLogger()
            else -> if(!running) startLogger()
        }
        return START_STICKY
    }
    private fun notification(text:String)=Notification.Builder(this,CHANNEL)
        .setContentTitle("BMW ENET Logger v0.7").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()

    private fun startLogger() {
        running=true
        startForeground(7,notification("Connecting…"))
        val pm=getSystemService(POWER_SERVICE) as PowerManager
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BmwEnet:Logger").apply{acquire()}
        val dir=getExternalFilesDir(null)?:filesDir
        logFile=File(dir,"bmw_enet_v07_${System.currentTimeMillis()}.csv")
        logFile!!.writeText("time_ms,rpm,load_pct,map_kpa_abs,iat_c,ign_advance_deg\n")
        executor.execute { loop() }
    }
    private fun stopLogger() {
        running=false
        try{socket?.close()}catch(_:Exception){}
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        running=false; try{socket?.close()}catch(_:Exception){}
        if(wakeLock?.isHeld==true) wakeLock?.release()
        super.onDestroy()
    }
    private fun emit(s:String) {
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS,s))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7,notification(s.take(100)))
    }
    private fun loop() {
        val started=SystemClock.elapsedRealtime(); var samples=0; var reconnects=0
        while(running) {
            try {
                val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val net=cm.allNetworks.firstOrNull{cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)==true}
                    ?: throw IOException("Ethernet unavailable")
                val lp=cm.getLinkProperties(net)
                val d=discover(net,ipv4Broadcasts(lp))?:throw IOException("BMW discovery no reply")
                socket=net.socketFactory.createSocket() as Socket
                socket!!.soTimeout=1800; socket!!.connect(InetSocketAddress(d.ip,6801),2500)
                emit("CONNECTED ${d.ip}:6801 • reconnects $reconnects")
                while(running && socket?.isClosed==false) {
                    fun pid(id:Int):ByteArray? {
                        val q=hsfz(byteArrayOf(0x01,id.toByte())); socket!!.getOutputStream().write(q); socket!!.getOutputStream().flush()
                        return readFrames(socket!!.getInputStream()).firstNotNullOfOrNull{payload(it)}
                    }
                    fun obd(p:ByteArray?,id:Int)=p?.let{decodeObd(it,id)}
                    val r=obd(pid(0x0C),0x0C)?.let{(((it[0].toInt()and 255)*256)+(it[1].toInt()and 255))/4.0}
                    val l=obd(pid(0x04),0x04)?.let{(it[0].toInt()and 255)*100.0/255.0}
                    val m=obd(pid(0x0B),0x0B)?.let{(it[0].toInt()and 255).toDouble()}
                    val i=obd(pid(0x0F),0x0F)?.let{((it[0].toInt()and 255)-40).toDouble()}
                    val a=obd(pid(0x0E),0x0E)?.let{(it[0].toInt()and 255)/2.0-64.0}
                    val t=SystemClock.elapsedRealtime()-started
                    FileOutputStream(logFile!!,true).bufferedWriter().use{it.appendLine(listOf(t,r?:"",l?:"",m?:"",i?:"",a?:"").joinToString(","))}
                    samples++
                    if(samples%10==0) emit("Logging • $samples samples • RPM ${r?.toInt()?:"—"} • reconnects $reconnects")
                }
            } catch(e:Exception) {
                if(!running) break
                reconnects++; emit("ENET reconnect #$reconnects: ${e.javaClass.simpleName}")
                try{socket?.close()}catch(_:Exception){}; socket=null
                SystemClock.sleep(1500)
            }
        }
    }
    data class D(val ip:String)
    private fun discover(n:Network,bs:List<String>):D? {
        val s=DatagramSocket(null); n.bindSocket(s); s.broadcast=true;s.soTimeout=1800;s.bind(InetSocketAddress(0))
        val q=byteArrayOf(0,0,0,0,0,0x11); bs.distinct().forEach{try{s.send(DatagramPacket(q,q.size,InetAddress.getByName(it),6811))}catch(_:Exception){}}
        return try{val b=ByteArray(512);val p=DatagramPacket(b,b.size);s.receive(p);D(p.address.hostAddress?:"")}catch(_:Exception){null}finally{s.close()}
    }
    private fun ipv4Broadcasts(lp:LinkProperties?)=lp?.linkAddresses?.mapNotNull{la->
        val a=la.address as? Inet4Address?:return@mapNotNull null; val p=la.prefixLength
        var ip=0L;for(b in a.address)ip=(ip shl 8)or(b.toInt()and 255).toLong()
        val mask=if(p==0)0L else(0xFFFFFFFFL shl(32-p))and 0xFFFFFFFFL;val x=(ip and mask)or(mask.inv()and 0xFFFFFFFFL)
        listOf((x shr 24)and 255,(x shr 16)and 255,(x shr 8)and 255,x and 255).joinToString(".")
    }?:emptyList()
    private fun hsfz(u:ByteArray):ByteArray{val l=2+u.size;return byteArrayOf((l ushr 24).toByte(),(l ushr 16).toByte(),(l ushr 8).toByte(),l.toByte(),0,1,0xF4.toByte(),0x12)+u}
    private fun exact(i:InputStream,n:Int):ByteArray?{val b=ByteArray(n);var o=0;while(o<n){val r=i.read(b,o,n-o);if(r<0)return null;o+=r};return b}
    private fun readFrames(i:InputStream):List<ByteArray>{val r=mutableListOf<ByteArray>();repeat(2){try{val h=exact(i,6)?:return r;val l=((h[0].toInt()and 255)shl 24)or((h[1].toInt()and 255)shl 16)or((h[2].toInt()and 255)shl 8)or(h[3].toInt()and 255);val b=exact(i,l)?:return r;r+=h+b;if(h[4].toInt()==0&&h[5].toInt()==1)return r}catch(_:SocketTimeoutException){return r}};return r}
    private fun payload(f:ByteArray)=if(f.size>=8&&f[4].toInt()==0&&f[5].toInt()==1)f.copyOfRange(8,f.size)else null
    private fun decodeObd(p:ByteArray,id:Int):ByteArray?{for(i in 0 until p.size-1)if(p[i]==0x41.toByte()&&(p[i+1].toInt()and 255)==id)return p.copyOfRange(i+2,p.size);return null}
}
