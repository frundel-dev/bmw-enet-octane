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
        .setContentTitle("BMW ENET Logger v1.7").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()

    private fun startLogger() {
        running=true
        startForeground(7,notification("Connecting…"))
        val pm=getSystemService(POWER_SERVICE) as PowerManager
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BmwEnet:Logger").apply{acquire()}
        val dir=getExternalFilesDir(null)?:filesDir
        logFile=File(dir,"bmw_enet_v17_${System.currentTimeMillis()}.csv")
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val profile=prefs.getString("prg_profile","DME8FF_R_EMBEDDED") ?: "DME8FF_R_EMBEDDED"
        val prgName=prefs.getString("prg_name","") ?: ""
        val prgSha=prefs.getString("prg_sha256","") ?: ""
        logFile!!.writeText("# app=v1.7,embedded_profile=DME8FF_R,prg_profile=$profile,prg_name=$prgName,prg_sha256=$prgSha,transport=AUTO_ETHERNET_WIFI\ntime_ms,rpm,load_pct,map_kpa_abs,iat_c,ign_advance_deg,coolant_c,throttle_pct,stft1_pct,lambda_eq,knock_status,superknock,knock_z1_vms,knock_z2_vms,knock_z3_vms,knock_z4_vms,ign_z1_deg,ign_z2_deg,ign_z3_deg,ign_z4_deg," + (0..19).joinToString(","){ "foctan_$it" } + ",test_window,transport,sample_hz\n")
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
        val foctanCache=arrayOfNulls<Double>(20)
        var slowIat:Double?=null; var slowIgn:Double?=null; var slowCoolant:Double?=null
        var slowThrottle:Double?=null; var slowStft:Double?=null; var slowLambda:Double?=null
        var slowSuperKnock:Int?=null; var lastSlow=0L; var lastFoctan=0L\n        val sampleTimes=java.util.ArrayDeque<Long>(); var validOctaneSamples=0
        while(running) {
            try {
                val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val candidates=cm.allNetworks.mapNotNull { n ->
                    val caps=cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                    when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Triple(n,"ETHERNET",0)
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Triple(n,"WIFI",1)
                        else -> null
                    }
                }.sortedBy{it.third}
                if(candidates.isEmpty()) throw IOException("No Ethernet/Wi-Fi ENET network")
                var net:Network?=null; var d:D?=null; var transport=""
                for(candidate in candidates) {
                    val lpCandidate=cm.getLinkProperties(candidate.first)
                    val found=discover(candidate.first,ipv4Broadcasts(lpCandidate))
                    if(found!=null) { net=candidate.first; d=found; transport=candidate.second; break }
                }
                val activeNet=net ?: throw IOException("BMW HSFZ discovery no reply on Ethernet/Wi-Fi")
                val gateway=d ?: throw IOException("BMW gateway not found")
                socket=activeNet.socketFactory.createSocket() as Socket
                socket!!.soTimeout=1800; socket!!.connect(InetSocketAddress(gateway.ip,6801),2500)
                emit("CONNECTED $transport ${gateway.ip}:6801 • reconnects $reconnects")
                while(running && socket?.isClosed==false) {
                    fun pid(id:Int):ByteArray? {
                        val q=hsfz(byteArrayOf(0x01,id.toByte())); socket!!.getOutputStream().write(q); socket!!.getOutputStream().flush()
                        return readFrames(socket!!.getInputStream()).firstNotNullOfOrNull{payload(it)}
                    }
                    fun obd(p:ByteArray?,id:Int)=p?.let{decodeObd(it,id)}
                    val r=obd(pid(0x0C),0x0C)?.let{(((it[0].toInt()and 255)*256)+(it[1].toInt()and 255))/4.0}
                    val l=obd(pid(0x04),0x04)?.let{(it[0].toInt()and 255)*100.0/255.0}
                    val m=obd(pid(0x0B),0x0B)?.let{(it[0].toInt()and 255).toDouble()}
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastSlow>=2000L) {
                        slowIat=obd(pid(0x0F),0x0F)?.let{((it[0].toInt()and 255)-40).toDouble()}
                        slowIgn=obd(pid(0x0E),0x0E)?.let{(it[0].toInt()and 255)/2.0-64.0}
                        slowCoolant=obd(pid(0x05),0x05)?.let{((it[0].toInt()and 255)-40).toDouble()}
                        slowThrottle=obd(pid(0x11),0x11)?.let{(it[0].toInt()and 255)*100.0/255.0}
                        slowStft=obd(pid(0x06),0x06)?.let{((it[0].toInt()and 255)-128)*100.0/128.0}
                        slowLambda=obd(pid(0x44),0x44)?.takeIf{it.size>=2}?.let{(((it[0].toInt()and 255)*256)+(it[1].toInt()and 255))*2.0/65535.0}
                        slowSuperKnock=udsData(0x5728)?.firstOrNull()?.let{it.toInt() and 255}
                        lastSlow=now
                    }
                    val i=slowIat; val a=slowIgn; val coolant=slowCoolant; val throttle=slowThrottle; val stft1=slowStft; val lambdaEq=slowLambda
                    val knockStatus=udsData(0x4A36)?.firstOrNull()?.let{it.toInt() and 255}
                    fun knock(did:Int)=udsData(did)?.takeIf{it.size>=4}?.let{
                        val raw=((it[0].toLong()and 255) shl 24) or ((it[1].toLong()and 255) shl 16) or ((it[2].toLong()and 255) shl 8) or (it[3].toLong()and 255)
                        raw*0.05/65536.0
                    }
                    fun ign(did:Int)=udsData(did)?.takeIf{it.size>=2}?.let{
                        val u=((it[0].toInt()and 255) shl 8) or (it[1].toInt()and 255); val signed=if(u>=0x8000)u-0x10000 else u; signed/10.0
                    }
                    val kz1=knock(0x4A37); val kz2=knock(0x4A38); val kz3=knock(0x4A39); val kz4=knock(0x4A3A)
                    val iz1=ign(0x4A49); val iz2=ign(0x4A4A); val iz3=ign(0x4A4C); val iz4=ign(0x4A4D)
                    val superKnock=slowSuperKnock
                    if(now-lastFoctan>=10000L) {
                        udsData(0x407F)?.let{data-> if(data.size>=220) for(x in 0..19) foctanCache[x]=(data[200+x].toInt()and 255)/256.0 }
                        lastFoctan=now
                    }
                                        val testWindow = r!=null && l!=null && m!=null && r>=2000.0 && l>=70.0 && m>=140.0
                    val t=SystemClock.elapsedRealtime()-started
                    sampleTimes.addLast(now); while(sampleTimes.size>30) sampleTimes.removeFirst()
                    val hz=if(sampleTimes.size>=2) (sampleTimes.size-1)*1000.0/(sampleTimes.last()-sampleTimes.first()).coerceAtLeast(1L) else 0.0
                    val fuelValues=foctanCache.filterNotNull()
                    val fuelFactor=if(fuelValues.isNotEmpty()) fuelValues.sorted()[fuelValues.size/2].coerceIn(0.0,1.0) else null
                    val ronEquiv=fuelFactor?.let{98.0-7.0*it}
                    val knockValues=listOfNotNull(kz1,kz2,kz3,kz4)
                    val knockMean=knockValues.takeIf{it.size==4}?.average()
                    val ignValues=listOfNotNull(iz1,iz2,iz3,iz4)
                    val ignSpread=ignValues.takeIf{it.size==4}?.let{v->v.maxOrNull()!!-v.minOrNull()!!}
                    if(testWindow && knockMean!=null && ignSpread!=null) validOctaneSamples++
                    val confidence=(validOctaneSamples*2).coerceAtMost(100)
                    FileOutputStream(logFile!!,true).bufferedWriter().use{w-> w.appendLine((listOf(t,r?:"",l?:"",m?:"",i?:"",a?:"",coolant?:"",throttle?:"",stft1?:"",lambdaEq?:"",knockStatus?:"",superKnock?:"",kz1?:"",kz2?:"",kz3?:"",kz4?:"",iz1?:"",iz2?:"",iz3?:"",iz4?:"") + foctanCache.map{it?:""} + listOf(if(testWindow)1 else 0,transport,"%.3f".format(java.util.Locale.US,hz),fuelFactor?:"",ronEquiv?:"",knockMean?:"",ignSpread?:"",confidence)).joinToString(","))}
                    samples++
                    if(samples%10==0) emit("Octane v0.1 • $transport • ${"%.1f".format(java.util.Locale.US,hz)} Hz • RONeq ${ronEquiv?.let{"%.1f".format(java.util.Locale.US,it)}?:"—"} • confidence $confidence% • test ${if(testWindow)"ACTIVE" else "—"}")
                }
            } catch(e:Exception) {
                if(!running) break
                reconnects++; emit("ENET reconnect #$reconnects: ${e.javaClass.simpleName}")
                try{socket?.close()}catch(_:Exception){}; socket=null
                SystemClock.sleep(1500)
            }
        }
    }
    private fun udsData(did:Int):ByteArray? {
        val s=socket ?: return null
        val hi=((did ushr 8) and 255).toByte(); val lo=(did and 255).toByte()
        val q=hsfz(byteArrayOf(0x22,hi,lo))
        s.getOutputStream().write(q); s.getOutputStream().flush()
        val p=readFrames(s.getInputStream()).mapNotNull{payload(it)}.firstOrNull{
            it.size>=3 && it[0]==0x62.toByte() && it[1]==hi && it[2]==lo
        } ?: return null
        return p.copyOfRange(3,p.size)
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
