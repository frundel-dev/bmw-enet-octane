package com.example.bmwenettest

import android.app.*
import android.content.*
import android.net.*
import android.net.wifi.WifiManager
import android.os.*
import android.media.AudioManager
import android.media.ToneGenerator
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
        const val EXTRA_STATE = "state"
        const val EXTRA_RPM = "rpm"
        const val EXTRA_TRANSPORT = "transport"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_HZ = "hz"
        const val EXTRA_RECONNECTS = "reconnects"
        const val EXTRA_SUMMARY = "summary"
        const val EXTRA_FUEL_SCORE = "fuel_score"
        const val EXTRA_RUN_QUALITY = "run_quality"
        const val EXTRA_SESSION_SCORE = "session_score"
        const val EXTRA_SESSION_CONFIDENCE = "session_confidence"
        const val EXTRA_SESSION_RUNS = "session_runs"
        const val EXTRA_COOLANT = "coolant_c"
        const val EXTRA_OIL = "oil_c"
        const val EXTRA_LOAD = "load_pct"
        const val EXTRA_MAP = "map_kpa"
        const val EXTRA_IAT = "iat_c"
        const val EXTRA_VALID_POINTS = "valid_points"
        const val EXTRA_HIGH_POINTS = "high_points"
        const val EXTRA_KNOCK_EVENTS = "knock_events"
        const val EXTRA_SUPER_EVENTS = "super_events"
        const val EXTRA_RESULT_STATE = "result_state"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SEGMENTS = "segments"
        const val EXTRA_COVERAGE = "coverage"
        const val EXTRA_SPEED = "speed_kmh"
        const val EXTRA_FUEL_PCT = "fuel_level_pct"
        const val EXTRA_FUEL_ID = "fuel_session_id"
        const val EXTRA_PHASE = "driving_phase"
        const val EXTRA_STEADY_POINTS = "steady_points"
        const val EXTRA_ACCEL_POINTS = "accel_points"
        const val EXTRA_REFUEL = "refuel_event"
        const val EXTRA_MIXING_KM = "mixing_km"
        const val CHANNEL = "enet_logger"
    }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var socket: Socket? = null
    private var logFile: File? = null
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }

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
        .setContentTitle("BMW ENET Logger v1.7.11").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()

    private fun startLogger() {
        running=true
        startForeground(7,notification("Connecting…"))
        val pm=getSystemService(POWER_SERVICE) as PowerManager
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BmwEnet:Logger").apply{acquire()}
        val wifi=applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        wifiLock=wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"BmwEnet:VXSCAN")?.apply { setReferenceCounted(false); acquire() }
        logFile=createLogFile(getSharedPreferences("bmw_native",MODE_PRIVATE).getInt("fuel_session_id",1))
        executor.execute { loop() }
    }
    private fun createLogFile(fuelId:Int): File {
        val dir=getExternalFilesDir(null)?:filesDir
        val file=File(dir,"bmw_enet_v1711_fuel${fuelId}_${System.currentTimeMillis()}.csv")
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val profile=prefs.getString("prg_profile","DME8FF_R_EMBEDDED") ?: "DME8FF_R_EMBEDDED"
        val prgName=prefs.getString("prg_name","") ?: ""
        val prgSha=prefs.getString("prg_sha256","") ?: ""
        file.writeText("# app=v1.7.11,fuel_session_id=$fuelId,embedded_profile=DME8FF_R,prg_profile=$profile,prg_name=$prgName,prg_sha256=$prgSha,transport=AUTO_ETHERNET_WIFI\ntime_ms,rpm,load_pct,map_kpa_abs,iat_c,ign_advance_deg,coolant_c,throttle_pct,stft1_pct,lambda_eq,knock_status,superknock,knock_z1_vms,knock_z2_vms,knock_z3_vms,knock_z4_vms,ign_z1_deg,ign_z2_deg,ign_z3_deg,ign_z4_deg," + (0..19).joinToString(","){ "foctan_$it" } + ",test_window,transport,sample_hz,ecu_fuel_factor,ecu_ron_equiv,knock_mean_vms,ign_spread_deg,octane_confidence_pct,run_id,reconnects,measurement_window,high_confidence_window,baseline95_knock_vms,baseline95_samples,knock_ratio95,run_fuel_score,run_quality_pct,run_valid_points,session_fuel_score,session_confidence_pct,session_valid_runs,oil_temp_c,measurement_mode,coverage_cells,speed_kmh,fuel_level_pct,fuel_session_id,driving_phase,steady_points,accel_points,steady_score,accel_score,refuel_pending,mixing_km\n")
        return file
    }

    private fun stopLogger() {
        running=false
        try{socket?.close()}catch(_:Exception){}
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        running=false; try{socket?.close()}catch(_:Exception){}
        if(wakeLock?.isHeld==true) wakeLock?.release()
        if(wifiLock?.isHeld==true) wifiLock?.release()
        tone.release()
        super.onDestroy()
    }
    private fun emit(s:String, state:String?=null, rpm:Double?=null, transport:String?=null, runId:Int?=null, hz:Double?=null, reconnects:Int?=null, summary:String?=null, fuelScore:Double?=null, runQuality:Int?=null, sessionScore:Double?=null, sessionConfidence:Int?=null, sessionRuns:Int?=null, coolant:Double?=null, oil:Double?=null, load:Double?=null, map:Double?=null, iat:Double?=null, validPoints:Int?=null, highPoints:Int?=null, knockEvents:Int?=null, superEvents:Int?=null, resultState:String?=null, mode:String?=null, segments:Int?=null, coverage:Int?=null, speed:Double?=null,
        fuelPct:Double?=null, fuelId:Int?=null, phase:String?=null,
        steadyPoints:Int?=null, accelPoints:Int?=null, refuelNote:String?=null, mixingKm:Double?=null) {
        val intent=Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS,s)
        state?.let{intent.putExtra(EXTRA_STATE,it)}; rpm?.let{intent.putExtra(EXTRA_RPM,it)}; transport?.let{intent.putExtra(EXTRA_TRANSPORT,it)}
        runId?.let{intent.putExtra(EXTRA_RUN_ID,it)}; hz?.let{intent.putExtra(EXTRA_HZ,it)}; reconnects?.let{intent.putExtra(EXTRA_RECONNECTS,it)}; summary?.let{intent.putExtra(EXTRA_SUMMARY,it)}
        fuelScore?.let{intent.putExtra(EXTRA_FUEL_SCORE,it)}; runQuality?.let{intent.putExtra(EXTRA_RUN_QUALITY,it)}; sessionScore?.let{intent.putExtra(EXTRA_SESSION_SCORE,it)}; sessionConfidence?.let{intent.putExtra(EXTRA_SESSION_CONFIDENCE,it)}; sessionRuns?.let{intent.putExtra(EXTRA_SESSION_RUNS,it)}; coolant?.let{intent.putExtra(EXTRA_COOLANT,it)}; oil?.let{intent.putExtra(EXTRA_OIL,it)}; load?.let{intent.putExtra(EXTRA_LOAD,it)}; map?.let{intent.putExtra(EXTRA_MAP,it)}; iat?.let{intent.putExtra(EXTRA_IAT,it)}; validPoints?.let{intent.putExtra(EXTRA_VALID_POINTS,it)}; highPoints?.let{intent.putExtra(EXTRA_HIGH_POINTS,it)}; knockEvents?.let{intent.putExtra(EXTRA_KNOCK_EVENTS,it)}; superEvents?.let{intent.putExtra(EXTRA_SUPER_EVENTS,it)}; resultState?.let{intent.putExtra(EXTRA_RESULT_STATE,it)}; mode?.let{intent.putExtra(EXTRA_MODE,it)}; segments?.let{intent.putExtra(EXTRA_SEGMENTS,it)}; coverage?.let{intent.putExtra(EXTRA_COVERAGE,it)}; speed?.let{intent.putExtra(EXTRA_SPEED,it)}; fuelPct?.let{intent.putExtra(EXTRA_FUEL_PCT,it)}; fuelId?.let{intent.putExtra(EXTRA_FUEL_ID,it)}; phase?.let{intent.putExtra(EXTRA_PHASE,it)}; steadyPoints?.let{intent.putExtra(EXTRA_STEADY_POINTS,it)}; accelPoints?.let{intent.putExtra(EXTRA_ACCEL_POINTS,it)}; refuelNote?.let{intent.putExtra(EXTRA_REFUEL,it)}; mixingKm?.let{intent.putExtra(EXTRA_MIXING_KM,it)}
        sendBroadcast(intent)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7,notification(s.take(100)))
    }
    private fun loop() {
        val started=SystemClock.elapsedRealtime(); var samples=0; var reconnects=0
        val foctanCache=arrayOfNulls<Double>(20)
        var slowIat:Double?=null; var slowIgn:Double?=null; var slowCoolant:Double?=null
        var slowThrottle:Double?=null; var slowStft:Double?=null; var slowLambda:Double?=null
        var slowSuperKnock:Int?=null; var slowOil:Double?=null; var slowSpeed:Double?=null; var slowFuel:Double?=null; var lastFuelPoll=0L; var lastSlow=0L; var lastFoctan=0L
        val sampleTimes=java.util.ArrayDeque<Long>(); var validOctaneSamples=0
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val autoMode=prefs.getString("measurement_mode","AUTO")=="AUTO"
        val fuelDetector=FuelRefillDetector(prefs.getFloat("last_fuel_pct",Float.NaN).toDouble())
        var fuelSessionId=prefs.getInt("fuel_session_id",1)
        var mixingKm=prefs.getFloat("mixing_remaining_km",0f).toDouble()
        var lastDistanceSampleTime=0L
        var lastRefuelNote=""
        var fuelSupported=false
        var lastQualified=0L; var previousKnock=false; var previousSuper=false
        var captureActive=false; var captureTailUntil=0L; var runId=0; var armed2000=false; var signaled4500=false
        var runStart=0L; var runStartRpm=0.0; var runMaxRpm=0.0; var runMaxLoad=0.0; var runMaxMap=0.0; var runSamples=0; var lastSummary=""
        var runWeightedRatio=0.0; var runWeight=0.0; var runValidPoints=0; var runHighPoints=0; var runKnockEvents=0; var runSuperEvents=0
        var lastRunScore:Double?=null; var lastRunQuality=0
        var sessionScoreSum=0.0; var sessionQualitySum=0.0; var sessionValidRuns=0
        val sessionScores=mutableListOf<Double>(); val coverageBins=mutableSetOf<String>(); var sessionHighPoints=0
        var previousRpm:Double?=null; var previousMap:Double?=null; var previousSampleTime=0L
        var lastAutoAcceptedTime=0L; var autoQualifiedSegments=0; var autoAcceptedSamples=0
        val autoCoverage=mutableSetOf<String>()
        var autoRatioSum=0.0; var autoRatioWeight=0.0; var autoKnockEvents=0; var autoSuperEvents=0
        var steadyRatioSum=0.0; var steadyRatioWeight=0.0; var accelRatioSum=0.0; var accelRatioWeight=0.0
        var steadyPoints=0; var accelPoints=0
        val acceptedAutoSegments=mutableSetOf<String>(); val steadyCells=mutableSetOf<String>(); val accelCells=mutableSetOf<String>()
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
                    val broadcasts=ipv4Broadcasts(lpCandidate)
                    emit("DISCOVERY ${candidate.second}: UDP 6811 • ${broadcasts.joinToString()} • attempt ${reconnects+1}")
                    val found=discover(candidate.first,broadcasts)
                    if(found!=null) { net=candidate.first; d=found; transport=candidate.second; break }
                }
                val activeNet=net ?: throw IOException("BMW HSFZ discovery no reply on Ethernet/Wi-Fi")
                val gateway=d ?: throw IOException("BMW gateway not found")
                emit("GATEWAY $transport ${gateway.ip} • connecting TCP 6801")
                socket=activeNet.socketFactory.createSocket() as Socket
                socket!!.soTimeout=1800; socket!!.connect(InetSocketAddress(gateway.ip,6801),2500)
                sampleTimes.clear()
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
                        slowSpeed=obd(pid(0x0D),0x0D)?.firstOrNull()?.let{(it.toInt() and 255).toDouble()}
                        slowIat=obd(pid(0x0F),0x0F)?.let{((it[0].toInt()and 255)-40).toDouble()}
                        slowIgn=obd(pid(0x0E),0x0E)?.let{(it[0].toInt()and 255)/2.0-64.0}
                        slowCoolant=obd(pid(0x05),0x05)?.let{((it[0].toInt()and 255)-40).toDouble()}
                        slowThrottle=obd(pid(0x11),0x11)?.let{(it[0].toInt()and 255)*100.0/255.0}
                        slowStft=obd(pid(0x06),0x06)?.let{((it[0].toInt()and 255)-128)*100.0/128.0}
                        slowLambda=obd(pid(0x44),0x44)?.takeIf{it.size>=2}?.let{(((it[0].toInt()and 255)*256)+(it[1].toInt()and 255))*2.0/65535.0}
                        slowOil=obd(pid(0x5C),0x5C)?.firstOrNull()?.let{((it.toInt()and 255)-40).toDouble()}
                        slowSuperKnock=udsData(0x5728)?.firstOrNull()?.let{it.toInt() and 255}
                        lastSlow=now
                    }
                    // Tank level is a slow optional OBD parameter. No value means unsupported/unavailable.
                    if(now-lastFuelPoll>=15000L) {
                        slowFuel=obd(pid(0x2F),0x2F)?.firstOrNull()?.let { (it.toInt() and 255)*100.0/255.0 }
                        lastFuelPoll=now
                        if(slowFuel!=null) fuelSupported=true
                        val tankUpdate=fuelDetector.observe(slowFuel,now)
                        fuelDetector.stableLevel?.let { prefs.edit().putFloat("last_fuel_pct",it.toFloat()).apply() }
                        tankUpdate.refill?.let { refill ->
                            fuelSessionId++
                            mixingKm=5.0 // Heuristic minimum driving distance to allow partial mixing.
                            prefs.edit().putInt("fuel_session_id",fuelSessionId).putFloat("mixing_remaining_km",mixingKm.toFloat()).apply()
                            val events=File(getExternalFilesDir(null)?:filesDir,"bmw_fuel_events.csv")
                            events.appendText("${System.currentTimeMillis()},$fuelSessionId,${"%.1f".format(java.util.Locale.US,refill.fromPercent)},${"%.1f".format(java.util.Locale.US,refill.toPercent)},PENDING\n")
                            logFile=createLogFile(fuelSessionId)
                            captureActive=false; captureTailUntil=0L; runId=0; runSamples=0; runValidPoints=0; runHighPoints=0
                            runWeightedRatio=0.0; runWeight=0.0; lastRunScore=null; lastRunQuality=0
                            sessionScoreSum=0.0; sessionQualitySum=0.0; sessionValidRuns=0; sessionScores.clear()
                            coverageBins.clear(); sessionHighPoints=0; autoQualifiedSegments=0; autoAcceptedSamples=0
                            autoCoverage.clear(); autoRatioSum=0.0; autoRatioWeight=0.0; autoKnockEvents=0; autoSuperEvents=0
                            steadyRatioSum=0.0; steadyRatioWeight=0.0; accelRatioSum=0.0; accelRatioWeight=0.0
                            steadyPoints=0; accelPoints=0; steadyCells.clear(); accelCells.clear(); acceptedAutoSegments.clear()
                            lastAutoAcceptedTime=0L; lastQualified=0L; previousSampleTime=0L; previousRpm=null; previousMap=null
                            lastRefuelNote="Возможная заправка: ${"%.0f".format(refill.fromPercent)}% → ${"%.0f".format(refill.toPercent)}% • сессия #$fuelSessionId"
                            emit(lastRefuelNote)
                        }
                    }
                    if(lastDistanceSampleTime>0L && now-lastDistanceSampleTime in 1L..10000L && mixingKm>0.0 && slowSpeed!=null) {
                        val traveled=(slowSpeed!!*(now-lastDistanceSampleTime)/3600000.0).coerceAtLeast(0.0)
                        mixingKm=(mixingKm-traveled).coerceAtLeast(0.0)
                        prefs.edit().putFloat("mixing_remaining_km",mixingKm.toFloat()).apply()
                    }
                    lastDistanceSampleTime=now
                    if(prefs.getInt("refuel_rejected_id",-1)==fuelSessionId) {
                        mixingKm=0.0
                        prefs.edit().putFloat("mixing_remaining_km",0f).remove("refuel_rejected_id").apply()
                        lastRefuelNote="Событие заправки #$fuelSessionId отклонено. Файлы замеров сохранены раздельно."
                    }
                    val speed=slowSpeed; val i=slowIat; val a=slowIgn; val coolant=slowCoolant; val oil=slowOil; val throttle=slowThrottle; val stft1=slowStft; val lambdaEq=slowLambda
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
                    // v1.7.11 measurement assistant: keep polling continuously, persist only measurement segments.
                    if(((autoMode && r!=null && l!=null && m!=null && r>=2000.0 && r<=4500.0 && l>=55.0 && m>=130.0) || (!autoMode && r!=null && r>=1800.0)) && !captureActive) {
                        captureActive=true; runId++; armed2000=false; signaled4500=false; runStart=now; runStartRpm=r; runMaxRpm=r; runMaxLoad=l?:0.0; runMaxMap=m?:0.0; runSamples=0; lastSummary=""; runWeightedRatio=0.0; runWeight=0.0; runValidPoints=0; runHighPoints=0; runKnockEvents=0; runSuperEvents=0; lastRunScore=null; lastRunQuality=0
                        if(!autoMode) tone.startTone(ToneGenerator.TONE_PROP_BEEP,120)
                    }
                    if(!autoMode && captureActive && r!=null && r>=2000.0 && !armed2000) {
                        armed2000=true; tone.startTone(ToneGenerator.TONE_PROP_ACK,220)
                    }
                    if(!autoMode && captureActive && r!=null && r>=4500.0 && !signaled4500) {
                        signaled4500=true
                        tone.startTone(ToneGenerator.TONE_PROP_ACK,120)
                        Handler(Looper.getMainLooper()).postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_ACK,120) },180)
                    }
                    if(captureActive) {
                        if(r!=null) runMaxRpm=kotlin.math.max(runMaxRpm,r); if(l!=null) runMaxLoad=kotlin.math.max(runMaxLoad,l); if(m!=null) runMaxMap=kotlin.math.max(runMaxMap,m); runSamples++
                        if((autoMode && now-lastQualified>=3000L) || (!autoMode && r!=null && r<1800.0)) {
                            if(captureTailUntil==0L) captureTailUntil=now+3000L
                            if(now>=captureTailUntil) { val rr=if(runWeight>0.0) runWeightedRatio/runWeight else null; lastRunScore=rr?.let{(100.0+(1.0-it)*50.0-runKnockEvents.coerceAtLeast(1).minus(1)*1.5-runSuperEvents*10.0).coerceIn(0.0,120.0)}; lastRunQuality=((runHighPoints*4+runValidPoints*2).coerceAtMost(100)); if(lastRunScore!=null && lastRunQuality>=20 && (!autoMode || (runValidPoints>=4 && runMaxLoad>=55.0))){ if(autoMode) autoQualifiedSegments++;sessionScoreSum+=lastRunScore!!*lastRunQuality;sessionQualitySum+=lastRunQuality;sessionValidRuns++;sessionScores.add(lastRunScore!!)}; val ss=if(sessionQualitySum>0)sessionScoreSum/sessionQualitySum else null; if(!autoMode) { tone.startTone(ToneGenerator.TONE_PROP_ACK,450); Handler(Looper.getMainLooper()).postDelayed({ tone.startTone(ToneGenerator.TONE_PROP_ACK,450) },500) }; lastSummary="ЗАМЕР #$runId ЗАВЕРШЁН ✓\n${"%.0f".format(runStartRpm)} → ${"%.0f".format(runMaxRpm)} rpm • ${"%.1f".format((now-runStart)/1000.0)} s\nFuel score ${lastRunScore?.let{String.format(java.util.Locale.US,"%.1f",it)}?:"—"} • Quality $lastRunQuality% • valid $runValidPoints\nSession ${ss?.let{String.format(java.util.Locale.US,"%.1f",it)}?:"—"} • valid runs $sessionValidRuns"; captureActive=false; captureTailUntil=0L; armed2000=false; signaled4500=false }
                        } else captureTailUntil=0L
                    }
                    val t=SystemClock.elapsedRealtime()-started
                    sampleTimes.addLast(now); while(sampleTimes.size>30) sampleTimes.removeFirst()
                    val hz=if(sampleTimes.size>=2) (sampleTimes.size-1)*1000.0/(sampleTimes.last()-sampleTimes.first()).coerceAtLeast(1L) else 0.0
                    val fuelValues=foctanCache.filterNotNull()
                    val fuelFactor=if(fuelValues.isNotEmpty()) fuelValues.sorted()[fuelValues.size/2].coerceIn(0.0,1.0) else null
                    val ronEquiv:Double?=null // INFOFOCTAN kept raw; no unvalidated RON conversion
                    val knockValues=listOfNotNull(kz1,kz2,kz3,kz4)
                    val knockMean=knockValues.takeIf{it.size==4}?.average()
                    val ignValues=listOfNotNull(iz1,iz2,iz3,iz4)
                    val ignSpread=ignValues.takeIf{it.size==4}?.let{v->v.maxOrNull()!!-v.minOrNull()!!}
                    if(testWindow && knockMean!=null && ignSpread!=null) validOctaneSamples++
                    val confidence=(validOctaneSamples*2).coerceAtMost(100)
                    val measurementWindow=r!=null && l!=null && m!=null && r>=2000.0 && r<=4500.0 && l>=55.0 && m>=130.0
                    val dt=(now-previousSampleTime).coerceAtLeast(1L)/1000.0
                    val rpmRate=if(r!=null && previousRpm!=null && previousSampleTime>0L && dt<=5.0) (r-previousRpm!!)/dt else null
                    val mapRate=if(m!=null && previousMap!=null && previousSampleTime>0L && dt<=5.0) (m-previousMap!!)/dt else null
                    val warm=i!=null && i in 10.0..65.0 && coolant!=null && coolant>=65.0
                    val accelerationAuto=autoMode && measurementWindow && warm && rpmRate!=null && mapRate!=null &&
                        rpmRate>=130.0 && rpmRate<=1800.0 && mapRate>=-65.0 && l!=null && l>=65.0 &&
                        m!=null && m>=150.0 && (throttle==null || throttle>=25.0)
                    val stableAuto=autoMode && measurementWindow && warm && rpmRate!=null && mapRate!=null &&
                        kotlin.math.abs(rpmRate)<=350.0 && kotlin.math.abs(mapRate)<=24.0
                    val drivePhase=if(!autoMode) "TEST" else if(accelerationAuto) "ACCELERATION" else if(stableAuto) "STEADY" else "TRANSIENT"
                    previousRpm=r; previousMap=m; previousSampleTime=now
                    val highConfidenceWindow=r!=null && l!=null && m!=null && r>=2200.0 && r<=4000.0 && l>=70.0 && m>=160.0
                    val b95=if(measurementWindow) baseline95(r,m) else null
                    val ratio95=if(b95!=null && knockMean!=null && b95.v>0.0 && !fuelDetector.pending && mixingKm<=0.0 && (!autoMode || ((accelerationAuto || stableAuto) && b95.n>=5))) knockMean/b95.v else null
                    if(autoMode && measurementWindow && ratio95!=null) lastQualified=now
                    if(captureActive && measurementWindow && ratio95!=null && (!autoMode || now-lastAutoAcceptedTime>=1200L)) {
                        if(autoMode) {
                            lastAutoAcceptedTime=now; autoAcceptedSamples++
                            val w=if(highConfidenceWindow)2.0 else 1.0
                            autoRatioSum+=ratio95*w; autoRatioWeight+=w
                            if(drivePhase=="ACCELERATION") { accelRatioSum+=ratio95*w; accelRatioWeight+=w; accelPoints++ }
                            else { steadyRatioSum+=ratio95*w; steadyRatioWeight+=w; steadyPoints++ }
                            if(knockStatus==1 && !previousKnock)autoKnockEvents++
                            if(superKnock!=null && superKnock>0 && !previousSuper)autoSuperEvents++
                            acceptedAutoSegments.add("$runId:$drivePhase")
                        }
                        val weight=if(highConfidenceWindow) 2.0 else 1.0
                        val rv=r!!; val mv=m!!; val rb=((rv-2000.0)/500.0).toInt().coerceIn(0,4); val mb=when{mv<160->0;mv<180->1;mv<200->2;else->3}; coverageBins.add("$rb:$mb"); if(autoMode) {
                            autoCoverage.add("$rb:$mb")
                            if(drivePhase=="ACCELERATION") accelCells.add("$rb:$mb") else steadyCells.add("$rb:$mb")
                        }; if(highConfidenceWindow) sessionHighPoints++
                        runWeightedRatio+=ratio95*weight; runWeight+=weight; runValidPoints++; if(highConfidenceWindow) runHighPoints++
                        if(knockStatus==1 && !previousKnock) runKnockEvents++; if(superKnock!=null && superKnock>0 && !previousSuper) runSuperEvents++
                    }
                    previousKnock=knockStatus==1; previousSuper=superKnock!=null && superKnock>0
                    val liveRunRatio=if(runWeight>0.0) runWeightedRatio/runWeight else null
                    val liveRunScore=liveRunRatio?.let{(100.0+(1.0-it)*50.0-(runKnockEvents-1).coerceAtLeast(0)*1.5-runSuperEvents*10.0).coerceIn(0.0,120.0)}
                    val liveRunQuality=((runHighPoints*4+runValidPoints*2).coerceAtMost(100))
                    val sessionScore=if(autoMode) { if(autoRatioWeight>0.0) (100.0+(1.0-autoRatioSum/autoRatioWeight)*50.0-(autoKnockEvents-1).coerceAtLeast(0)*1.5-autoSuperEvents*10.0).coerceIn(0.0,120.0) else null } else if(sessionQualitySum>0) sessionScoreSum/sessionQualitySum else null
                    val consistency=if(sessionScores.size>=2){val mean=sessionScores.average(); val sd=kotlin.math.sqrt(sessionScores.sumOf{(it-mean)*(it-mean)}/sessionScores.size); (15.0-sd*3.0).toInt().coerceIn(0,15)}else 0
                    val sessionConfidence=if(autoMode) {
                        val rawConfidence=(acceptedAutoSegments.size*6).coerceAtMost(30)+
                            ((steadyCells.size+accelCells.size)*4).coerceAtMost(28)+
                            (autoAcceptedSamples/2).coerceAtMost(24)+
                            (if(steadyPoints>0 && accelPoints>0)8 else 0)
                        if(autoAcceptedSamples<12 || acceptedAutoSegments.size<3 || autoCoverage.size<3) rawConfidence.coerceAtMost(49)
                        else rawConfidence.coerceAtMost(90)
                    } else ((sessionValidRuns*8).coerceAtMost(40)+(sessionHighPoints*2).coerceAtMost(25)+(coverageBins.size*2).coerceAtMost(20)+consistency).coerceAtMost(100)
                    val steadyScore=if(steadyRatioWeight>0.0) (100.0+(1.0-steadyRatioSum/steadyRatioWeight)*50.0).coerceIn(0.0,120.0) else null
                    val accelScore=if(accelRatioWeight>0.0) (100.0+(1.0-accelRatioSum/accelRatioWeight)*50.0).coerceIn(0.0,120.0) else null
                    val resultState=when { mixingKm>0.0 -> "MIXING"; fuelDetector.pending -> "REFUEL CHECK"; sessionConfidence<60 -> "COLLECTING"; sessionScore==null -> "COLLECTING"; sessionScore>=95.0 -> "NORMAL"; sessionScore>=92.0 -> "BORDERLINE"; else -> "BELOW BASELINE" }
                    if(captureActive) FileOutputStream(logFile!!,true).bufferedWriter().use{w-> w.appendLine((listOf(t,r?:"",l?:"",m?:"",i?:"",a?:"",coolant?:"",throttle?:"",stft1?:"",lambdaEq?:"",knockStatus?:"",superKnock?:"",kz1?:"",kz2?:"",kz3?:"",kz4?:"",iz1?:"",iz2?:"",iz3?:"",iz4?:"") + foctanCache.map{it?:""} + listOf(if(testWindow)1 else 0,transport,"%.3f".format(java.util.Locale.US,hz),fuelFactor?:"",ronEquiv?:"",knockMean?:"",ignSpread?:"",confidence,runId,reconnects,if(measurementWindow)1 else 0,if(highConfidenceWindow)1 else 0,b95?.v?:"",b95?.n?:"",ratio95?:"",liveRunScore?:"",liveRunQuality,runValidPoints,sessionScore?:"",sessionConfidence,sessionValidRuns,oil?:"",if(autoMode)"AUTO" else "TEST",coverageBins.size,speed?:"",slowFuel?:"",fuelSessionId,drivePhase,steadyPoints,accelPoints,steadyScore?:"",accelScore?:"",if(fuelDetector.pending)1 else 0,"%.2f".format(java.util.Locale.US,mixingKm))).joinToString(","))}
                    samples++
                    if(samples%4==0) { val state=if(autoMode) { if(captureActive) "AUTO • УЧАСТОК #$runId" else "AUTO • ПОИСК УЧАСТКА" } else if(captureActive) { if(signaled4500) "ЗАВЕРШЕНИЕ #$runId" else if(armed2000) "ЗАМЕР #$runId" else "ГОТОВ #$runId" } else if(lastSummary.isNotEmpty()) "ЗАВЕРШЁН #$runId" else "ОЖИДАНИЕ"; emit("v1.7.11 • $state • $transport • ${"%.1f".format(java.util.Locale.US,hz)} Hz • Fuel ${liveRunScore?.let{String.format(java.util.Locale.US,"%.0f",it)}?:"—"} Q$liveRunQuality%",state,r,transport,runId,hz,reconnects,lastSummary.takeIf{it.isNotEmpty()},liveRunScore,liveRunQuality,sessionScore,sessionConfidence,sessionValidRuns,coolant,oil,l,m,i,runValidPoints,runHighPoints,runKnockEvents,runSuperEvents,resultState,if(autoMode)"AUTO" else "TEST",if(autoMode)acceptedAutoSegments.size else sessionValidRuns,coverageBins.size,speed,slowFuel,fuelSessionId,drivePhase,steadyPoints,accelPoints,lastRefuelNote.takeIf{it.isNotEmpty()},mixingKm) }
                }
            } catch(e:Exception) {
                if(!running) break
                reconnects++; sampleTimes.clear(); tone.startTone(ToneGenerator.TONE_SUP_ERROR,600); emit("ENET reconnect #$reconnects: ${e.javaClass.simpleName}: ${e.message ?: "no details"}")
                try{socket?.close()}catch(_:Exception){}; socket=null
                SystemClock.sleep(1500)
            }
        }
    }
    // Octane Engine v0.2: empirical AI-95 baseline from validated v1.7.2 + independent v1.7.3 AI-95 runs.
    // RPM x MAP bins: median knock_mean_vms and number of baseline samples.
    private data class B95(val v:Double,val n:Int)
    private fun baseline95(rpm:Double?, map:Double?):B95? {
        if(rpm==null || map==null) return null
        val ri=when { rpm<2000->return null; rpm<2500->0; rpm<3000->1; rpm<3500->2; rpm<4000->3; rpm<4500->4; else->5 }
        val mi=when { map<130->return null; map<160->0; map<180->1; map<200->2; else->3 }
        val v=arrayOf(
            arrayOf<B95?>(B95(.073798,12),B95(.070848,1),B95(.069070,2),B95(.071781,2)),
            arrayOf<B95?>(B95(.074039,19),B95(.071964,10),B95(.074590,5),B95(.081251,9)),
            arrayOf<B95?>(B95(.076440,10),B95(.082133,4),B95(.082402,6),B95(.093991,7)),
            arrayOf<B95?>(B95(.079952,8),B95(.086841,3),B95(.097288,2),B95(.097531,10)),
            arrayOf<B95?>(B95(.085714,9),B95(.095348,3),B95(.104305,2),B95(.114581,9)),
            arrayOf<B95?>(null,null,null,null)
        )
        return v[ri][mi]
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
        val s=DatagramSocket(null); n.bindSocket(s); s.broadcast=true;s.soTimeout=2500;s.bind(InetSocketAddress(0))
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
