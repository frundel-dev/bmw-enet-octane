package com.example.bmwenettest

import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.os.Build
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.Gravity
import android.widget.*
import java.io.InputStream
import java.net.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var status: TextView
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var logging = false
    private var logFile: File? = null
    private var supportedPids: Set<Int> = emptySet()
    private var statusReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36,40,36,36) }
        root.addView(TextView(this).apply { text="BMW ENET OCTANE v1.7.14"; textSize=25f; gravity=Gravity.CENTER_HORIZONTAL })
        root.addView(TextView(this).apply { text="G20 • B48 • USB ENET + VXSCAN Wi-Fi • read-only"; textSize=14f; gravity=Gravity.CENTER_HORIZONTAL })
        root.addView(Button(this).apply { text="MODE: ${getSharedPreferences("bmw_native",MODE_PRIVATE).getString("measurement_mode","AUTO")} • SWITCH"; textSize=20f; minHeight=160; setPadding(24,32,24,32); setOnClickListener { val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE); val next=if(prefs.getString("measurement_mode","AUTO")=="AUTO")"TEST" else "AUTO"; prefs.edit().putString("measurement_mode",next).apply(); text="MODE: $next (restart logger)" } })
        root.addView(Button(this).apply { text="START BACKGROUND LOGGER"; textSize=20f; minHeight=160; setPadding(24,32,24,32); setOnClickListener {
            if (!logging) {
                logging=true; text="STOP LOGGER"
                val i=Intent(this@MainActivity,EnetLoggerService::class.java).setAction(EnetLoggerService.ACTION_START)
                startForegroundService(i)
                status.text="Logger started. AUTO evaluates STEADY / ACCELERATION separately. Fuel tank detection: OBD PID 0x2F, if supported. Refueling starts a new fuel session. Same AI-95 baseline = 100; Fuel Score is relative, not actual RON."
            } else {
                logging=false; text="START BACKGROUND LOGGER"
                startService(Intent(this@MainActivity,EnetLoggerService::class.java).setAction(EnetLoggerService.ACTION_STOP))
            }
        } })
        status = TextView(this).apply { textSize=15f; text="Embedded profile: DME8FF_R (fresh ECU dataset) ✓\nPRG import is optional.\n\nConnect USB-C ENET or join VXSCAN ENET Wi-Fi, ignition ON, then press START."; setPadding(0,24,0,0); setTextIsSelectable(true) }
        // Keep the dashboard scrollable, while auxiliary controls remain fixed at the bottom.
        val dashboard=ScrollView(this).apply {
            setFillViewport(true)
            addView(status)
        }
        root.addView(dashboard,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f
        ))
        // Compact bottom action: keep all secondary functions in one menu.
        root.addView(Button(this).apply {
            text="⋯  ДОПОЛНИТЕЛЬНО"
            textSize=14f
            isAllCaps=false
            minHeight=(48*resources.displayMetrics.density).toInt()
            setOnClickListener { showAdditionalMenu() }
        },LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        setContentView(root)
        statusReceiver=object:BroadcastReceiver(){
            override fun onReceive(context:Context?, intent:Intent?) {
                if(intent?.action!=EnetLoggerService.ACTION_STATUS) return
                val msg=intent.getStringExtra(EnetLoggerService.EXTRA_STATUS) ?: return
                val state=intent.getStringExtra(EnetLoggerService.EXTRA_STATE)
                if(state==null) { status.text=msg; return }
                val rpm=intent.getDoubleExtra(EnetLoggerService.EXTRA_RPM,Double.NaN)
                val speed=intent.getDoubleExtra(EnetLoggerService.EXTRA_SPEED,Double.NaN)
                val transport=intent.getStringExtra(EnetLoggerService.EXTRA_TRANSPORT) ?: "—"
                val run=intent.getIntExtra(EnetLoggerService.EXTRA_RUN_ID,0)
                val hz=intent.getDoubleExtra(EnetLoggerService.EXTRA_HZ,0.0)
                val reconnects=intent.getIntExtra(EnetLoggerService.EXTRA_RECONNECTS,0)
                val summary=intent.getStringExtra(EnetLoggerService.EXTRA_SUMMARY)
                val fuelScore=intent.getDoubleExtra(EnetLoggerService.EXTRA_FUEL_SCORE,Double.NaN)
                val runQuality=intent.getIntExtra(EnetLoggerService.EXTRA_RUN_QUALITY,0)
                val sessionScore=intent.getDoubleExtra(EnetLoggerService.EXTRA_SESSION_SCORE,Double.NaN)
                val sessionConfidence=intent.getIntExtra(EnetLoggerService.EXTRA_SESSION_CONFIDENCE,0)
                val sessionRuns=intent.getIntExtra(EnetLoggerService.EXTRA_SESSION_RUNS,0)
                val coolant=intent.getDoubleExtra(EnetLoggerService.EXTRA_COOLANT,Double.NaN)
                val oil=intent.getDoubleExtra(EnetLoggerService.EXTRA_OIL,Double.NaN)
                val load=intent.getDoubleExtra(EnetLoggerService.EXTRA_LOAD,Double.NaN)
                val map=intent.getDoubleExtra(EnetLoggerService.EXTRA_MAP,Double.NaN)
                val iat=intent.getDoubleExtra(EnetLoggerService.EXTRA_IAT,Double.NaN)
                val validPoints=intent.getIntExtra(EnetLoggerService.EXTRA_VALID_POINTS,0)
                val highPoints=intent.getIntExtra(EnetLoggerService.EXTRA_HIGH_POINTS,0)
                val knockEvents=intent.getIntExtra(EnetLoggerService.EXTRA_KNOCK_EVENTS,0)
                val superEvents=intent.getIntExtra(EnetLoggerService.EXTRA_SUPER_EVENTS,0)
                val resultState=intent.getStringExtra(EnetLoggerService.EXTRA_RESULT_STATE) ?: "COLLECTING"
                val phaseSegments=intent.getIntExtra(EnetLoggerService.EXTRA_SEGMENTS,sessionRuns)
                val steadyConfidence=intent.getIntExtra(EnetLoggerService.EXTRA_STEADY_CONF,0)
                val accelConfidence=intent.getIntExtra(EnetLoggerService.EXTRA_ACCEL_CONF,0)
                val steadySegments=intent.getIntExtra(EnetLoggerService.EXTRA_STEADY_SEGMENTS,0)
                val accelSegments=intent.getIntExtra(EnetLoggerService.EXTRA_ACCEL_SEGMENTS,0)
                val steadyScore=intent.getDoubleExtra(EnetLoggerService.EXTRA_STEADY_SCORE,Double.NaN)
                val accelScore=intent.getDoubleExtra(EnetLoggerService.EXTRA_ACCEL_SCORE,Double.NaN)
                val highRpmMean=intent.getDoubleExtra(EnetLoggerService.EXTRA_HIGH_RPM_RATIO,Double.NaN)
                val highRpmPoints=intent.getIntExtra(EnetLoggerService.EXTRA_HIGH_RPM_POINTS,0)
                val highRpmOver120=intent.getIntExtra(EnetLoggerService.EXTRA_HIGH_RPM_OVER120,0)
                val fuelPct=intent.getDoubleExtra(EnetLoggerService.EXTRA_FUEL_PCT,Double.NaN)
                val fuelId=intent.getIntExtra(EnetLoggerService.EXTRA_FUEL_ID,1)
                val drivingPhase=intent.getStringExtra(EnetLoggerService.EXTRA_PHASE) ?: "—"
                val steadyPoints=intent.getIntExtra(EnetLoggerService.EXTRA_STEADY_POINTS,0)
                val accelPoints=intent.getIntExtra(EnetLoggerService.EXTRA_ACCEL_POINTS,0)
                val refuelNote=intent.getStringExtra(EnetLoggerService.EXTRA_REFUEL)
                val mixingKm=intent.getDoubleExtra(EnetLoggerService.EXTRA_MIXING_KM,0.0)
                status.text=buildString {
                    val scoreText=if(resultState=="MIXING" || resultState=="REFUEL CHECK") "—" else if(sessionScore.isFinite()) "%.1f".format(sessionScore) else if(fuelScore.isFinite()) "%.1f".format(fuelScore) else "—"
                    val progress=if(rpm.isFinite()) (((rpm-2000.0)/2500.0)*10.0).toInt().coerceIn(0,10) else 0
                    val bar="█".repeat(progress)+"░".repeat(10-progress)
                    append("BMW OCTANE  •  ").append(transport).append("  •  ").append("%.2f Hz".format(hz)).append("\n\n")
                    append("        FUEL QUALITY\n")
                    append("             ").append(scoreText).append("\n")
                    append("       AI-95 BASELINE = 100\n")
                    append("           ").append(resultState).append(if(resultState=="NORMAL") " ✓" else "").append("\n\n")
                    append("CONFIDENCE  ").append("█".repeat((sessionConfidence/10).coerceIn(0,10))).append("░".repeat((10-sessionConfidence/10).coerceIn(0,10))).append("  ").append(sessionConfidence).append("%\n")
                    append("Qualified phase segments: ").append(phaseSegments).append("   HC points: ").append(highPoints).append("\n")
                    append("STEADY ").append(steadyPoints).append(" pts • ").append(steadySegments).append(" sections")
                        .append(" • Confidence ").append(steadyConfidence).append("%\n")
                    append("  Ratio score: ").append(if(steadyScore.isFinite()) "%.1f".format(steadyScore) else "—").append("\n")
                    append("ACCEL ").append(accelPoints).append(" pts • ").append(accelSegments).append(" sections")
                        .append(" • Confidence ").append(accelConfidence).append("%\n")
                    append("  Ratio score: ").append(if(accelScore.isFinite()) "%.1f".format(accelScore) else "—").append("\n")
                    if(steadyPoints==0 || accelPoints==0) append("Partial coverage: one driving phase missing\n")
                    append("Mode: ").append(drivingPhase).append("\n\n")
                    append("──── FUEL SESSION #").append(fuelId).append(" ────\n")
                    append("Tank: ").append(if(fuelPct.isFinite()) "%.1f%%".format(fuelPct) else "— (OBD PID 2F unavailable)").append("\n")
                    if(mixingKm>0.0) append("Mixing estimate: ").append("%.1f".format(mixingKm)).append(" km remaining\n")
                    if(!refuelNote.isNullOrBlank()) append(refuelNote).append("\n")
                    append("\n")
                    append("──── CURRENT RUN #").append(run).append(" ────\n")
                    append("Speed ").append(if(speed.isFinite()) "%.0f km/h".format(speed) else "—").append("   ")
                    append("RPM ").append(if(rpm.isFinite()) "%.0f".format(rpm) else "—").append("   ").append(bar).append("\n")
                    append("Load ").append(if(load.isFinite()) "%.0f%%".format(load) else "—").append("   MAP ").append(if(map.isFinite()) "%.0f kPa".format(map) else "—").append("\n")
                    append("IAT ").append(if(iat.isFinite()) "%.0f°C".format(iat) else "—").append("   ОЖ ").append(if(coolant.isFinite()) "%.0f°C".format(coolant) else "—").append("   Масло ").append(if(oil.isFinite()) "%.0f°C".format(oil) else "—").append("\n")
                    append("Run score ").append(if(fuelScore.isFinite()) "%.1f".format(fuelScore) else "—").append("   Quality ").append(runQuality).append("%\n")
                    append("Valid points ").append(validPoints).append("   HC ").append(highPoints).append("\n\n")
                    append("──── HIGH RPM DIAGNOSTIC ────\n")
                    append("3500+ RPM, MAP 180+: ").append(highRpmPoints).append(" samples\n")
                    append("Knock signal / AI-95 baseline: ")
                        .append(if(highRpmMean.isFinite()) "%.2f".format(highRpmMean) else "—")
                        .append("  • Ratio ≥1.20: ").append(highRpmOver120).append("\n")
                    append("Signal ratio is not a count of knock events.\n\n")
                    append("──── KNOCK ────\n")
                    append("Knock events ").append(knockEvents).append("   Superknock ").append(superEvents).append("\n\n")
                    append("STATE: ").append(state).append("   reconnect ").append(reconnects)
                    if(!summary.isNullOrBlank()) append("\n\n").append(summary)
                    append(if(drivingPhase=="TEST") "\n\nTEST: 1800 → 4500 rpm" else "\n\nAUTO: steady + acceleration • matched RPM×MAP")
                }            }
        }
        val filter=IntentFilter(EnetLoggerService.ACTION_STATUS)
        if(Build.VERSION.SDK_INT>=33) registerReceiver(statusReceiver,filter,RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") registerReceiver(statusReceiver,filter)
    }


    private fun showAdditionalMenu() {
        val items=arrayOf("Импорт BMW DME .PRG", "История замеров", "Журнал соединений VXSCAN")
        AlertDialog.Builder(this)
            .setTitle("Дополнительно")
            .setItems(items) { _,which ->
                when(which) {
                    0 -> {
                        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type="application/octet-stream"
                        }
                        startActivityForResult(i,901)
                    }
                    1 -> showMeasurementHistory()
                    2 -> showConnectionHistory()
                }
            }
            .setNegativeButton("Закрыть",null)
            .show()
    }

    private fun showConnectionHistory() {
        val file=File(getExternalFilesDir(null)?:filesDir,"bmw_connection_events.csv")
        if(!file.isFile) {
            AlertDialog.Builder(this).setTitle("VXSCAN • соединения")
                .setMessage("Пока нет записей о соединениях.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val records=try { file.useLines { it.drop(1).toList().takeLast(30) } }
                         catch(e:Exception) { emptyList<String>() }
            val details=if(records.isEmpty()) "Нет событий" else records.asReversed().joinToString("\n\n") { line ->
                val fields=line.split(",",limit=8)
                val date=fields.firstOrNull()?.toLongOrNull()?.let {
                    java.text.SimpleDateFormat("dd.MM HH:mm:ss",java.util.Locale.getDefault())
                        .format(java.util.Date(it))
                } ?: "—"
                "$date • ${fields.getOrNull(1)?:"—"}\n" +
                    "Stage: ${fields.getOrNull(3)?:"—"} • ${fields.getOrNull(4)?:"—"}" +
                    " • ${fields.getOrNull(5)?:"—"}\n" +
                    "Outage: ${fields.getOrNull(6)?:"0"} ms • ${fields.getOrNull(7)?:""}"
            }
            runOnUiThread {
                if(!isFinishing && !isDestroyed) AlertDialog.Builder(this)
                    .setTitle("VXSCAN • последние соединения")
                    .setMessage(details)
                    .setPositiveButton("OK",null).show()
            }
        }
    }

    private fun showMeasurementHistory() {
        val dir=getExternalFilesDir(null)?:filesDir
        val recordings=dir.listFiles()?.filter{it.isFile && it.name.startsWith("bmw_enet_") && it.extension.equals("csv",true)}
            ?.sortedByDescending{it.lastModified()} ?: emptyList()
        val fuelEventsFile=File(dir,"bmw_fuel_events.csv")
        val refuelEvents=if(fuelEventsFile.isFile) fuelEventsFile.readLines().filter{it.split(",").size>=4}.asReversed() else emptyList()
        if(recordings.isEmpty() && refuelEvents.isEmpty()) {
            AlertDialog.Builder(this).setTitle("История замеров").setMessage("Сохранённых CSV пока нет")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val labels=recordings.map { file ->
                try {
                    val lines=file.useLines { seq -> seq.filter{it.isNotBlank() && !it.startsWith("#")}.toList() }
                    val keys=lines.firstOrNull()?.split(",") ?: emptyList()
                    val records=lines.drop(1)
                    fun idx(name:String)=keys.indexOf(name)
                    fun last(name:String):String {
                        val col=idx(name)
                        return if(col<0) "—" else records.asReversed().firstNotNullOfOrNull {
                            it.split(",").getOrNull(col)?.takeIf{v->v.isNotBlank()}
                        } ?: "—"
                    }
                    val speedIndex=idx("speed_kmh")
                    val maxSpeed=if(speedIndex>=0) records.mapNotNull{it.split(",").getOrNull(speedIndex)?.toDoubleOrNull()}.maxOrNull() else null
                    val date=java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",java.util.Locale.getDefault()).format(java.util.Date(file.lastModified()))
                    "$date • ${file.name.substringBeforeLast(".")}\nСтрок: ${records.size} • Score: ${last("session_fuel_score")} • Confidence: ${last("session_confidence_pct")}%\nСкорость макс.: ${maxSpeed?.let{"%.0f км/ч".format(it)}?:"—"} • Топливо #${last("fuel_session_id")}\nSTEADY ${last("steady_points")} • ACCEL ${last("accel_points")} • бак ${last("fuel_level_pct")}%\nConfidence STEADY ${last("steady_confidence_pct")}% / ACCEL ${last("accel_confidence_pct")}%\nУчастки STEADY ${last("steady_segments")} / ACCEL ${last("accel_segments")} • всего ${last("auto_phase_segments")}\n3500+ RPM сигнал/эталон: ${last("high_rpm_ratio95_mean")} • точек ${last("high_rpm_points")}\nРеконнекты: ${last("reconnects")}"
                } catch(e:Exception) { file.name+" • Ошибка чтения: "+e.javaClass.simpleName }
            }
            val eventLabels=refuelEvents.map { line ->
                val fields=line.split(",")
                val date=fields[0].toLongOrNull()?.let {
                    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm",java.util.Locale.getDefault()).format(java.util.Date(it))
                } ?: "—"
                val decision=when(fields.getOrNull(4)) {
                    "CONFIRMED" -> "✓ подтверждена"
                    "REJECTED" -> "× отклонена"
                    else -> "? требует проверки"
                }
                "⛽ $date • топливо #${fields[1]}: ${fields[2]}% → ${fields[3]}% • $decision"
            }
            val allLabels=eventLabels+labels
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                AlertDialog.Builder(this).setTitle("История замеров и заправок")
                    .setItems(allLabels.toTypedArray()) { _,which ->
                        if(which<eventLabels.size) {
                            val fields=refuelEvents[which].split(",")
                            val builder=AlertDialog.Builder(this).setTitle("Возможная заправка")
                                .setMessage(eventLabels[which]+"\n\nОпределено по изменению уровня топлива. Сегменты уже разделены для безопасности расчётов.")
                            if(fields.getOrNull(4)=="PENDING" || fields.size<5) {
                                builder.setPositiveButton("Подтвердить") { _,_ -> markRefuelEvent(fields[1],"CONFIRMED") }
                                    .setNegativeButton("Не было") { _,_ -> markRefuelEvent(fields[1],"REJECTED") }
                                    .setNeutralButton("Позже",null)
                            } else builder.setPositiveButton("OK",null)
                            builder.show()
                        } else {
                            val fileIndex=which-eventLabels.size
                            val selected=recordings[fileIndex]
                            AlertDialog.Builder(this).setTitle(selected.name)
                                .setMessage(labels[fileIndex]+"\n\nФайл сохранён в папке приложения.")
                                .setPositiveButton("OK",null).show()
                        }
                    }.setNegativeButton("Закрыть",null).show()
            }
        }
    }

    private fun markRefuelEvent(id:String,decision:String) {
        val file=File(getExternalFilesDir(null)?:filesDir,"bmw_fuel_events.csv")
        if(!file.isFile)return
        val lines=file.readLines().toMutableList()
        val index=lines.indexOfLast { it.split(",").getOrNull(1)==id }
        if(index<0)return
        lines[index]=lines[index].split(",").take(4).joinToString(",")+"," + decision
        file.writeText(lines.joinToString("\n",postfix="\n"))
        if(decision=="REJECTED") {
            getSharedPreferences("bmw_native",MODE_PRIVATE).edit()
                .putInt("refuel_rejected_id",id.toIntOrNull()?:-1).apply()
        }
        Toast.makeText(this,if(decision=="CONFIRMED")"Заправка подтверждена" else "Ложное событие отмечено; история не удалена",Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        statusReceiver?.let{try{unregisterReceiver(it)}catch(_:Exception){}}
        statusReceiver=null
        super.onDestroy()
    }


    override fun onActivityResult(requestCode:Int, resultCode:Int, data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=901 || resultCode!=RESULT_OK) return
        val uri=data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch(_:Exception) {}
        try {
            val name=queryName(uri) ?: "dme.prg"
            if(!name.lowercase().endsWith(".prg")) throw IllegalArgumentException("Select a BMW .PRG file")
            val dst=File(filesDir,"ecu").apply{mkdirs()}.resolve(name)
            contentResolver.openInputStream(uri)!!.use { input -> dst.outputStream().use { input.copyTo(it) } }
            if(dst.length()<1024) { dst.delete(); throw IllegalArgumentException("PRG file is too small") }
            val lower=name.lowercase()
            val profile=when {
                lower=="dme8ff_r.prg" -> "DME8FF_R"
                lower=="dme_bx8.prg" -> "DME_BX8"
                else -> "UNVERIFIED"
            }
            val sha=sha256(dst)
            val expectedSize=when(profile) {
                "DME8FF_R" -> 7458486L
                "DME_BX8" -> 4254857L
                else -> -1L
            }
            val freshMatch=expectedSize==dst.length()
            getSharedPreferences("bmw_native",MODE_PRIVATE).edit()
                .putString("prg_path",dst.absolutePath).putString("prg_name",name)
                .putString("prg_profile",profile).putString("prg_sha256",sha)
                .putBoolean("fresh_ecu_match",freshMatch).apply()
            status.text="""BMW DME PRG imported ✓
File: $name
Profile: $profile
Size: ${dst.length()} bytes
Fresh ECU.zip size match: ${if(freshMatch)"YES ✓" else "NO / unknown"}
SHA-256: $sha

v1.1 priority:
1. DME8FF_R — preferred G20/B48 profile
2. DME_BX8 — fallback/comparison profile

Native target set:
• STATUS_SUPERKLOPFER / STAT_STATUS_KLOPFEN
• STAT_KLOPFWERT_ZYL1…4_SPANNUNG_WERT
• STAT_ZUENDWINKEL_ZYL1…4_WERT
• STAT_INFOFOCTAN_*

PRG is stored privately. READ-ONLY.
Unknown DIDs are never probed."""
        } catch(e:Exception) { status.text="PRG IMPORT ERROR: ${e.message}" }
    }

    private fun sha256(file:File):String {
        val md=MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val b=ByteArray(65536)
            while(true) { val n=input.read(b); if(n<=0) break; md.update(b,0,n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun queryName(uri:android.net.Uri):String? {
        contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { c ->
            if(c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    private data class LiveSample(
        val t: Long, val rpm: Double?, val load: Double?, val map: Double?,
        val iat: Double?, val ign: Double?
    )

    private fun runLogger(button: Button) {
        status.text = "Connecting to BMW…"
        executor.execute {
            var socket: Socket? = null
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.allNetworks.firstOrNull {
                    cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
                } ?: throw IllegalStateException("Ethernet not found")
                val lp = cm.getLinkProperties(network)
                val d = discover(network, ipv4Broadcasts(lp)) ?: throw IllegalStateException("BMW HSFZ discovery: no reply")
                socket = network.socketFactory.createSocket() as Socket
                socket.soTimeout = 1800
                socket.connect(InetSocketAddress(d.ip, 6801), 2500)

                val dir = getExternalFilesDir(null) ?: filesDir
                logFile = File(dir, "bmw_enet_v06_${System.currentTimeMillis()}.csv")
                FileOutputStream(logFile!!, false).bufferedWriter().use { w ->
                    w.appendLine("time_ms,rpm,load_pct,map_kpa_abs,iat_c,ign_advance_deg,coolant_c,throttle_pct,stft1_pct,lambda_eq")
                }

                val start = SystemClock.elapsedRealtime()
                var count = 0
                var rpmMin = Double.POSITIVE_INFINITY; var rpmMax = Double.NEGATIVE_INFINITY
                var loadMax = Double.NEGATIVE_INFINITY
                while (logging) {
                    fun readPid(pid: Int): ByteArray? {
                        val req = hsfzDiag(0xF4, 0x12, byteArrayOf(0x01, pid.toByte()))
                        socket!!.getOutputStream().write(req); socket!!.getOutputStream().flush()
                        return readFrames(socket!!.getInputStream(), 2).firstNotNullOfOrNull { payload(it) }
                    }
                    val rpm = readPid(0x0C)?.let { decodeObd(it,0x0C) }?.let { (((it[0].toInt() and 255)*256)+(it[1].toInt() and 255))/4.0 }
                    val load = readPid(0x04)?.let { decodeObd(it,0x04) }?.let { (it[0].toInt() and 255)*100.0/255.0 }
                    val map = readPid(0x0B)?.let { decodeObd(it,0x0B) }?.let { (it[0].toInt() and 255).toDouble() }
                    val iat = readPid(0x0F)?.let { decodeObd(it,0x0F) }?.let { ((it[0].toInt() and 255)-40).toDouble() }
                    if (supportedPids.isEmpty()) supportedPids = scanSupportedPids(socket!!)
                    val ign = readPid(0x0E)?.let { decodeObd(it,0x0E) }?.let { (it[0].toInt() and 255)/2.0-64.0 }
                    val coolant = if (0x05 in supportedPids) readPid(0x05)?.let { decodeObd(it,0x05) }?.let { ((it[0].toInt() and 255)-40).toDouble() } else null
                    val throttle = if (0x11 in supportedPids) readPid(0x11)?.let { decodeObd(it,0x11) }?.let { (it[0].toInt() and 255)*100.0/255.0 } else null
                    val stft1 = if (0x06 in supportedPids) readPid(0x06)?.let { decodeObd(it,0x06) }?.let { ((it[0].toInt() and 255)-128)*100.0/128.0 } else null
                    val lambdaEq = if (0x44 in supportedPids) readPid(0x44)?.let { decodeObd(it,0x44) }?.let { (((it[0].toInt() and 255)*256)+(it[1].toInt() and 255))*2.0/65535.0 } else null
                    val s = LiveSample(SystemClock.elapsedRealtime()-start,rpm,load,map,iat,ign)
                    rpm?.let { rpmMin=kotlin.math.min(rpmMin,it); rpmMax=kotlin.math.max(rpmMax,it) }
                    load?.let { loadMax=kotlin.math.max(loadMax,it) }
                    FileOutputStream(logFile!!, true).bufferedWriter().use { w ->
                        w.appendLine(listOf(s.t,s.rpm?:"",s.load?:"",s.map?:"",s.iat?:"",s.ign?:"",coolant?:"",throttle?:"",stft1?:"",lambdaEq?:"").joinToString(","))
                    }
                    count++
                    val hz = if (s.t > 0) count*1000.0/s.t else 0.0
                    main.post {
                        status.text = """BMW: CONNECTED  ${d.ip}:6801
VIN: ${d.vin ?: "unknown"}
Samples: $count   Effective rate: ${"%.2f".format(hz)} Hz

RPM: ${rpm?.let{"%.0f".format(it)} ?: "—"}
Load: ${load?.let{"%.1f %%".format(it)} ?: "—"}
MAP: ${map?.let{"%.0f kPa abs".format(it)} ?: "—"}
IAT: ${iat?.let{"%.1f °C".format(it)} ?: "—"}
Ignition advance: ${ign?.let{"%.1f °".format(it)} ?: "—"}
Coolant: ${coolant?.let{"%.1f °C".format(it)} ?: "—"}
Throttle: ${throttle?.let{"%.1f %%".format(it)} ?: "—"}
STFT Bank 1: ${stft1?.let{"%+.1f %%".format(it)} ?: "—"}
Lambda eq.: ${lambdaEq?.let{"%.3f".format(it)} ?: "—"}
Supported Mode 01 PIDs: ${supportedPids.size}

RPM min/max: ${if(rpmMin.isFinite()) "%.0f / %.0f".format(rpmMin,rpmMax) else "—"}
Max load: ${if(loadMax.isFinite()) "%.1f %%".format(loadMax) else "—"}

CSV: ${logFile!!.absolutePath}

BMW knock/timing correction:
EDIABAS status_lesen path identified; raw UDS DID is not guessed.
READ-ONLY."""
                    }
                }
            } catch (e: Exception) {
                logging = false
                main.post { status.text = "LOGGER ERROR ${e.javaClass.simpleName}: ${e.message}"; button.text="START LIVE LOGGER" }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
                logging = false
                main.post { button.text="START LIVE LOGGER" }
            }
        }
    }

    private fun runTest() {
        status.text = "Scanning…"
        executor.execute {
            val out = mutableListOf<String>()
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true }
                if (network == null) out += "Ethernet: NOT FOUND" else {
                    val lp = cm.getLinkProperties(network)
                    out += "Ethernet: CONNECTED"
                    out += "Interface: ${lp?.interfaceName ?: "?"}"
                    out += "Android IP: ${lp?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" } ?: "?"}"
                    out += "Routes: " + (lp?.routes?.joinToString { it.toString() } ?: "?")
                    val broadcasts = ipv4Broadcasts(lp)
                    out += "IPv4 broadcasts: " + if (broadcasts.isEmpty()) "none" else broadcasts.joinToString()
                    val d = discover(network, broadcasts)
                    if (d == null) {
                        out += "\nBMW HSFZ discovery: NO REPLY"
                        out += probeTcpCandidates(network, broadcasts, 6801, "HSFZ TCP")
                        out += probeDoip(network, broadcasts)
                    } else {
                        out += "\nBMW: FOUND"
                        out += "Gateway: ${d.ip}"
                        out += "Discovery VIN: ${d.vin ?: "not parsed"}"
                        out += "Discovery bytes: ${d.bytes}"
                        val socket = network.socketFactory.createSocket() as Socket
                        socket.soTimeout = 2500
                        socket.connect(InetSocketAddress(d.ip, 6801), 2500)
                        out += "TCP 6801: OPEN ✓"
                        val vinReq = hsfzDiag(0xF4, 0x12, byteArrayOf(0x22, 0xF1.toByte(), 0x90.toByte()))
                        out += "\nDME 0x12 → UDS 22 F190 (VIN)"
                        out += "TX: ${hex(vinReq)}"
                        socket.getOutputStream().write(vinReq); socket.getOutputStream().flush()
                        val vinFrames = readFrames(socket.getInputStream(), 2)
                        if (vinFrames.isEmpty()) out += "RX: timeout / no frame"
                        else vinFrames.forEachIndexed { i, f -> out += "RX${i+1}: ${hex(f)}"; decodeVin(f)?.let { out += "DME VIN: $it" } }
                        out += "\nLIVE DATA — standard OBD services through DME"
                        val pids = listOf(
                            Triple(0x0C, "RPM") { p: ByteArray -> decodeObd(p, 0x0C)?.let { (((it[0].toInt() and 255) * 256) + (it[1].toInt() and 255)) / 4.0 to "rpm" } },
                            Triple(0x04, "Calculated load") { p: ByteArray -> decodeObd(p, 0x04)?.let { (it[0].toInt() and 255) * 100.0 / 255.0 to "%" } },
                            Triple(0x0B, "MAP") { p: ByteArray -> decodeObd(p, 0x0B)?.let { (it[0].toInt() and 255).toDouble() to "kPa abs" } },
                            Triple(0x0F, "IAT") { p: ByteArray -> decodeObd(p, 0x0F)?.let { ((it[0].toInt() and 255) - 40).toDouble() to "°C" } },
                            Triple(0x0E, "Ignition advance") { p: ByteArray -> decodeObd(p, 0x0E)?.let { ((it[0].toInt() and 255) / 2.0 - 64.0) to "°" } }
                        )
                        for ((pid, name, decoder) in pids) {
                            val req = hsfzDiag(0xF4, 0x12, byteArrayOf(0x01, pid.toByte()))
                            socket.getOutputStream().write(req); socket.getOutputStream().flush()
                            val frames = readFrames(socket.getInputStream(), 2)
                            val diag = frames.firstNotNullOfOrNull { payload(it) }
                            val value = diag?.let(decoder)
                            out += if (value != null) "$name: %.2f %s".format(value.first, value.second)
                                   else "$name: no decoded value"
                            if (frames.isNotEmpty()) out += "  RX: " + frames.joinToString(" | ") { hex(it) }
                        }
                        out += "\nBMW-specific knock/timing correction: DID mapping not enabled yet (no guessed identifiers)."
                        out += "Transport/DME session is ready for validated B48 DIDs."
                        socket.close()
                        out += "\nREAD-ONLY complete. No coding, flashing, security access or write service sent."
                    }
                }
            } catch (e: Exception) { out += "\nERROR ${e.javaClass.simpleName}: ${e.message}" }
            main.post { status.text = out.joinToString("\n") }
        }
    }

    data class Discovery(val ip:String, val vin:String?, val bytes:Int)

    private fun discover(network: Network, broadcasts: List<String>): Discovery? {
        val s=DatagramSocket(null); network.bindSocket(s); s.reuseAddress=true; s.broadcast=true; s.soTimeout=2500; s.bind(InetSocketAddress(0))
        val req=byteArrayOf(0,0,0,0,0,0x11)
        (broadcasts + listOf("169.254.255.255","255.255.255.255")).distinct().forEach { try{s.send(DatagramPacket(req,req.size,InetAddress.getByName(it),6811))}catch(_:Exception){} }
        return try {
            val b=ByteArray(1024); val p=DatagramPacket(b,b.size); s.receive(p); val data=p.data.copyOf(p.length)
            val a=String(data,StandardCharsets.US_ASCII); val m="BMWVIN"; val x=a.indexOf(m)
            val vin=if(x>=0) a.substring(x+m.length).filter{it.isLetterOrDigit()}.take(17).takeIf{it.length==17} else null
            Discovery(p.address.hostAddress?:"?",vin,p.length)
        } catch(_:SocketTimeoutException){null} finally{s.close()}
    }


    private fun ipv4Broadcasts(lp: android.net.LinkProperties?): List<String> {
        if (lp == null) return emptyList()
        return lp.linkAddresses.mapNotNull { la ->
            val a = la.address
            if (a !is java.net.Inet4Address) return@mapNotNull null
            val p = la.prefixLength
            if (p !in 0..32) return@mapNotNull null
            val raw = a.address
            var ip = 0L
            for (b in raw) ip = (ip shl 8) or (b.toInt() and 255).toLong()
            val mask = if (p == 0) 0L else (0xFFFFFFFFL shl (32 - p)) and 0xFFFFFFFFL
            val bc = (ip and mask) or (mask.inv() and 0xFFFFFFFFL)
            listOf((bc shr 24) and 255, (bc shr 16) and 255, (bc shr 8) and 255, bc and 255).joinToString(".")
        }.distinct()
    }

    private fun probeTcpCandidates(network: Network, broadcasts: List<String>, port: Int, label: String): String {
        val candidates = mutableSetOf<String>()
        // Default gateways are tested separately through LinkProperties routes in a future revision.
        // Broadcast addresses themselves are not valid TCP peers, so this is informational only.
        return "\\n$label $port: no discovered peer IP to test"
    }

    private fun probeDoip(network: Network, broadcasts: List<String>): String {
        val targets = (broadcasts + listOf("169.254.255.255", "255.255.255.255")).distinct()
        val req = byteArrayOf(0x02, 0xFD.toByte(), 0x00, 0x01, 0, 0, 0, 0)
        val s = DatagramSocket(null)
        return try {
            network.bindSocket(s); s.reuseAddress = true; s.broadcast = true; s.soTimeout = 1200
            s.bind(InetSocketAddress(0))
            targets.forEach { t -> try { s.send(DatagramPacket(req, req.size, InetAddress.getByName(t), 13400)) } catch (_: Exception) {} }
            val b = ByteArray(2048); val p = DatagramPacket(b, b.size); s.receive(p)
            val data = p.data.copyOf(p.length)
            val vin = if (data.size >= 25 && data[2] == 0x00.toByte() && data[3] == 0x04.toByte())
                String(data.copyOfRange(8, 25), StandardCharsets.US_ASCII).filter { it.code in 32..126 } else null
            "\\nDoIP UDP 13400: REPLY from ${p.address.hostAddress}, bytes=${p.length}" + (vin?.let { "\\nDoIP VIN: $it" } ?: "")
        } catch (_: SocketTimeoutException) {
            "\\nDoIP UDP 13400: NO REPLY"
        } catch (e: Exception) {
            "\\nDoIP UDP 13400: ERROR ${e.javaClass.simpleName}: ${e.message}"
        } finally { s.close() }
    }

    private fun hsfzDiag(src:Int, dst:Int, uds:ByteArray):ByteArray {
        val len=2+uds.size
        return byteArrayOf((len ushr 24).toByte(),(len ushr 16).toByte(),(len ushr 8).toByte(),len.toByte(),0x00,0x01,src.toByte(),dst.toByte()) + uds
    }

    private fun readFrames(input:InputStream, max:Int):List<ByteArray> {
        val result= mutableListOf<ByteArray>()
        repeat(max) {
            try {
                val h=readExact(input,6) ?: return@repeat
                val len=((h[0].toInt() and 255) shl 24) or ((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
                if(len<0 || len>65536) return result
                val body=readExact(input,len) ?: return result
                result += h+body
                val type=((h[4].toInt() and 255) shl 8) or (h[5].toInt() and 255)
                if(type==1) return result
            } catch(_:SocketTimeoutException){ return result }
        }
        return result
    }

    private fun readExact(input:InputStream,n:Int):ByteArray? {
        val b=ByteArray(n); var off=0
        while(off<n){ val r=input.read(b,off,n-off); if(r<0)return null; off+=r }
        return b
    }

    private fun payload(frame:ByteArray):ByteArray? {
        if(frame.size<8)return null
        val type=((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255)
        return if(type==1) frame.copyOfRange(8,frame.size) else null
    }

    private fun decodeVin(frame:ByteArray):String? {
        val p=payload(frame)?:return null
        if(p.size>=20 && p[0]==0x62.toByte() && p[1]==0xF1.toByte() && p[2]==0x90.toByte())
            return String(p.copyOfRange(3,20),StandardCharsets.US_ASCII).filter{it.code in 32..126}
        return null
    }



    private fun scanSupportedPids(socket: Socket): Set<Int> {
        val result = mutableSetOf<Int>()
        for (base in listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)) {
            val req = hsfzDiag(0xF4, 0x12, byteArrayOf(0x01, base.toByte()))
            socket.getOutputStream().write(req); socket.getOutputStream().flush()
            val p = readFrames(socket.getInputStream(), 2).firstNotNullOfOrNull { payload(it) } ?: break
            val d = decodeObd(p, base) ?: break
            if (d.size < 4) break
            for (bit in 0 until 32) {
                val byteIndex = bit / 8
                val bitIndex = 7 - (bit % 8)
                if (((d[byteIndex].toInt() ushr bitIndex) and 1) != 0) result += base + bit + 1
            }
            if ((base + 0x20) !in result) break
        }
        return result
    }

    private fun decodeObd(p: ByteArray, pid: Int): ByteArray? {
        for (i in 0 until p.size - 1) {
            if (p[i] == 0x41.toByte() && (p[i + 1].toInt() and 255) == pid)
                return p.copyOfRange(i + 2, p.size)
        }
        return null
    }

    private fun decodeRpm(frame:ByteArray):Double? {
        val p=payload(frame)?:return null
        for(i in 0 until p.size-3) if(p[i]==0x41.toByte() && p[i+1]==0x0C.toByte())
            return (((p[i+2].toInt() and 255)*256)+(p[i+3].toInt() and 255))/4.0
        return null
    }

    private fun decodeNegative(frame:ByteArray):String? {
        val p=payload(frame)?:return null
        return if(p.size>=3 && p[0]==0x7F.toByte()) "service=${"%02X".format(p[1])}, NRC=${"%02X".format(p[2])}" else null
    }

    private fun hex(b:ByteArray)=b.joinToString(" "){"%02X".format(it.toInt() and 255)}
}
