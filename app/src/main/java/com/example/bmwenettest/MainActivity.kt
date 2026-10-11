package com.example.bmwenettest

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.widget.ImageView
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
    private lateinit var dashboard: OctaneDashboard
    private val executor = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var logging = false
    @Volatile private var historyBusy = false
    @Volatile private var historyCancel = false
    private var logFile: File? = null
    private var supportedPids: Set<Int> = emptySet()
    private var statusReceiver: BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scale=resources.displayMetrics.density
        fun dp(v:Int)=(scale*v+0.5f).toInt()
        val navy=Color.rgb(12,19,32)
        val muted=Color.rgb(158,175,196)
        val sky=Color.rgb(89,171,255)
        window.statusBarColor=navy
        window.navigationBarColor=navy
        window.decorView.systemUiVisibility=0

        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(14),dp(15),dp(14),dp(7))
            setBackgroundColor(navy)
        }
        val heading=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        heading.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_bmw_roundel)
            scaleType=ImageView.ScaleType.FIT_CENTER
        },LinearLayout.LayoutParams(dp(38),dp(38)).apply {
            rightMargin=dp(8)
        })
        heading.addView(TextView(this).apply {
            text="BMW  OCTANE"
            textSize=22f
            setTextColor(Color.WHITE)
            typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        heading.addView(TextView(this).apply {
            text="v1.7.29"
            textSize=13f
            setTextColor(sky)
        })
        root.addView(heading)
        root.addView(TextView(this).apply {
            text="G20 / B48   •   USB ENET / VXSCAN"
            textSize=12f
            setTextColor(muted)
            setPadding(0,dp(5),0,dp(14))
        })
        val controls=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val modeButton=Button(this).apply {
            text="РЕЖИМ: "+prefs.getString("measurement_mode","AUTO")
            textSize=14f
            isAllCaps=false
            minHeight=dp(52)
            setTextColor(Color.WHITE)
            backgroundTintList=ColorStateList.valueOf(Color.rgb(41,60,84))
            setOnClickListener {
                val next=if(prefs.getString("measurement_mode","AUTO")=="AUTO")"TEST" else "AUTO"
                prefs.edit().putString("measurement_mode",next).apply()
                text="РЕЖИМ: "+next
                if(logging) Toast.makeText(this@MainActivity,"Новый режим активируется после перезапуска логгера",Toast.LENGTH_LONG).show()
            }
        }
        controls.addView(modeButton,LinearLayout.LayoutParams(0,dp(54),1f).apply {
            rightMargin=dp(7)
        })
        val startButton=Button(this).apply {
            text="START LOGGER"
            textSize=15f
            isAllCaps=false
            minHeight=dp(52)
            setTextColor(Color.rgb(7,18,29))
            backgroundTintList=ColorStateList.valueOf(sky)
            setOnClickListener {
                if(!logging) {
                    if(historyBusy) {
                        Toast.makeText(this@MainActivity,
                            "Сначала заверши чтение истории DME",Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    logging=true
                    text="STOP LOGGER"
                    backgroundTintList=ColorStateList.valueOf(Color.rgb(255,179,126))
                    dashboard.setLogging(true)
                    startForegroundService(Intent(this@MainActivity,EnetLoggerService::class.java)
                        .setAction(EnetLoggerService.ACTION_START))
                } else {
                    logging=false
                    text="START LOGGER"
                    backgroundTintList=ColorStateList.valueOf(sky)
                    startService(Intent(this@MainActivity,EnetLoggerService::class.java)
                        .setAction(EnetLoggerService.ACTION_STOP))
                    dashboard.setLogging(false)
                }
            }
        }
        controls.addView(startButton,LinearLayout.LayoutParams(0,dp(54),1.15f).apply {
            rightMargin=dp(5)
        })
        // Compact overflow replaces the full-width footer button.
        controls.addView(Button(this).apply {
            text="⋮"
            textSize=26f
            isAllCaps=false
            contentDescription="Дополнительно"
            minWidth=0
            minHeight=dp(52)
            setPadding(0,0,0,dp(3))
            setTextColor(Color.WHITE)
            backgroundTintList=ColorStateList.valueOf(Color.rgb(41,60,84))
            setOnClickListener { showAdditionalMenu() }
        },LinearLayout.LayoutParams(dp(47),dp(54)))
        root.addView(controls)
        dashboard=OctaneDashboard(this)
        status=dashboard.connectionView
        val scroll=ScrollView(this).apply {
            setFillViewport(true)
            addView(dashboard)
        }
        root.addView(scroll,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,0,1f
        ))
        setContentView(root)
        statusReceiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context?,intent:Intent?) {
                if(intent?.action!=EnetLoggerService.ACTION_STATUS)return
                val message=intent.getStringExtra(EnetLoggerService.EXTRA_STATUS)?:return
                if(intent.getStringExtra(EnetLoggerService.EXTRA_STATE)==null) {
                    dashboard.setMessage(message)
                } else {
                    dashboard.render(intent)
                }
            }
        }
        val filter=IntentFilter(EnetLoggerService.ACTION_STATUS)
        if(Build.VERSION.SDK_INT>=33) {
            registerReceiver(statusReceiver,filter,RECEIVER_NOT_EXPORTED)
        } else {
            registerStatusReceiverPre33(filter)
        }
    }

    // Android < 13 has no RECEIVER_NOT_EXPORTED overload. The only caller
    // checks SDK_INT, and this receiver listens solely for our internal
    // app-scoped broadcasts sent with Intent.setPackage(packageName).
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerStatusReceiverPre33(filter:IntentFilter) {
        @Suppress("DEPRECATION")
        registerReceiver(statusReceiver,filter)
    }


    private fun showAdditionalMenu() {
        val items=arrayOf("Импорт BMW DME .PRG", "История замеров", "Журнал VXSCAN + Android",
            "События сети Android", "Только TCP-реконнекты",
            "Резервный режим DME (B+ / B / A)", "Производительность опроса",
            "Считать историю и адаптации DME (LOGGER STOP)",
            "Сохранённые снимки истории DME", "Фоновый DME Monitor • журнал")
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
                    3 -> showConnectionHistory("ANDROID")
                    4 -> showConnectionHistory("TCP")
                    5 -> showPollingModeDialog()
                    6 -> showPollingPerformance()
                    7 -> showDmeHistoryScan()
                    8 -> showDmeHistoryFiles()
                    9 -> showDmeMonitorLog()
                }
            }
            .setNegativeButton("Закрыть",null)
            .show()
    }

    private fun showDmeHistoryScan() {
        if(historyBusy) {
            Toast.makeText(this,"Сбор истории уже выполняется",Toast.LENGTH_LONG).show()
            return
        }
        if(logging || EnetLoggerService.isLoggerActive) {
            AlertDialog.Builder(this).setTitle("Сначала останови LOGGER")
                .setMessage("История DME считывается только после остановки логгера. Второе TCP-соединение VXSCAN одновременно не используется.")
                .setPositiveButton("Понятно",null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("История и адаптации DME • только чтение")
            .setMessage("Выполнять только на стоянке. Прочитаем кандидатные адаптации 0x554A/0x4A85/0x4A9D, список ошибок UDS 0x19 и доступные Freeze Frame/extended records (не более 12 DTC). Сохраним сырые ответы в отдельный CSV. Ошибки и адаптации НЕ удаляются и НЕ сбрасываются. Некоторые параметры могут не поддерживаться.")
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Считать") { _,_ ->
                if(logging || EnetLoggerService.isLoggerActive || historyBusy) {
                    Toast.makeText(this,"Останови LOGGER",Toast.LENGTH_LONG).show()
                } else startDmeHistoryScan()
            }.show()
    }

    private fun startDmeHistoryScan() {
        historyBusy=true
        historyCancel=false
        val progress=AlertDialog.Builder(this)
            .setTitle("DME • чтение истории")
            .setMessage("Поиск сети BMW…")
            .setNegativeButton("Отменить") { _,_ -> historyCancel=true }
            .create()
        progress.setCancelable(false)
        progress.show()
        executor.execute {
            try {
                val result=DmeHistoryReader(applicationContext).collect(
                    { historyCancel || Thread.currentThread().isInterrupted },
                    { text -> main.post {
                        if(!isFinishing && !isDestroyed && progress.isShowing)
                            progress.setMessage(text)
                    } }
                )
                main.post {
                    if(!isFinishing && !isDestroyed) {
                        if(progress.isShowing)progress.dismiss()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(if(result.successful)"История DME сохранена" else "Частичный журнал DME")
                            .setMessage(result.summary+"\n\nCSV: "+result.file.absolutePath+
                                "\n\nСырые данные ECU, не лабораторное измерение октанового числа.")
                            .setPositiveButton("Посмотреть") { _,_ -> showDmeHistoryFiles() }
                            .setNegativeButton("Закрыть",null).show()
                    }
                }
            }catch(error:Exception) {
                main.post {
                    if(!isFinishing && !isDestroyed) {
                        if(progress.isShowing)progress.dismiss()
                        AlertDialog.Builder(this@MainActivity).setTitle("История DME")
                            .setMessage(error.javaClass.simpleName+": "+(error.message ?: "ошибка"))
                            .setPositiveButton("OK",null).show()
                    }
                }
            }finally {
                historyBusy=false
            }
        }
    }

    private fun showDmeHistoryFiles() {
        val dir=getExternalFilesDir(null) ?: filesDir
        val files=dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("bmw_dme_history_v1727_") &&
                it.name.endsWith(".csv")
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if(files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("История DME")
                .setMessage("Сохранённых снимков ещё нет. Останови LOGGER и запусти чтение.")
                .setPositiveButton("OK",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Сохранённые снимки DME")
            .setItems(files.take(20).map { it.name }.toTypedArray()) { _,idx ->
                val file=files[idx]
                val contents=try {
                    file.bufferedReader().useLines { sequence ->
                        sequence.take(45).joinToString("\n") { it.take(340) }
                    }
                }catch(error:Exception){error.message ?: "Ошибка чтения"}
                val view=ScrollView(this).apply {
                    addView(TextView(this@MainActivity).apply {
                        text=file.absolutePath+"\n\n"+contents+
                            "\n\nВ CSV доступны полные необработанные ответы."
                        textSize=12f
                        setPadding(24,20,24,20)
                        setTextIsSelectable(true)
                    })
                }
                AlertDialog.Builder(this).setTitle(file.name).setView(view)
                    .setPositiveButton("Закрыть",null).show()
            }.setNegativeButton("Закрыть",null).show()
    }

    private fun showDmeMonitorLog() {
        val dir=getExternalFilesDir(null)?:filesDir
        val log=dir.listFiles()?.filter {
            it.isFile && it.name.startsWith("bmw_dme_monitor_v1728_") &&
                it.name.endsWith(".csv")
        }?.maxByOrNull { it.lastModified() }
        if(log==null) {
            AlertDialog.Builder(this).setTitle("Фоновый DME Monitor")
                .setMessage("Нет записей. Запусти LOGGER в B+ и дождись нескольких циклов на умеренной нагрузке.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val lines=try {
                log.useLines { it.take(6000).toList().takeLast(24) }
            }catch(_:Exception){emptyList<String>()}
            main.post {
                if(isFinishing || isDestroyed)return@post
                val text=log.absolutePath+"\n\n"+lines.joinToString("\n") { it.take(450) }+
                    "\n\nПолный CSV содержит сырые ответы и временные метки; " +
                    "значения адаптаций предварительные, Fuel Score не меняется."
                val view=ScrollView(this).apply {
                    addView(TextView(this@MainActivity).apply {
                        this.text=text
                        textSize=11f
                        setTextColor(Color.WHITE)
                        setTextIsSelectable(true)
                        setPadding(22,18,22,18)
                    })
                }
                AlertDialog.Builder(this).setTitle("Фоновый DME Monitor")
                    .setView(view).setPositiveButton("Закрыть",null).show()
            }
        }
    }

    private fun showPollingModeDialog() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val migrated=prefs.getBoolean(PollModePolicy.MIGRATED_KEY,false)
        val current=PollModePolicy.resolve(prefs.getString("poll_mode",null),migrated)
        if(!migrated) {
            prefs.edit().putString("poll_mode",current)
                .putBoolean(PollModePolicy.MIGRATED_KEY,true).commit()
        }
        val modes=PollModePolicy.manualModes
        val options=arrayOf(
            "B+ • основной, 8 DID + фоновый DME Monitor",
            "B • резервный, два пакета по 4 DID",
            "A • резервный, последовательный опрос"
        )
        val selected=modes.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("DME • опрос (по умолчанию B+)")
            .setSingleChoiceItems(options,selected) { dialog,which ->
                val mode=modes[which]
                prefs.edit().putString("poll_mode",mode)
                    .putBoolean(PollModePolicy.MIGRATED_KEY,true).apply()
                dialog.dismiss()
                Toast.makeText(this,
                    when(mode) {
                        "B+" -> "B+: 8 DID, фоновый DME Monitor; резерв B при отказе"
                        else -> "Резервный режим "+mode+" • переключение без перезапуска"
                    },Toast.LENGTH_LONG).show()
            }.setNegativeButton("Отмена",null).show()
    }

    private fun showPollingPerformance() {
        val dir=getExternalFilesDir(null)?:filesDir
        val recent=dir.listFiles()?.filter { it.isFile &&
            (it.name.startsWith("bmw_poll_benchmark_v1722_") ||
                it.name.startsWith("bmw_poll_benchmark_")) &&
                it.name.endsWith(".csv")
        }?.maxByOrNull { it.lastModified() }
        if(recent==null) {
            AlertDialog.Builder(this).setTitle("Производительность DME")
                .setMessage("Пока нет журнала. Запустите логгер и подождите несколько секунд.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val content=try { recent.useLines { it.toList().takeLast(20).joinToString("\n") } }
                catch(_:Exception) { "Не удалось прочитать CSV" }
            runOnUiThread {
                val scroller=ScrollView(this).apply {
                    setBackgroundColor(Color.rgb(12,19,32))
                }
                scroller.addView(TextView(this).apply {
                    text=recent.name+"\n\n"+content
                    textSize=11f
                    setTextColor(Color.WHITE)
                    setPadding(20,16,20,16)
                    setTextIsSelectable(true)
                })
                AlertDialog.Builder(this).setTitle("Замеры скорости • CSV")
                    .setView(scroller).setPositiveButton("OK",null).show()
            }
        }
    }

    private fun showConnectionHistory(filter:String="ALL") {
        val file=File(getExternalFilesDir(null)?:filesDir,"bmw_connection_events.csv")
        if(!file.isFile) {
            AlertDialog.Builder(this).setTitle("VXSCAN • соединения")
                .setMessage("Пока нет записей о соединениях.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val records=try {
                file.useLines { seq ->
                    seq.drop(1)
                        .filter { line ->
                            when(filter) {
                                "ANDROID" -> line.contains(",ANDROID_")
                                "TCP" -> !line.contains(",ANDROID_")
                                else -> true
                            }
                        }
                        .toList().takeLast(50)
                }
            }
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
                    .setTitle(when(filter) {
                        "ANDROID" -> "Android • события сети"
                        "TCP" -> "VXSCAN • TCP и реконнекты"
                        else -> "VXSCAN + Android • последние события"
                    })
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
        historyCancel=true
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

    // UI diagnostics share the same bounded, fail-closed HSFZ framing as the logger.
    private fun readFrames(input:InputStream, max:Int)=HsfzCodec.readFrames(input,max)
    private fun payload(frame:ByteArray)=HsfzCodec.payload(frame)

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
