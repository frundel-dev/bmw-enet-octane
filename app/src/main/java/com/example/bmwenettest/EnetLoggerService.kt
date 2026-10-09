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
        const val EXTRA_STEADY_CONF = "steady_confidence_pct"
        const val EXTRA_ACCEL_CONF = "accel_confidence_pct"
        const val EXTRA_STEADY_SEGMENTS = "steady_segments"
        const val EXTRA_ACCEL_SEGMENTS = "accel_segments"
        const val EXTRA_STEADY_SCORE = "steady_score"
        const val EXTRA_ACCEL_SCORE = "accel_score"
        const val EXTRA_HIGH_RPM_RATIO = "high_rpm_ratio95_mean"
        const val EXTRA_HIGH_RPM_POINTS = "high_rpm_points"
        const val EXTRA_HIGH_RPM_OVER120 = "high_rpm_over120_count"
        const val EXTRA_STEADY_SURVEY = "steady_survey_points"
        const val EXTRA_STEADY_CELLS = "steady_survey_cells"
        const val EXTRA_ACCEL_V08_SCORE = "accel_v08_score"
        const val EXTRA_ACCEL_V08_POINTS = "accel_v08_points"
        const val EXTRA_CALIBRATION = "calibration_model"
        const val EXTRA_STFT = "stft1_pct"
        const val EXTRA_LTFT = "ltft1_pct"
        const val EXTRA_COMBINED_TRIM = "combined_trim_pct"
        const val EXTRA_IGN_SPREAD = "ignition_spread_deg"
        const val EXTRA_FINE_STEADY_CELLS = "fine_steady_cells"
        const val EXTRA_REPEATABLE_STEADY_CELLS = "repeatable_steady_cells"
        const val CHANNEL = "enet_logger"
    }
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var androidNetworkMonitor: AndroidNetworkEventMonitor? = null
    @Volatile private var vxscanWifiBlocked = false
    private var telemetryFile: File? = null
    private var steadySurveyFile: File? = null
    private val connectionLogLock = Any()
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
        .setContentTitle("BMW ENET Logger v1.7.19").setContentText(text)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setOngoing(true).build()

    private fun startLogger() {
        running=true
        startForeground(7,notification("Connecting…"))
        val pm=getSystemService(POWER_SERVICE) as PowerManager
        wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"BmwEnet:Logger").apply{acquire()}
        val wifi=applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
        wifiLock=wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"BmwEnet:VXSCAN")?.apply { setReferenceCounted(false); acquire() }
        logFile=createLogFile(getSharedPreferences("bmw_native",MODE_PRIVATE).getInt("fuel_session_id",1))
        telemetryFile=File(getExternalFilesDir(null)?:filesDir,"bmw_telemetry_v1719_${System.currentTimeMillis()}.csv").apply {
            writeText("wall_time_ms,elapsed_ms,event,transport,rpm,load_pct,map_kpa_abs,speed_kmh,coolant_c,fuel_level_pct,fuel_session_id,run_id,reconnects,phase,stft1_pct,ltft1_pct,ign_advance_deg\n")
        }
        val connectivity=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        androidNetworkMonitor=AndroidNetworkEventMonitor(connectivity) { event,transport,detail ->
            if(event=="BLOCKED" && transport=="WIFI") {
                vxscanWifiBlocked=detail.contains("blocked=true")
            }
            connectionEvent("ANDROID_$event",0,"ANDROID",transport,null,detail)
        }.also { it.start() }
        steadySurveyFile=createSteadySurveyFile(
            getSharedPreferences("bmw_native",MODE_PRIVATE).getInt("fuel_session_id",1))
        executor.execute { loop() }
    }
    private fun createSteadySurveyFile(fuelId:Int):File {
        val dir=getExternalFilesDir(null)?:filesDir
        return File(dir,"bmw_steady_v1719_fuel${fuelId}_${System.currentTimeMillis()}.csv").apply {
            writeText("# calibration=${OctaneCalibration.VERSION},fuel_session=$fuelId,observational_only=true\n"+
                "wall_time_ms,elapsed_ms,rpm,load_pct,map_kpa_abs,rpm_per_s,map_kpa_per_s,"+
                "knock_mean_vms,iat_c,coolant_c,speed_kmh,reference_vms,reference_samples,"+
                "reference_trips,cell,segment_id,steady_filter_pass,accepted_to_score,"+
                "fine_cell,cell_samples,cell_segments,cell_median_vms,cell_mad_pct,"+
                "trim_comparable,stft1_pct,ltft1_pct,combined_trim_pct,ign_advance_deg,"+
                "ign_spread_deg,knock_retard_deg,injection_pulse_ms\n")
        }
    }

    private fun createLogFile(fuelId:Int): File {
        val dir=getExternalFilesDir(null)?:filesDir
        val file=File(dir,"bmw_enet_v1719_fuel${fuelId}_${System.currentTimeMillis()}.csv")
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val profile=prefs.getString("prg_profile","DME8FF_R_EMBEDDED") ?: "DME8FF_R_EMBEDDED"
        val prgName=prefs.getString("prg_name","") ?: ""
        val prgSha=prefs.getString("prg_sha256","") ?: ""
        file.writeText("# app=v1.7.19,fuel_session_id=$fuelId,embedded_profile=DME8FF_R,prg_profile=$profile,prg_name=$prgName,prg_sha256=$prgSha,transport=AUTO_ETHERNET_WIFI\ntime_ms,rpm,load_pct,map_kpa_abs,iat_c,ign_advance_deg,coolant_c,throttle_pct,stft1_pct,lambda_eq,knock_status,superknock,knock_z1_vms,knock_z2_vms,knock_z3_vms,knock_z4_vms,ign_z1_deg,ign_z2_deg,ign_z3_deg,ign_z4_deg," + (0..19).joinToString(","){ "foctan_$it" } + ",test_window,transport,sample_hz,ecu_fuel_factor,ecu_ron_equiv,knock_mean_vms,ign_spread_deg,octane_confidence_pct,run_id,reconnects,measurement_window,high_confidence_window,baseline95_knock_vms,baseline95_samples,knock_ratio95,run_fuel_score,run_quality_pct,run_valid_points,session_fuel_score,session_confidence_pct,session_valid_runs,oil_temp_c,measurement_mode,coverage_cells,speed_kmh,fuel_level_pct,fuel_session_id,driving_phase,steady_points,accel_points,steady_score,accel_score,refuel_pending,mixing_km,steady_confidence_pct,accel_confidence_pct,steady_segments,accel_segments,high_rpm_ratio95_mean,high_rpm_points,high_rpm_over120_count,high_rpm_baseline_support_avg,auto_phase_segments,steady_survey_points,steady_survey_cells,accel_v08_score,accel_v08_points,calibration_model,steady_reference_vms,accel_reference_vms,"+
            "ltft1_pct,combined_trim_pct,ign_advance_deg,"+
            "steady_fine_cell,steady_fine_cells,steady_repeatable_cells,"+
            "steady_cell_samples,steady_cell_segments,steady_cell_median_vms,steady_cell_mad_pct,"+
            "knock_retard_deg,injection_pulse_ms\n")
        return file
    }

    private fun stopLogger() {
        running=false
        androidNetworkMonitor?.stop(); androidNetworkMonitor=null
        try{socket?.close()}catch(_:Exception){}
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
    override fun onDestroy() {
        androidNetworkMonitor?.stop(); androidNetworkMonitor=null
        running=false; try{socket?.close()}catch(_:Exception){}
        if(wakeLock?.isHeld==true) wakeLock?.release()
        if(wifiLock?.isHeld==true) wifiLock?.release()
        tone.release()
        super.onDestroy()
    }
    private fun emit(s:String, state:String?=null, rpm:Double?=null, transport:String?=null, runId:Int?=null, hz:Double?=null, reconnects:Int?=null, summary:String?=null, fuelScore:Double?=null, runQuality:Int?=null, sessionScore:Double?=null, sessionConfidence:Int?=null, sessionRuns:Int?=null, coolant:Double?=null, oil:Double?=null, load:Double?=null, map:Double?=null, iat:Double?=null, validPoints:Int?=null, highPoints:Int?=null, knockEvents:Int?=null, superEvents:Int?=null, resultState:String?=null, mode:String?=null, segments:Int?=null, coverage:Int?=null, speed:Double?=null,
        fuelPct:Double?=null, fuelId:Int?=null, phase:String?=null,
        steadyPoints:Int?=null, accelPoints:Int?=null, refuelNote:String?=null, mixingKm:Double?=null,steadyConf:Int?=null,accelConf:Int?=null,
        steadySegments:Int?=null,accelSegments:Int?=null,steadyScore:Double?=null,accelScore:Double?=null,
        highRpmMean:Double?=null,highRpmPoints:Int?=null,highRpmOver120:Int?=null,
        steadySurveyPoints:Int?=null,steadySurveyCells:Int?=null,
        accelV08Score:Double?=null,accelV08Points:Int?=null,
        stft:Double?=null,ltft:Double?=null,combinedTrim:Double?=null,
        ignitionSpread:Double?=null,fineCells:Int?=null,repeatableCells:Int?=null) {
        val intent=Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATUS,s)
        state?.let{intent.putExtra(EXTRA_STATE,it)}; rpm?.let{intent.putExtra(EXTRA_RPM,it)}; transport?.let{intent.putExtra(EXTRA_TRANSPORT,it)}
        runId?.let{intent.putExtra(EXTRA_RUN_ID,it)}; hz?.let{intent.putExtra(EXTRA_HZ,it)}; reconnects?.let{intent.putExtra(EXTRA_RECONNECTS,it)}; summary?.let{intent.putExtra(EXTRA_SUMMARY,it)}
        fuelScore?.let{intent.putExtra(EXTRA_FUEL_SCORE,it)}; runQuality?.let{intent.putExtra(EXTRA_RUN_QUALITY,it)}; sessionScore?.let{intent.putExtra(EXTRA_SESSION_SCORE,it)}; sessionConfidence?.let{intent.putExtra(EXTRA_SESSION_CONFIDENCE,it)}; sessionRuns?.let{intent.putExtra(EXTRA_SESSION_RUNS,it)}; coolant?.let{intent.putExtra(EXTRA_COOLANT,it)}; oil?.let{intent.putExtra(EXTRA_OIL,it)}; load?.let{intent.putExtra(EXTRA_LOAD,it)}; map?.let{intent.putExtra(EXTRA_MAP,it)}; iat?.let{intent.putExtra(EXTRA_IAT,it)}; validPoints?.let{intent.putExtra(EXTRA_VALID_POINTS,it)}; highPoints?.let{intent.putExtra(EXTRA_HIGH_POINTS,it)}; knockEvents?.let{intent.putExtra(EXTRA_KNOCK_EVENTS,it)}; superEvents?.let{intent.putExtra(EXTRA_SUPER_EVENTS,it)}; resultState?.let{intent.putExtra(EXTRA_RESULT_STATE,it)}; mode?.let{intent.putExtra(EXTRA_MODE,it)}; segments?.let{intent.putExtra(EXTRA_SEGMENTS,it)}; coverage?.let{intent.putExtra(EXTRA_COVERAGE,it)}; speed?.let{intent.putExtra(EXTRA_SPEED,it)}; fuelPct?.let{intent.putExtra(EXTRA_FUEL_PCT,it)}; fuelId?.let{intent.putExtra(EXTRA_FUEL_ID,it)}; phase?.let{intent.putExtra(EXTRA_PHASE,it)}; steadyPoints?.let{intent.putExtra(EXTRA_STEADY_POINTS,it)}; accelPoints?.let{intent.putExtra(EXTRA_ACCEL_POINTS,it)}; refuelNote?.let{intent.putExtra(EXTRA_REFUEL,it)}; mixingKm?.let{intent.putExtra(EXTRA_MIXING_KM,it)}
        steadyConf?.let{intent.putExtra(EXTRA_STEADY_CONF,it)}; accelConf?.let{intent.putExtra(EXTRA_ACCEL_CONF,it)}
        steadySegments?.let{intent.putExtra(EXTRA_STEADY_SEGMENTS,it)}; accelSegments?.let{intent.putExtra(EXTRA_ACCEL_SEGMENTS,it)}
        steadyScore?.let{intent.putExtra(EXTRA_STEADY_SCORE,it)}; accelScore?.let{intent.putExtra(EXTRA_ACCEL_SCORE,it)}
        highRpmMean?.let{intent.putExtra(EXTRA_HIGH_RPM_RATIO,it)}; highRpmPoints?.let{intent.putExtra(EXTRA_HIGH_RPM_POINTS,it)}
        highRpmOver120?.let{intent.putExtra(EXTRA_HIGH_RPM_OVER120,it)}
        steadySurveyPoints?.let{intent.putExtra(EXTRA_STEADY_SURVEY,it)}
        steadySurveyCells?.let{intent.putExtra(EXTRA_STEADY_CELLS,it)}
        accelV08Score?.let{intent.putExtra(EXTRA_ACCEL_V08_SCORE,it)}
        accelV08Points?.let{intent.putExtra(EXTRA_ACCEL_V08_POINTS,it)}
        stft?.let{intent.putExtra(EXTRA_STFT,it)}
        ltft?.let{intent.putExtra(EXTRA_LTFT,it)}
        combinedTrim?.let{intent.putExtra(EXTRA_COMBINED_TRIM,it)}
        ignitionSpread?.let{intent.putExtra(EXTRA_IGN_SPREAD,it)}
        fineCells?.let{intent.putExtra(EXTRA_FINE_STEADY_CELLS,it)}
        repeatableCells?.let{intent.putExtra(EXTRA_REPEATABLE_STEADY_CELLS,it)}
        intent.putExtra(EXTRA_CALIBRATION,OctaneCalibration.VERSION)
        sendBroadcast(intent)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7,notification(s.take(100)))
    }
    private fun connectionEvent(event:String,attempt:Int,stage:String,transport:String,ip:String?,detail:String,gapMs:Long=0L) {
        // Network callbacks and ECU polling run on separate threads; keep each CSV row atomic.
        synchronized(connectionLogLock) {
            try {
                val file=File(getExternalFilesDir(null)?:filesDir,"bmw_connection_events.csv")
                if(!file.exists()) file.appendText("wall_time_ms,event,attempt,stage,transport,gateway_ip,gap_ms,detail\n")
                val safe=detail.replace("\n"," ").replace("\r"," ").replace(",",";").take(240)
                file.appendText("${System.currentTimeMillis()},$event,$attempt,$stage,$transport,${ip?:""},$gapMs,$safe\n")
            } catch(_:Exception) { /* Diagnostics must never interrupt the logger. */ }
        }
    }

    private fun telemetryRow(elapsedMs:Long,event:String,transport:String,
                             rpm:Double?=null,load:Double?=null,map:Double?=null,
                             speed:Double?=null,coolant:Double?=null,fuelLevel:Double?=null,
                             fuelId:Int=0,run:Int=0,reconnects:Int=0,phase:String="—",
                             stft:Double?=null,ltft:Double?=null,ign:Double?=null) {
        val file=telemetryFile?:return
        fun number(v:Double?)=if(v!=null && v.isFinite()) String.format(java.util.Locale.US,"%.2f",v) else ""
        try {
            FileOutputStream(file,true).bufferedWriter().use { out ->
                out.appendLine(listOf(System.currentTimeMillis(),elapsedMs,event,transport,
                    number(rpm),number(load),number(map),number(speed),number(coolant),
                    number(fuelLevel),fuelId,run,reconnects,phase,number(stft),number(ltft),number(ign)).joinToString(","))
            }
        } catch(_:Exception) { /* Telemetry file errors must not terminate ECU polling. */ }
    }

    private fun isNetworkPermissionDenied(error:Throwable):Boolean {
        var t:Throwable?=error
        repeat(5) {
            val message=t?.message?.lowercase(java.util.Locale.ROOT) ?: ""
            if("eperm" in message || "operation not permitted" in message ||
                "eacces" in message || "permission denied" in message) return true
            if(t is SecurityException) return true
            t=t?.cause
        }
        return false
    }

    private fun networkSnapshot(): String {
        return try {
            val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val defaultNetwork=cm.activeNetwork?.toString() ?: "none"
            val wifi=cm.allNetworks.filter { n ->
                cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true
            }.joinToString("|") { it.toString() }.ifEmpty { "none" }
            "default=$defaultNetwork;wifi_networks=$wifi"
        } catch(_:Exception) { "snapshot=unavailable" }
    }

    private fun phaseConfidence(points:Int,segments:Int,cells:Int):Int {
        if(points==0 || segments==0 || cells==0)return 0
        val estimate=points.coerceAtMost(24) + (segments*8).coerceAtMost(40) + (cells*6).coerceAtMost(30)
        return if(points<10 || segments<3 || cells<3) estimate.coerceAtMost(49)
               else estimate.coerceAtMost(94)
    }

    private fun loop() {
        val started=SystemClock.elapsedRealtime(); var samples=0; var reconnects=0
        val foctanCache=arrayOfNulls<Double>(20)
        var slowIat:Double?=null; var slowIgn:Double?=null; var slowCoolant:Double?=null
        var slowThrottle:Double?=null; var slowStft:Double?=null; var slowLtft:Double?=null; var slowLambda:Double?=null
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
        var cachedIp:String?=prefs.getString("last_gateway_ip",null)
        var cachedTransport:String?=prefs.getString("last_gateway_transport",null)
        var connectionStage="NETWORK"; var connectionTransport=""; var connectionIp:String?=null
        var outageFrom:Long?=null; var emptyPolls=0; var consecutiveFailures=0; var lastTelemetryTime=0L
        // A stale Android Network handle can remain listed while VPN routing is active.
        // Never retry a binding-denied network on every connection attempt.
        val deniedNetworks=mutableMapOf<Network,Long>()
        var lastGoodNetwork:Network?=null
        var lastSelectionLog=0L
        var steadyRatioSum=0.0; var steadyRatioWeight=0.0; var accelRatioSum=0.0; var accelRatioWeight=0.0
        var steadyPoints=0; var accelPoints=0
        val acceptedAutoSegments=mutableSetOf<String>(); val steadyCells=mutableSetOf<String>(); val accelCells=mutableSetOf<String>()
        val steadySegments=mutableSetOf<Int>(); val accelSegments=mutableSetOf<Int>()
        val steadySurveyCells=mutableSetOf<String>()
        val steadyFineStats=SteadyCellStats()
        var lastFineCell=""; var lastFineSummary=SteadyCellStats.Summary(0,0,null,null,false)
        var steadySurveyPoints=0; var steadySegmentId=0; var lastSteadyObservation=0L
        var accelV08Sum=0.0; var accelV08Weight=0.0; var accelV08Points=0
        var highRpmRatioSum=0.0; var highRpmPoints=0; var highRpmOver120=0; var highRpmBaselineSum=0
        while(running) {
            try {
                connectionStage="NETWORK"; connectionTransport=""; connectionIp=null
                val cm=getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val selectionTime=SystemClock.elapsedRealtime()
                deniedNetworks.entries.removeAll { it.value<=selectionTime }
                val vpnNetworks=cm.allNetworks.filter { n ->
                    cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN)==true
                }
                val candidates=cm.allNetworks.mapNotNull { n ->
                    val caps=cm.getNetworkCapabilities(n) ?: return@mapNotNull null
                    if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                    val kind=when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                        else -> return@mapNotNull null
                    }
                    val lp=cm.getLinkProperties(n) ?: return@mapNotNull null
                    val addresses=lp.linkAddresses.mapNotNull { it.address as? Inet4Address }
                    if(addresses.isEmpty())return@mapNotNull null
                    val linkLocal=addresses.any { it.isLinkLocalAddress }
                    val priority=when {
                        n==lastGoodNetwork -> 0
                        kind=="ETHERNET" && linkLocal -> 1
                        kind=="WIFI" && linkLocal -> 2
                        kind=="ETHERNET" -> 3
                        else -> 4
                    }
                    Triple(n,kind,priority)
                }.sortedBy { it.third }
                if(selectionTime-lastSelectionLog>15000L || reconnects==0 && lastSelectionLog==0L) {
                    connectionEvent("NETWORK_SELECTION",reconnects,"NETWORK","ANDROID",null,
                        "vpn_active=${vpnNetworks.isNotEmpty()};default=${cm.activeNetwork};"+
                        "candidates=${candidates.joinToString("|") { "${it.first}:${it.second}:rank${it.third}" }};"+
                        "denied=${deniedNetworks.keys.joinToString("|")}")
                    lastSelectionLog=selectionTime
                }
                if(candidates.isEmpty()) throw IOException("No usable physical Ethernet/Wi-Fi network with IPv4 address")
                var activeNet:Network?=null; var transport=""; var gatewayIp:String?=null; var viaFastPath=false
                for(candidate in candidates) {
                    val network=candidate.first
                    val kind=candidate.second
                    if(deniedNetworks[network]?.let { it>SystemClock.elapsedRealtime() } == true) {
                        connectionEvent("NETWORK_SKIPPED",reconnects+1,"NETWORK",kind,null,
                            "network=$network;reason=previous_EPERM;retry_after_ms=${deniedNetworks[network]!!-SystemClock.elapsedRealtime()}")
                        continue
                    }
                    connectionTransport=kind
                    fun rejectIfDenied(e:Exception,stage:String):Boolean {
                        if(!isNetworkPermissionDenied(e))return false
                        deniedNetworks[network]=SystemClock.elapsedRealtime()+12000L
                        connectionEvent("NETWORK_DENIED",reconnects+1,stage,kind,null,
                            "network=$network;type=${e.javaClass.simpleName};error=${e.message?:"unknown"};"+
                            "vpn_active=${vpnNetworks.isNotEmpty()}")
                        return true
                    }
                    val rememberedIp=cachedIp
                    // Cached TCP is fastest after call-related interruption.
                    if(rememberedIp!=null && kind==cachedTransport) {
                        connectionStage="FAST_TCP"
                        try {
                            val quick=network.socketFactory.createSocket()
                            try {
                                quick.soTimeout=1800
                                quick.connect(InetSocketAddress(rememberedIp,6801),1300)
                                socket=quick; activeNet=network; transport=kind; gatewayIp=rememberedIp
                                viaFastPath=true
                                break
                            } catch(e:Exception) {
                                try{quick.close()}catch(_:Exception){}
                                throw e
                            }
                        } catch(e:Exception) {
                            connectionEvent("FAST_RETRY_FAILED",reconnects+1,connectionStage,kind,rememberedIp,
                                "network=$network;${e.javaClass.simpleName}: ${e.message?:"no details"}")
                            if(rejectIfDenied(e,connectionStage)) continue
                            if(consecutiveFailures<2) continue
                        }
                    }
                    // Discovery may fail when Android denies Network.bindSocket under VPN.
                    // Handle that per candidate, not by aborting the entire connection attempt.
                    connectionStage="DISCOVERY"
                    val broadcasts=ipv4Broadcasts(cm.getLinkProperties(network))
                    if(broadcasts.isEmpty())continue
                    val found=try {
                        emit("DISCOVERY $kind network=$network • UDP 6811 • attempt ${reconnects+1}")
                        discover(network,broadcasts)
                    } catch(e:Exception) {
                        if(!rejectIfDenied(e,connectionStage)) {
                            connectionEvent("DISCOVERY_FAILED",reconnects+1,connectionStage,kind,null,
                                "network=$network;${e.javaClass.simpleName}: ${e.message?:"no details"}")
                        }
                        null
                    } ?: continue
                    connectionStage="TCP"
                    connectionIp=found.ip
                    emit("GATEWAY $kind ${found.ip}:6801 • network=$network")
                    var candidateSocket:Socket?=null
                    try {
                        candidateSocket=network.socketFactory.createSocket()
                        candidateSocket.soTimeout=1800
                        candidateSocket.connect(InetSocketAddress(found.ip,6801),2000)
                        socket=candidateSocket; activeNet=network; transport=kind; gatewayIp=found.ip
                        break
                    } catch(e:Exception) {
                        try{candidateSocket?.close()}catch(_:Exception){}
                        connectionEvent("TCP_FAILED",reconnects+1,connectionStage,kind,found.ip,
                            "network=$network;${e.javaClass.simpleName}: ${e.message?:"no details"}")
                        rejectIfDenied(e,connectionStage)
                    }
                }
                if(activeNet==null || gatewayIp==null) throw IOException("HSFZ gateway unavailable after cached TCP and UDP discovery")
                cachedIp=gatewayIp; cachedTransport=transport; connectionIp=gatewayIp
                lastGoodNetwork=activeNet
                deniedNetworks.remove(activeNet)
                connectionTransport=transport; connectionStage="POLL"; emptyPolls=0
                consecutiveFailures=0
                prefs.edit().putString("last_gateway_ip",gatewayIp)
                    .putString("last_gateway_transport",transport).apply()
                sampleTimes.clear()
                val gap=outageFrom?.let { (SystemClock.elapsedRealtime()-it).coerceAtLeast(0L) } ?: 0L
                connectionEvent(if(gap>0)"RECOVERED" else "CONNECTED",reconnects,connectionStage,
                    transport,gatewayIp,if(viaFastPath)"cached TCP" else "UDP discovery + TCP",gap)
                outageFrom=null
                telemetryRow(SystemClock.elapsedRealtime()-started,"CONNECTED",transport,
                    fuelId=fuelSessionId,reconnects=reconnects)
                emit("CONNECTED $transport $gatewayIp:6801 • reconnects $reconnects" +
                    if(gap>0)" • recovered in ${gap} ms" else "")
                while(running && socket?.isClosed==false) {
                    fun pid(id:Int):ByteArray? {
                        val q=hsfz(byteArrayOf(0x01,id.toByte())); socket!!.getOutputStream().write(q); socket!!.getOutputStream().flush()
                        return readFrames(socket!!.getInputStream()).firstNotNullOfOrNull{payload(it)}
                    }
                    fun obd(p:ByteArray?,id:Int)=p?.let{decodeObd(it,id)}
                    val r=obd(pid(0x0C),0x0C)?.let{(((it[0].toInt()and 255)*256)+(it[1].toInt()and 255))/4.0}
                    val l=obd(pid(0x04),0x04)?.let{(it[0].toInt()and 255)*100.0/255.0}
                    val m=obd(pid(0x0B),0x0B)?.let{(it[0].toInt()and 255).toDouble()}
                    if(r==null && l==null && m==null) {
                        emptyPolls++
                        if(emptyPolls>=3) throw IOException("No ECU OBD reply for 3 consecutive polls")
                    } else emptyPolls=0
                    val now=SystemClock.elapsedRealtime()
                    if(now-lastSlow>=2000L) {
                        slowSpeed=obd(pid(0x0D),0x0D)?.firstOrNull()?.let{(it.toInt() and 255).toDouble()}
                        slowIat=obd(pid(0x0F),0x0F)?.let{((it[0].toInt()and 255)-40).toDouble()}
                        slowIgn=obd(pid(0x0E),0x0E)?.let{(it[0].toInt()and 255)/2.0-64.0}
                        slowCoolant=obd(pid(0x05),0x05)?.let{((it[0].toInt()and 255)-40).toDouble()}
                        slowThrottle=obd(pid(0x11),0x11)?.let{(it[0].toInt()and 255)*100.0/255.0}
                        slowStft=obd(pid(0x06),0x06)?.firstOrNull()?.let{((it.toInt()and 255)-128)*100.0/128.0}
                        slowLtft=obd(pid(0x07),0x07)?.firstOrNull()?.let{((it.toInt()and 255)-128)*100.0/128.0}
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
                            steadySurveyFile=createSteadySurveyFile(fuelSessionId)
                            captureActive=false; captureTailUntil=0L; runId=0; runSamples=0; runValidPoints=0; runHighPoints=0
                            runWeightedRatio=0.0; runWeight=0.0; lastRunScore=null; lastRunQuality=0
                            sessionScoreSum=0.0; sessionQualitySum=0.0; sessionValidRuns=0; sessionScores.clear()
                            coverageBins.clear(); sessionHighPoints=0; autoQualifiedSegments=0; autoAcceptedSamples=0
                            autoCoverage.clear(); autoRatioSum=0.0; autoRatioWeight=0.0; autoKnockEvents=0; autoSuperEvents=0
                            steadyRatioSum=0.0; steadyRatioWeight=0.0; accelRatioSum=0.0; accelRatioWeight=0.0
                            steadyPoints=0; accelPoints=0; steadyCells.clear(); accelCells.clear(); acceptedAutoSegments.clear()
                            steadySegments.clear(); accelSegments.clear(); steadySurveyCells.clear()
                            steadyFineStats.clear();lastFineCell=""
                            lastFineSummary=SteadyCellStats.Summary(0,0,null,null,false)
                            steadySurveyPoints=0;steadySegmentId=0;lastSteadyObservation=0L
                            accelV08Sum=0.0;accelV08Weight=0.0;accelV08Points=0
                            highRpmRatioSum=0.0; highRpmPoints=0; highRpmOver120=0; highRpmBaselineSum=0
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
                    val speed=slowSpeed; val i=slowIat; val a=slowIgn; val coolant=slowCoolant; val oil=slowOil; val throttle=slowThrottle; val stft1=slowStft; val ltft1=slowLtft; val lambdaEq=slowLambda
                    val combinedTrim=if(stft1!=null && ltft1!=null)
                        ((1.0+stft1/100.0)*(1.0+ltft1/100.0)-1.0)*100.0 else null
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
                    // v1.7.19 measurement assistant: keep polling continuously, persist only measurement segments.
                    if(((autoMode && r!=null && l!=null && m!=null && r>=1300.0 && r<=4500.0 &&
                        l>=15.0 && m>=88.0 && m<=250.0 && (speed==null || speed>=15.0)) ||
                        (!autoMode && r!=null && r>=1800.0)) && !captureActive) {
                        captureActive=true; runId++; armed2000=false; signaled4500=false; runStart=now;
                        if(autoMode)lastQualified=now; runStartRpm=r; runMaxRpm=r; runMaxLoad=l?:0.0; runMaxMap=m?:0.0; runSamples=0; lastSummary=""; runWeightedRatio=0.0; runWeight=0.0; runValidPoints=0; runHighPoints=0; runKnockEvents=0; runSuperEvents=0; lastRunScore=null; lastRunQuality=0
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
                    // STEADY v0.8 observes ordinary urban/cruise load independently
                    // from the old high-load RPM x MAP baseline (which is unchanged).
                    val steadyWindow=autoMode && r!=null && l!=null && m!=null &&
                        r>=1300.0 && r<4500.0 && l in 15.0..68.0 && m in 88.0..150.0 &&
                        (speed==null || speed>=15.0)
                    val steadyAuto=steadyWindow && warm && rpmRate!=null && mapRate!=null &&
                        kotlin.math.abs(rpmRate)<=250.0 && kotlin.math.abs(mapRate)<=16.0
                    val stableAuto=autoMode && measurementWindow && warm && rpmRate!=null && mapRate!=null &&
                        kotlin.math.abs(rpmRate)<=350.0 && kotlin.math.abs(mapRate)<=24.0
                    val drivePhase=when {
                        !autoMode -> "TEST"
                        steadyAuto -> "STEADY"
                        accelerationAuto -> "ACCELERATION"
                        stableAuto -> "STEADY_HIGH"
                        else -> "TRANSIENT"
                    }
                    previousRpm=r; previousMap=m; previousSampleTime=now
                    val highConfidenceWindow=r!=null && l!=null && m!=null && r>=2200.0 && r<=4000.0 && l>=70.0 && m>=160.0
                    // Record all valid steady observations, even outside a known
                    // reference cell. This is observation data, not an auto-adjusted baseline.
                    val steadyRef=if(steadyAuto) OctaneCalibration.steadyReference(r,m,l) else null
                    val accelRef=if(accelerationAuto) OctaneCalibration.accelerationReference(r,m) else null
                    val trimComparable=OctaneCalibration.trimsComparable(stft1,ltft1)
                    if(steadyWindow && warm && knockMean!=null && knockMean>0.0 &&
                        !fuelDetector.pending && mixingKm<=0.0 &&
                        r!=null && m!=null && l!=null && i!=null && coolant!=null) {
                        val coarseCell=OctaneCalibration.steadyCell(r,m)
                        val fineCell=OctaneCalibration.fineSteadyCell(r,m,l,i,coolant)
                        val validSteadyRef=steadyAuto && trimComparable && steadyRef!=null &&
                            steadyRef.trainingSamples>=5 && steadyRef.trainingTrips>=2
                        var fineStats=steadyFineStats.summary(fineCell)
                        if(steadyAuto) {
                            if(lastSteadyObservation==0L || now-lastSteadyObservation>6000L)steadySegmentId++
                            lastSteadyObservation=now
                            steadySurveyPoints++
                            steadySurveyCells.add(fineCell)
                            if(trimComparable) {
                                fineStats=steadyFineStats.record(fineCell,steadySegmentId,knockMean)
                            }
                            lastFineCell=fineCell
                            lastFineSummary=fineStats
                            if(validSteadyRef && steadyRef!=null) {
                                // This is only the earlier provisional v0.8 STEADY
                                // comparison. No self-updating reference is used.
                                val steadyRatio=knockMean/steadyRef.knockMeanVms
                                steadyRatioSum+=steadyRatio
                                steadyRatioWeight+=1.0
                                steadyPoints++
                                steadySegments.add(steadySegmentId)
                                steadyCells.add(fineCell)
                            }
                            lastQualified=now
                        }
                        // Raw candidate and matched-cell stats stay separate from
                        // the legacy official Fuel Score and from the v0.8 reference.
                        try {
                            FileOutputStream(steadySurveyFile!!,true).bufferedWriter().use { out ->
                                out.appendLine(listOf(System.currentTimeMillis(),t,r,l,m,
                                    rpmRate?:"",mapRate?:"",knockMean,i,coolant,
                                    speed?:"",steadyRef?.knockMeanVms?:"",
                                    steadyRef?.trainingSamples?:"",steadyRef?.trainingTrips?:"",
                                    coarseCell,steadySegmentId,if(steadyAuto)1 else 0,
                                    if(validSteadyRef)1 else 0,fineCell,
                                    fineStats.observations,fineStats.segments,
                                    fineStats.medianVms?:"",fineStats.dispersionPct?:"",
                                    if(trimComparable)1 else 0,stft1?:"",ltft1?:"",
                                    combinedTrim?:"",a?:"",ignSpread?:"",
                                    "", "").joinToString(","))
                            }
                        } catch(_:Exception) { }
                    }
                    val b95=if(measurementWindow) baseline95(r,m) else null
                    val ratio95=if(b95!=null && knockMean!=null && b95.v>0.0 && !fuelDetector.pending && mixingKm<=0.0 && (!autoMode || ((accelerationAuto || stableAuto) && b95.n>=5))) knockMean/b95.v else null
                    if(autoMode && measurementWindow && ratio95!=null) lastQualified=now
                    if(captureActive && measurementWindow && ratio95!=null && (!autoMode || now-lastAutoAcceptedTime>=1200L)) {
                        if(autoMode) {
                            lastAutoAcceptedTime=now; autoAcceptedSamples++
                            val w=if(highConfidenceWindow)2.0 else 1.0
                            autoRatioSum+=ratio95*w; autoRatioWeight+=w
                            if(drivePhase=="ACCELERATION") {
                                accelRatioSum+=ratio95*w; accelRatioWeight+=w; accelPoints++
                                accelSegments.add(runId)
                                if(accelRef!=null && knockMean!=null && accelRef.trainingTrips>=3) {
                                    accelV08Sum+=(knockMean/accelRef.knockMeanVms)*w
                                    accelV08Weight+=w
                                    accelV08Points++
                                }
                            }
                            // High-load steady points still count in the legacy overall
                            // score, but NEVER contaminate the low-load STEADY v0.8 score.
                            if(knockStatus==1 && !previousKnock)autoKnockEvents++
                            if(superKnock!=null && superKnock>0 && !previousSuper)autoSuperEvents++
                            acceptedAutoSegments.add("$runId:$drivePhase")
                        }
                        if(r!=null && m!=null && b95!=null && r>=3500.0 && m>=180.0) {
                            highRpmRatioSum+=ratio95; highRpmPoints++; highRpmBaselineSum+=b95.n
                            if(ratio95>=1.20)highRpmOver120++
                        }
                        val weight=if(highConfidenceWindow) 2.0 else 1.0
                        val rv=r!!; val mv=m!!; val rb=((rv-2000.0)/500.0).toInt().coerceIn(0,4); val mb=when{mv<160->0;mv<180->1;mv<200->2;else->3}; coverageBins.add("$rb:$mb"); if(autoMode) {
                            autoCoverage.add("$rb:$mb")
                            if(drivePhase=="ACCELERATION") accelCells.add("$rb:$mb")
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
                    val steadyConfidence=if(autoMode)
                        // Only one legacy provisional reference cell is supported by
                        // independent AI-95 sessions; no independent v0.9 holdout yet.
                        phaseConfidence(steadyPoints,steadySegments.size,steadyCells.size).coerceAtMost(49)
                        else 0
                    val accelConfidence=if(autoMode)phaseConfidence(accelPoints,accelSegments.size,accelCells.size) else 0
                    val sessionConfidence=if(autoMode) {
                        val primary=maxOf(steadyConfidence,accelConfidence)
                        when {
                            steadyConfidence>=60 && accelConfidence>=60 -> (primary+10).coerceAtMost(95)
                            steadyPoints>=4 && accelPoints>=4 -> primary.coerceAtMost(82)
                            else -> primary.coerceAtMost(74)
                        }
                    } else ((sessionValidRuns*8).coerceAtMost(40)+(sessionHighPoints*2).coerceAtMost(25)+(coverageBins.size*2).coerceAtMost(20)+consistency).coerceAtMost(100)
                    val steadyScore=if(steadyRatioWeight>0.0) (100.0+(1.0-steadyRatioSum/steadyRatioWeight)*50.0).coerceIn(0.0,120.0) else null
                    val accelScore=if(accelRatioWeight>0.0) (100.0+(1.0-accelRatioSum/accelRatioWeight)*50.0).coerceIn(0.0,120.0) else null
                    val accelV08Score=if(accelV08Weight>0.0)
                        OctaneCalibration.experimentalScore(accelV08Sum/accelV08Weight) else null
                    val highRpmMean=if(highRpmPoints>0) highRpmRatioSum/highRpmPoints else null
                    val highRpmBaselineAvg=if(highRpmPoints>0) highRpmBaselineSum.toDouble()/highRpmPoints else null
                    val resultState=when { mixingKm>0.0 -> "MIXING"; fuelDetector.pending -> "REFUEL CHECK"; sessionConfidence<60 -> "COLLECTING"; sessionScore==null -> "COLLECTING"; sessionScore>=95.0 -> "NORMAL"; sessionScore>=92.0 -> "BORDERLINE"; else -> "BELOW BASELINE" }
                    // Heartbeat is written regardless of captureActive. Keep measurement
                    // CSV unchanged, but make short connection tests analyzable.
                    if(now-lastTelemetryTime>=2500L) {
                        telemetryRow(t,if(captureActive)"CAPTURE" else "IDLE",transport,
                            r,l,m,speed,coolant,slowFuel,fuelSessionId,runId,reconnects,drivePhase,
                            stft1,ltft1,a)
                        lastTelemetryTime=now
                    }
                    if(captureActive) FileOutputStream(logFile!!,true).bufferedWriter().use{w-> w.appendLine((listOf(t,r?:"",l?:"",m?:"",i?:"",a?:"",coolant?:"",throttle?:"",stft1?:"",lambdaEq?:"",knockStatus?:"",superKnock?:"",kz1?:"",kz2?:"",kz3?:"",kz4?:"",iz1?:"",iz2?:"",iz3?:"",iz4?:"") + foctanCache.map{it?:""} + listOf(if(testWindow)1 else 0,transport,"%.3f".format(java.util.Locale.US,hz),fuelFactor?:"",ronEquiv?:"",knockMean?:"",ignSpread?:"",confidence,runId,reconnects,if(measurementWindow)1 else 0,if(highConfidenceWindow)1 else 0,b95?.v?:"",b95?.n?:"",ratio95?:"",liveRunScore?:"",liveRunQuality,runValidPoints,sessionScore?:"",sessionConfidence,sessionValidRuns,oil?:"",if(autoMode)"AUTO" else "TEST",coverageBins.size,speed?:"",slowFuel?:"",fuelSessionId,drivePhase,steadyPoints,accelPoints,steadyScore?:"",accelScore?:"",if(fuelDetector.pending)1 else 0,"%.2f".format(java.util.Locale.US,mixingKm),steadyConfidence,accelConfidence,steadySegments.size,accelSegments.size,highRpmMean?:"",highRpmPoints,highRpmOver120,highRpmBaselineAvg?:"",acceptedAutoSegments.size,
                        steadySurveyPoints,steadySurveyCells.size,accelV08Score?:"",accelV08Points,
                        OctaneCalibration.VERSION,steadyRef?.knockMeanVms?:"",accelRef?.knockMeanVms?:"",
                        ltft1?:"",combinedTrim?:"",a?:"",lastFineCell,
                        steadyFineStats.cells(),steadyFineStats.repeatableCells(),
                        lastFineSummary.observations,lastFineSummary.segments,
                        lastFineSummary.medianVms?:"",lastFineSummary.dispersionPct?:"",
                        "", "")).joinToString(","))}
                    samples++
                    if(samples%4==0) { val state=if(autoMode) { if(captureActive) "AUTO • УЧАСТОК #$runId" else "AUTO • ПОИСК УЧАСТКА" } else if(captureActive) { if(signaled4500) "ЗАВЕРШЕНИЕ #$runId" else if(armed2000) "ЗАМЕР #$runId" else "ГОТОВ #$runId" } else if(lastSummary.isNotEmpty()) "ЗАВЕРШЁН #$runId" else "ОЖИДАНИЕ"; emit("v1.7.19 • $state • $transport • ${"%.1f".format(java.util.Locale.US,hz)} Hz • Fuel ${liveRunScore?.let{String.format(java.util.Locale.US,"%.0f",it)}?:"—"} Q$liveRunQuality%",state,r,transport,runId,hz,reconnects,lastSummary.takeIf{it.isNotEmpty()},liveRunScore,liveRunQuality,sessionScore,sessionConfidence,sessionValidRuns,coolant,oil,l,m,i,runValidPoints,runHighPoints,runKnockEvents,runSuperEvents,resultState,if(autoMode)"AUTO" else "TEST",if(autoMode)acceptedAutoSegments.size else sessionValidRuns,coverageBins.size,speed,slowFuel,fuelSessionId,drivePhase,steadyPoints,accelPoints,lastRefuelNote.takeIf{it.isNotEmpty()},mixingKm,
                        steadyConfidence,accelConfidence,steadySegments.size,accelSegments.size,
                        steadyScore,accelScore,highRpmMean,highRpmPoints,highRpmOver120,
                        steadySurveyPoints,steadySurveyCells.size,accelV08Score,accelV08Points,
                        stft1,ltft1,combinedTrim,ignSpread,
                        steadyFineStats.cells(),steadyFineStats.repeatableCells()) }
                }
            } catch(e:Exception) {
                if(!running) break
                reconnects++; consecutiveFailures++; sampleTimes.clear()
                if(outageFrom==null) outageFrom=SystemClock.elapsedRealtime()
                // Do not combine samples on opposite sides of a network interruption.
                captureActive=false; captureTailUntil=0L; previousSampleTime=0L
                previousRpm=null; previousMap=null; lastQualified=0L;lastSteadyObservation=0L
                runWeightedRatio=0.0; runWeight=0.0; runValidPoints=0; runHighPoints=0
                connectionEvent("DISCONNECTED",reconnects,connectionStage,connectionTransport,connectionIp,
                    "${e.javaClass.simpleName}: ${e.message?:"no details"};${networkSnapshot()}")
                telemetryRow(SystemClock.elapsedRealtime()-started,"DISCONNECTED",connectionTransport,
                    fuelId=fuelSessionId,reconnects=reconnects,phase=connectionStage)
                tone.startTone(ToneGenerator.TONE_SUP_ERROR,600)
                emit("ENET reconnect #$reconnects [$connectionStage]: ${e.javaClass.simpleName}: ${e.message ?: "no details"}")
                try{socket?.close()}catch(_:Exception){}; socket=null
                // Wait briefly if Android reports that the Wi-Fi is blocked, then
                // retry rapidly. Repeated failures use bounded exponential backoff.
                if(vxscanWifiBlocked) {
                    val deadline=SystemClock.elapsedRealtime()+1200L
                    while(running && vxscanWifiBlocked && SystemClock.elapsedRealtime()<deadline) {
                        SystemClock.sleep(30L)
                    }
                }
                val retryMs=when(consecutiveFailures) {
                    1 -> 70L
                    2 -> 170L
                    3 -> 360L
                    4 -> 700L
                    else -> 1200L
                }
                connectionEvent("RETRY",reconnects,"BACKOFF",connectionTransport,connectionIp,
                    "delay_ms=$retryMs;wifi_blocked=$vxscanWifiBlocked;failures=$consecutiveFailures")
                if(running) SystemClock.sleep(retryMs)
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
        val s=DatagramSocket(null)
        try {
            // n.bindSocket may throw EPERM with a VPN present. Let the caller
            // quarantine this Network and continue with other Wi-Fi/Ethernet.
            n.bindSocket(s)
            s.broadcast=true
            s.soTimeout=1400
            s.bind(InetSocketAddress(0))
            val q=byteArrayOf(0,0,0,0,0,0x11)
            bs.distinct().forEach { address ->
                try {
                    s.send(DatagramPacket(q,q.size,InetAddress.getByName(address),6811))
                } catch(_:Exception) { }
            }
            return try {
                val b=ByteArray(512)
                val p=DatagramPacket(b,b.size)
                s.receive(p)
                D(p.address.hostAddress?:"")
            } catch(_:SocketTimeoutException) { null }
        } finally { s.close() }
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
