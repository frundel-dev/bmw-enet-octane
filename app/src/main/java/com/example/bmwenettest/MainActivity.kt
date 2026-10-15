package com.example.bmwenettest

import android.Manifest
import android.content.pm.PackageManager
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
    private var localOnlyStatus:TextView?=null
    private var pendingLocalOnlySsid:String?=null
    private var pendingLocalOnlyPassword:String?=null
    private val LOCAL_ONLY_WIFI_PERMISSION_REQUEST=6212
    private var liveLearningRpm:Double? = null
    private var liveLearningLoad:Double? = null
    private var loggerControl:Button? = null
    private lateinit var guidedTitle:TextView
    private lateinit var guidedDetail:TextView
    private lateinit var guidedNext:TextView
    private lateinit var guidedProgress:ProgressBar
    private lateinit var guidedAction:Button
    private var guidedSnapshot:GuidedFuelTest.Overview?=null
    private var guidedUiRefresh:Runnable?=null
    private lateinit var guidedPanel:LinearLayout
    private var fuelOverviewDialog:AlertDialog?=null
    private lateinit var targetPointsOne:TextView
    private lateinit var targetPointsTwo:TextView
    private lateinit var targetProgressOne:ProgressBar
    private lateinit var targetProgressTwo:ProgressBar
    private lateinit var targetHint:TextView
    private lateinit var livePanelBorder:android.graphics.drawable.GradientDrawable
    private lateinit var liveMapValue:TextView
    private lateinit var liveRpmValue:TextView
    private lateinit var liveCue:TextView
    private lateinit var liveHold:ProgressBar
    private lateinit var octane2LiveStatus:TextView
    private lateinit var octane2LambdaStatus:TextView
    private var pendingOctane2Export:File?=null
    private val refuelStore by lazy { RefuelSessionStore(applicationContext) }
    private lateinit var pendingRefuelBanner:TextView
    private lateinit var pendingRefuelAction:Button
    private var recentVehicleSpeed:Double?=null
    private var recentSpeedAt=0L
    private var stoppedSinceMs=0L
    private var presentedRefuelId=0L
    private lateinit var homeFuelTitle:TextView
    private lateinit var homeFuelMeta:TextView
    private lateinit var homeConnection:TextView
    private lateinit var homeCaptureState:TextView
    private lateinit var homeNextMode:TextView
    private lateinit var homeTargetAState:TextView
    private lateinit var homeTargetBState:TextView
    private lateinit var analysisSummary:TextView
    private lateinit var diagnosticSummary:TextView
    private lateinit var highSpeedStatus:TextView
    private var highSpeedLastUpdateAt=0L
    private lateinit var navButtons:List<TextView>
    private lateinit var tabPages:List<android.view.View>
    private var selectedTab=0
    private var firstTarget=Octane2UiState.target(null)
    private var secondTarget=Octane2UiState.target(null)
    private var connection=Octane2UiState.Connection.DISCONNECTED
    private var transportLabel="ENET / VXSCAN"
    private var lastFastReceivedAt=0L
    private var latestRpm:Double?=null
    private var latestMap:Double?=null
    private var lastActualLambda:Double?=null
    private var lastRamState="LOCKED"
    private var lastPollHz:Double?=null
    private var lastReconnectCount=0
    private val surface=Color.rgb(23,34,52)
    private val accentBlue=Color.rgb(89,171,255)
    private val accentGreen=Color.rgb(75,219,183)
    private val tertiary=Color.rgb(158,175,196)
    private val fastStaleClear=Runnable {
        showFastTelemetry(null,null,null,-1,0L)
        lastActualLambda=null
        octane2LambdaStatus.text="Фактическая λ: нет свежих данных"
        if(logging && connection==Octane2UiState.Connection.CONNECTED)
            connection=Octane2UiState.Connection.CONNECTING
        renderHomeState()
    }

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
            text="BMW OCTANE 2.0"
            textSize=22f
            setTextColor(Color.WHITE)
            typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        heading.addView(TextView(this).apply {
            text="v2.0.1 LAB"
            textSize=13f
            setTextColor(sky)
        })
        root.addView(heading)
        root.addView(TextView(this).apply {
            text="G20 / B48   •   USB ENET / VXSCAN"
            textSize=12f
            setTextColor(muted)
            setPadding(0,dp(5),0,dp(5))
        })
        // Outside every ScrollView: DME and LOGGER are visible on all tabs.
        val headerStatus=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(0,0,0,dp(11))
        }
        homeConnection=TextView(this).apply {
            textSize=11f
            setTextColor(muted)
            includeFontPadding=false
        }
        headerStatus.addView(homeConnection,
            LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        homeCaptureState=TextView(this).apply {
            textSize=11f
            setTextColor(muted)
            gravity=Gravity.END
            includeFontPadding=false
        }
        headerStatus.addView(homeCaptureState,
            LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        root.addView(headerStatus)
        val controls=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        logging=EnetLoggerService.isLoggerActive
        connection=if(logging)Octane2UiState.Connection.CONNECTING else Octane2UiState.Connection.DISCONNECTED
        val modeButton=Button(this).apply {
            text="НОВАЯ ЗАПРАВКА"
            textSize=13f
            isAllCaps=false
            minHeight=dp(52)
            setTextColor(Color.WHITE)
            backgroundTintList=ColorStateList.valueOf(Color.rgb(41,60,84))
            setOnClickListener { showFuelTestOverview() }
        }
        controls.addView(modeButton,LinearLayout.LayoutParams(0,dp(54),1f).apply {
            rightMargin=dp(7)
        })
        val startButton=Button(this).apply {
            text=if(logging)"ОСТАНОВИТЬ" else "НАЧАТЬ СБОР"
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
                    val localOnly=VxscanLocalOnlyWifi.snapshot()
                    if(localOnly.enabled && localOnly.phase!=VxscanLocalOnlyWifi.Phase.READY) {
                        Toast.makeText(this@MainActivity,
                            "Local-only VXSCAN: сначала дождись статуса READY или отключи режим",
                            Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    // An initial session must have operator-confirmed fuel.
                    // Do not start the optimized poll coroutine beforehand.
                    if(!refuelStore.hasCurrentFuel()) {
                        showNewFuelTestDialog(startAfterInitialSelection=true)
                        return@setOnClickListener
                    }
                    logging=true
                    text="ОСТАНОВИТЬ"
                    connection=Octane2UiState.Connection.CONNECTING
                    renderHomeState()
                    backgroundTintList=ColorStateList.valueOf(Color.rgb(255,179,126))
                    dashboard.setLogging(true)
                    startForegroundService(Intent(this@MainActivity,EnetLoggerService::class.java)
                        .setAction(EnetLoggerService.ACTION_START))
                } else {
                    logging=false
                    text="НАЧАТЬ СБОР"
                    connection=Octane2UiState.Connection.DISCONNECTED
                    renderHomeState()
                    backgroundTintList=ColorStateList.valueOf(sky)
                    startService(Intent(this@MainActivity,EnetLoggerService::class.java)
                        .setAction(EnetLoggerService.ACTION_STOP))
                    dashboard.setLogging(false)
                    main.removeCallbacks(fastStaleClear)
                    showFastTelemetry(null,null,null,-1,0L)
                }
            }
        }
        loggerControl=startButton
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

        // Fixed in the visible area: no scrolling or 1.5-second score refresh
        // is needed to follow the next complete RPM/MAP diagnostic sample.
        val quickPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(10))
        }
        livePanelBorder=android.graphics.drawable.GradientDrawable().apply {
            setColor(Color.rgb(25,39,57))
            cornerRadius=dp(10).toFloat()
            setStroke(dp(2),Color.rgb(71,88,111))
        }
        quickPanel.background=livePanelBorder
        quickPanel.addView(TextView(this).apply {
            text="LIVE STEADY • данные DME"
            textSize=12f;setTextColor(muted)
            typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        })
        val liveRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
        }
        val mapColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        mapColumn.addView(TextView(this).apply {
            text="MAP ABS • кПа"
            textSize=12f;setTextColor(muted)
        })
        liveMapValue=TextView(this).apply {
            text="—";textSize=43f;setTextColor(Color.WHITE)
            typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
            includeFontPadding=false
        }
        mapColumn.addView(liveMapValue)
        liveRow.addView(mapColumn,LinearLayout.LayoutParams(0,-2,1.2f))
        val rpmColumn=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        rpmColumn.addView(TextView(this).apply {
            text="ОБОРОТЫ • об/мин"
            textSize=12f;setTextColor(muted)
        })
        liveRpmValue=TextView(this).apply {
            text="—";textSize=30f;setTextColor(Color.WHITE)
            typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
            includeFontPadding=false
        }
        rpmColumn.addView(liveRpmValue)
        liveRow.addView(rpmColumn,LinearLayout.LayoutParams(0,-2,1f))
        quickPanel.addView(liveRow)
        liveCue=TextView(this).apply {
            text="Ожидание свежих RPM / MAP от DME"
            textSize=12f;setTextColor(muted)
        }
        quickPanel.addView(liveCue)
        liveHold=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100;progress=0
            progressTintList=ColorStateList.valueOf(Color.rgb(75,219,183))
        }
        quickPanel.addView(liveHold,LinearLayout.LayoutParams(-1,dp(5)).apply {
            topMargin=dp(5)
        })
        octane2LiveStatus=TextView(this).apply {
            text="RAM RPM: заблокирован до подтверждения SWFL • резерв OBD"
            textSize=11f;setTextColor(sky)
            setPadding(0,dp(6),0,0)
        }
        quickPanel.addView(octane2LiveStatus)
        octane2LambdaStatus=TextView(this).apply {
            text="Фактическая λ: нет подтверждённых данных"
            textSize=11f;setTextColor(muted)
            setPadding(0,dp(3),0,0)
        }
        quickPanel.addView(octane2LambdaStatus)
        root.addView(quickPanel,LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(6);bottomMargin=dp(3)
        })

        // A compact permanent benchmark replaces the old full-size fuel-test
        // card. A fuel evaluation is available only after tapping "НОВАЯ ЗАПРАВКА".
        val targetPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(11),dp(6),dp(11),dp(8))
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(25,39,57));cornerRadius=dp(10).toFloat()
            }
        }
        targetPanel.addView(TextView(this).apply {
            text="НЕДОСТАЮЩИЕ STEADY • MAP 105–129 кПа"
            textSize=12f;setTextColor(sky)
            typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
        })
        fun targetRow(label:String):Pair<TextView,ProgressBar> {
            val header=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            header.addView(TextView(this).apply {
                text=label;textSize=12f;setTextColor(Color.WHITE)
            },LinearLayout.LayoutParams(0,-2,1f))
            val counts=TextView(this).apply {
                text="0 точек • 0 участков"
                textSize=11f;setTextColor(muted);gravity=Gravity.END
            }
            header.addView(counts)
            targetPanel.addView(header,LinearLayout.LayoutParams(-1,-2).apply {
                topMargin=dp(5)
            })
            val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
                max=100;progress=0
                progressTintList=ColorStateList.valueOf(sky)
            }
            targetPanel.addView(bar,LinearLayout.LayoutParams(-1,dp(6)))
            return counts to bar
        }
        val one=targetRow("2500–2999 об/мин")
        targetPointsOne=one.first;targetProgressOne=one.second
        val two=targetRow("3000–3499 об/мин")
        targetPointsTwo=two.first;targetProgressTwo=two.second
        targetHint=TextView(this).apply {
            text="Запусти обычный LOGGER в AUTO. Тест заправки не требуется."
            textSize=11f;setTextColor(muted)
            setPadding(0,dp(7),0,0)
        }
        targetPanel.addView(targetHint)
        root.addView(targetPanel,LinearLayout.LayoutParams(-1,-2).apply {
            topMargin=dp(5);bottomMargin=dp(4)
        })
        guidedPanel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(9))
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(25,39,57));cornerRadius=dp(10).toFloat()
            }
        }
        guidedTitle=TextView(this).apply {
            textSize=17f;setTextColor(Color.WHITE)
            text="Выбери заправленное топливо, чтобы начать тест"
        }
        guidedPanel.addView(guidedTitle)
        guidedDetail=TextView(this).apply {
            textSize=12f;setTextColor(muted)
            text="Текущая заправка пока не выбрана"
            setPadding(0,dp(5),0,dp(6))
        }
        guidedPanel.addView(guidedDetail)
        guidedProgress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100;progress=0;progressTintList=ColorStateList.valueOf(sky)
        }
        guidedPanel.addView(guidedProgress,LinearLayout.LayoutParams(-1,dp(9)))
        guidedNext=TextView(this).apply {
            textSize=12f;setTextColor(Color.WHITE)
            text="Подсказки по RPM и нагрузке появятся после начала записи."
            setPadding(0,dp(8),0,dp(5))
        }
        guidedPanel.addView(guidedNext)
        guidedAction=Button(this).apply {
            text="ПОСМОТРЕТЬ РЕЗУЛЬТАТ"
            textSize=12f;isAllCaps=false
            setOnClickListener { showGuidedResult() }
        }
        val guidedButtons=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        guidedButtons.addView(Button(this).apply {
            text="КАРТА RPM × LOAD"
            textSize=11f;isAllCaps=false
            setOnClickListener { showGuidedCoverageMap() }
        },LinearLayout.LayoutParams(0,dp(43),1f))
        guidedButtons.addView(guidedAction,LinearLayout.LayoutParams(0,dp(43),1f))
        guidedPanel.addView(guidedButtons)
        dashboard=OctaneDashboard(this)
        status=dashboard.connectionView
        // Use the original, live-connected views; only their hierarchy
        // changes. The diagnostic service, receiver and fuel storage remain.
        root.removeView(controls)
        // Refuelling is controlled from its own card, technical tools from Settings.
        controls.removeView(modeButton)
        if(controls.childCount>1)controls.removeViewAt(controls.childCount-1)
        root.removeView(quickPanel)
        root.removeView(targetPanel)
        quickPanel.removeView(octane2LiveStatus)
        quickPanel.removeView(octane2LambdaStatus)
        fun smallText(t:String,size:Float=13f,color:Int=Color.WHITE)=TextView(this).apply {
            text=t;textSize=size;setTextColor(color);includeFontPadding=false
        }
        fun card(title:String)=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(10),dp(12),dp(11))
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(surface);cornerRadius=dp(12).toFloat()
                setStroke(dp(1),Color.rgb(45,62,83))
            }
            addView(smallText(title,11f,tertiary))
        }
        fun section()=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(0,dp(4),0,dp(12))
        }
        fun addCard(parent:LinearLayout,child:android.view.View) {
            parent.addView(child,LinearLayout.LayoutParams(-1,-2).apply {
                bottomMargin=dp(9)
            })
        }
        fun action(text:String,block:()->Unit)=Button(this).apply {
            this.text=text;isAllCaps=false;textSize=12f;minHeight=dp(44)
            backgroundTintList=ColorStateList.valueOf(Color.rgb(41,60,84))
            setOnClickListener{block()}
        }
        val home=section()
        val fuelCard=card("ТЕКУЩАЯ ЗАПРАВКА")
        homeFuelTitle=smallText("Топливо не выбрано",19f)
        homeFuelMeta=smallText("Заправка не выбрана",12f,tertiary)
        fuelCard.addView(homeFuelTitle)
        fuelCard.addView(homeFuelMeta)
        fuelCard.addView(action("НОВАЯ ЗАПРАВКА"){showNewFuelTestDialog()})
        fuelCard.addView(action("ИЗМЕНИТЬ ТОПЛИВО"){editCurrentFuel()})
        addCard(home,fuelCard)
        // Keep the existing LOGGER START/STOP control visible near the top.
        // This reuses the same button and listener, without duplicate actions.
        addCard(home,controls)

        val pendingCard=card("ВОЗМОЖНАЯ НОВАЯ ЗАПРАВКА")
        pendingRefuelBanner=smallText("Ожидает подтверждения",13f,Color.rgb(255,194,102))
        pendingCard.addView(pendingRefuelBanner)
        pendingRefuelAction=action("ПОДТВЕРДИТЬ ЗАПРАВКУ") {
            showPendingRefuelDialog()
        }
        pendingCard.addView(pendingRefuelAction)
        addCard(home,pendingCard)
        pendingCard.visibility=android.view.View.GONE
        pendingRefuelBanner.tag=pendingCard

        // Measured coverage remains the canonical persisted SteadyTargets
        // sample/segment/trip counter; never infer it from UI timers.
        targetPanel.addView(smallText(
            "Критерий: 24 точки, 4 независимых участка, 2 запуска LOGGER на область.",
            10f,tertiary))
        homeTargetAState=smallText("2500–2999: не начато",11f,tertiary)
        homeTargetBState=smallText("3000–3499: не начато",11f,tertiary)
        targetPanel.addView(homeTargetAState)
        targetPanel.addView(homeTargetBState)
        addCard(home,targetPanel)

        val guidance=card("СЛЕДУЮЩИЙ РЕЖИМ")
        homeNextMode=smallText(
            "STEADY • 2500–2999 об/мин\nMAP 105–129 кПа",16f,Color.WHITE)
        guidance.addView(homeNextMode)
        addCard(home,guidance)
        addCard(home,quickPanel)

        val refuels=section()
        val refuelCard=card("ЗАПРАВКИ")
        refuelCard.addView(smallText(
            "Текущая заправка, её история и субъективная оценка — только здесь.",
            13f,tertiary))
        refuelCard.addView(action("СОЗДАТЬ ЗАПРАВКУ"){showNewFuelTestDialog()})
        refuelCard.addView(action("ТЕКУЩАЯ ЗАПРАВКА"){showFuelTestOverview()})
        refuelCard.addView(action("ИЗМЕНИТЬ ТОПЛИВО"){editCurrentFuel()})
        refuelCard.addView(action("ПРОВЕРИТЬ ОБНАРУЖЕННУЮ ЗАПРАВКУ"){
            showPendingRefuelDialog()
        })
        refuelCard.addView(action("ИСТОРИЯ ЗАПРАВОК"){showGuidedFuelHistory()})
        addCard(refuels,refuelCard)

        val analysis=section()
        val analysisCard=card("АНАЛИЗ ТОПЛИВА")
        analysisSummary=smallText(
            "2.0: недостаточно данных. Научно валидированного Fuel Score пока нет.",
            14f,Color.WHITE)
        analysisCard.addView(analysisSummary)
        analysisCard.addView(action("КАРТА RPM × LOAD • STEADY / ACCEL"){
            showGuidedCoverageMap()
        })
        analysisCard.addView(action("ПРЕДВАРИТЕЛЬНЫЙ АНАЛИЗ ЗАПРАВКИ"){
            showGuidedResult()
        })
        analysisCard.addView(action("СРАВНИТЬ 1.7 ↔ 2.0 И ЭКСПОРТИРОВАТЬ CSV"){
            showOctane2ComparisonFiles()
        })
        addCard(analysis,analysisCard)

        val diagnostics=section()
        val diagnosticCard=card("ДИАГНОСТИЧЕСКИЕ ИСТОЧНИКИ")
        diagnosticSummary=smallText("Нет свежих данных • RAM / DID / OBD",12f,tertiary)
        diagnosticCard.addView(diagnosticSummary)
        diagnosticCard.addView(octane2LiveStatus)
        diagnosticCard.addView(octane2LambdaStatus)
        addCard(diagnostics,diagnosticCard)
        val highSpeedCard=card("HIGH-SPEED ENGINE • EXPERIMENTAL")
        highSpeedStatus=smallText("Ожидаю профилирование DME • нет измерений",12f,tertiary)
        highSpeedCard.addView(highSpeedStatus)
        highSpeedCard.addView(action("ПРОФИЛЬ HIGH SPEED"){showHighSpeedProfileDialog()})
        highSpeedCard.addView(action("HIGH-SPEED BENCHMARK CSV"){showHighSpeedBenchmark()})
        addCard(diagnostics,highSpeedCard)
        val fastDidCard=card("FAST DID VALIDATION • READ-ONLY AUDIT")
        fastDidCard.addView(smallText(
            "BASELINE: активен • FAST_DID_TEST / FAST_BATCH_TEST: "+
            "заблокированы до документального подтверждения DME/SWFL. "+
            "Новые запросы к ECU не отправляются.",12f,tertiary))
        fastDidCard.addView(action("КАНДИДАТЫ DID И KNOCK-СЕМАНТИКА"){
            showFastDidCandidateAudit()
        })
        fastDidCard.addView(action("RAW FAST DID • CSV / OFFLINE"){
            showFastDidAuditCsv()
        })
        addCard(diagnostics,fastDidCard)
        diagnostics.addView(dashboard)

        val settings=section()
        val settingsCard=card("НАСТРОЙКИ")
        settingsCard.addView(smallText(
            "Параметры диагностики и резервные режимы. Изменения RAM/SWFL применяются после остановки сбора.",
            12f,tertiary))
        localOnlyStatus=smallText("Local-only VXSCAN: выключено",12f,tertiary)
        settingsCard.addView(localOnlyStatus)
        settingsCard.addView(action("VXSCAN + 4G/5G • LOCAL-ONLY WI-FI") {
            showLocalOnlyWifiDialog()
        })
        settingsCard.addView(action("RAM / SWFL • ПРОФИЛЬ"){showOctane2RamProfile()})
        settingsCard.addView(action("РЕЖИМ ОПРОСА DME"){showPollingModeDialog()})
        settingsCard.addView(action("HIGH-SPEED ПРОФИЛЬ"){showHighSpeedProfileDialog()})
        settingsCard.addView(action("OFFLINE REPLAY"){showObservationReplay()})
        settingsCard.addView(action("DME PRG • RAW OFFLINE (БЕЗ ECU)") { showPrgOfflineLab() })
        settingsCard.addView(action("ДОПОЛНИТЕЛЬНО • ДИАГНОСТИКА / ЭКСПОРТ"){
            showAdditionalMenu()
        })
        addCard(settings,settingsCard)

        tabPages=listOf(home,refuels,analysis,diagnostics,settings).map { section ->
            ScrollView(this).apply {
                isFillViewport=true
                isVerticalScrollBarEnabled=false
                addView(section)
            }
        }
        val holder=android.widget.FrameLayout(this)
        tabPages.forEach { view ->
            holder.addView(view,android.widget.FrameLayout.LayoutParams(-1,-1))
        }
        root.addView(holder,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.rgb(18,29,45))
                setStroke(dp(1),Color.rgb(45,62,83))
            }
        }
        navButtons=listOf("Главная","Заправки","Анализ","Диагностика","Настройки")
            .mapIndexed{index,label ->
                TextView(this).apply {
                    text=label;textSize=if(index==3)10f else 11f
                    gravity=Gravity.CENTER
                    isClickable=true
                    contentDescription="Раздел: $label"
                    setOnClickListener{selectTab(index)}
                    nav.addView(this,LinearLayout.LayoutParams(0,dp(52),1f))
                }
            }
        root.addView(nav,LinearLayout.LayoutParams(-1,dp(52)))
        setContentView(root)
        selectTab(savedInstanceState?.getInt("octane2_tab",0)?:0)
        dashboard.setLogging(logging)
        if(logging)startButton.backgroundTintList=
            ColorStateList.valueOf(Color.rgb(255,179,126))
        renderHomeState()
        statusReceiver=object:BroadcastReceiver() {
            override fun onReceive(context:Context?,intent:Intent?) {
                if(intent?.action==VxscanLocalOnlyWifi.ACTION_STATUS) {
                    refreshLocalOnlyWifiStatus()
                    return
                }
                if(intent?.action==EnetLoggerService.ACTION_OCTANE2) {
                    val state=intent.getStringExtra(EnetLoggerService.EXTRA_RAM_STATUS)
                        ?: "LOCKED"
                    val ram=intent.getDoubleExtra(EnetLoggerService.EXTRA_RAM_RPM,Double.NaN)
                    val obd=intent.getDoubleExtra(EnetLoggerService.EXTRA_OBD_RPM,Double.NaN)
                    lastRamState=state
                    octane2LiveStatus.text=if(ram.isFinite() && obd.isFinite())
                        "RAM $state • RAM ${ram.toInt()} / OBD ${obd.toInt()} об/мин"
                        else "RAM: $state • OBD сохранён как контроль"
                    val lambda=intent.getDoubleExtra(
                        EnetLoggerService.EXTRA_ACTUAL_LAMBDA,Double.NaN)
                    val pid=intent.getIntExtra(
                        EnetLoggerService.EXTRA_LAMBDA_SOURCE,-1)
                    lastActualLambda=lambda.takeIf{it.isFinite()}
                    octane2LambdaStatus.text=if(lambda.isFinite())
                        "Фактическая λ = ${"%.3f".format(java.util.Locale.US,lambda)} • PID 0x${pid.toString(16)}"
                        else "Фактическая λ недоступна • заданная смесь не заменяет датчик"
                    renderHomeState()
                    return
                }
                if(intent?.action==EnetLoggerService.ACTION_FAST_TELEMETRY) {
                    val r=intent.getDoubleExtra(EnetLoggerService.EXTRA_RPM,Double.NaN)
                    val m=intent.getDoubleExtra(EnetLoggerService.EXTRA_MAP,Double.NaN)
                    val l=intent.getDoubleExtra(EnetLoggerService.EXTRA_LOAD,Double.NaN)
                    if(logging && (r.isFinite() || m.isFinite()))
                        connection=Octane2UiState.Connection.CONNECTED
                    lastFastReceivedAt=SystemClock.elapsedRealtime()
                    latestRpm=r.takeIf{it.isFinite()}
                    latestMap=m.takeIf{it.isFinite()}
                    showFastTelemetry(r.takeIf{it.isFinite()},m.takeIf{it.isFinite()},
                        l.takeIf{it.isFinite()},
                        intent.getIntExtra(EnetLoggerService.EXTRA_STEADY_TARGET_INDEX,-1),
                        intent.getLongExtra(EnetLoggerService.EXTRA_STEADY_HOLD_MS,0L))
                    main.removeCallbacks(fastStaleClear)
                    main.postDelayed(fastStaleClear,2500L)
                    renderHomeState()
                    return
                }
                if(intent?.action==EnetLoggerService.ACTION_HIGH_SPEED_STATUS) {
                    if(::highSpeedStatus.isInitialized) {
                        highSpeedStatus.text=intent.getStringExtra(
                            EnetLoggerService.EXTRA_HIGH_SPEED_STATUS)
                            ?: "Нет измерений"
                        highSpeedLastUpdateAt=SystemClock.elapsedRealtime()
                    }
                    return
                }
                if(intent?.action!=EnetLoggerService.ACTION_STATUS)return
                val message=intent.getStringExtra(EnetLoggerService.EXTRA_STATUS)?:return
                val speedFresh=intent.hasExtra(EnetLoggerService.EXTRA_SPEED)
                if(speedFresh) {
                    val speed=intent.getDoubleExtra(EnetLoggerService.EXTRA_SPEED,Double.NaN)
                    recentVehicleSpeed=speed.takeIf{it.isFinite() && it>=0.0}
                    recentSpeedAt=SystemClock.elapsedRealtime()
                    if(recentVehicleSpeed==0.0) {
                        if(stoppedSinceMs==0L)stoppedSinceMs=recentSpeedAt
                    } else stoppedSinceMs=0L
                }
                val newTransport=intent.getStringExtra(EnetLoggerService.EXTRA_TRANSPORT)
                if(!newTransport.isNullOrBlank())transportLabel=newTransport
                lastPollHz=intent.takeIf{it.hasExtra(EnetLoggerService.EXTRA_HZ)}
                    ?.getDoubleExtra(EnetLoggerService.EXTRA_HZ,Double.NaN)
                    ?.takeIf{it.isFinite()}
                    ?:lastPollHz
                if(intent.hasExtra(EnetLoggerService.EXTRA_RECONNECTS))
                    lastReconnectCount=intent.getIntExtra(EnetLoggerService.EXTRA_RECONNECTS,0)
                when {
                    message.startsWith("ENET reconnect",true) ->
                        connection=Octane2UiState.Connection.RECONNECTING
                    message.startsWith("CONNECTED ",true) ->
                        connection=Octane2UiState.Connection.CONNECTED
                    message.startsWith("DISCOVERY ",true) ||
                    message.startsWith("GATEWAY ",true) ->
                        connection=Octane2UiState.Connection.CONNECTING
                    message.contains("No usable physical",true) ->
                        connection=Octane2UiState.Connection.ERROR
                }
                if(intent.hasExtra(EnetLoggerService.EXTRA_RPM))
                    liveLearningRpm=intent.getDoubleExtra(EnetLoggerService.EXTRA_RPM,0.0)
                if(intent.hasExtra(EnetLoggerService.EXTRA_LOAD))
                    liveLearningLoad=intent.getDoubleExtra(EnetLoggerService.EXTRA_LOAD,0.0)
                if(intent.hasExtra(EnetLoggerService.EXTRA_STEADY_TARGET_A)) {
                    updateTargetStatus(
                        intent.getIntArrayExtra(EnetLoggerService.EXTRA_STEADY_TARGET_A),
                        intent.getIntArrayExtra(EnetLoggerService.EXTRA_STEADY_TARGET_B),
                        intent.getStringExtra(EnetLoggerService.EXTRA_STEADY_TARGET_HINT))
                }
                if(intent.getStringExtra(EnetLoggerService.EXTRA_STATE)==null) {
                    dashboard.setMessage(message)
                } else {
                    dashboard.render(intent)
                }
                renderHomeState()
                if(refuelStore.hasPending())maybeOfferStationaryRefuel()
            }
        }
        val filter=IntentFilter(EnetLoggerService.ACTION_STATUS).apply {
            addAction(EnetLoggerService.ACTION_OCTANE2)
            addAction(EnetLoggerService.ACTION_FAST_TELEMETRY)
            addAction(EnetLoggerService.ACTION_HIGH_SPEED_STATUS)
        }
        if(Build.VERSION.SDK_INT>=33) {
            registerReceiver(statusReceiver,filter,RECEIVER_NOT_EXPORTED)
        } else {
            registerStatusReceiverPre33(filter)
        }
        // Restore previous samples without starting a DME connection.
        executor.execute {
            val session=prefs.getInt("fuel_session_id",1)
            val old=mutableListOf<SteadyTargets.Record>()
            try {
                val file=File(getExternalFilesDir(null)?:filesDir,SteadyTargets.filename(session))
                if(file.isFile)file.useLines { lines ->
                    lines.forEach { SteadyTargets.parse(it)?.let(old::add) }
                }
            }catch(_:Exception){}
            val scoped=Octane2UiState.recordsForCurrentRefuel(old,session,
                prefs.getLong(GuidedFuelTest.PREF_ID,0L),
                prefs.getInt(GuidedFuelTest.PREF_FUEL_SESSION,-1))
            val snap=SteadyTargets.Tracker(session,scoped).snapshot()
            runOnUiThread {
                if(!isFinishing && !isDestroyed)
                    updateTargetStatus(intArrayOf(snap.first.points,snap.first.segments,
                        snap.first.loggerStarts,snap.first.iatBins,snap.first.coveragePct),
                        intArrayOf(snap.second.points,snap.second.segments,
                        snap.second.loggerStarts,snap.second.iatBins,snap.second.coveragePct),
                        "История нового сбора восстановлена • LOGGER AUTO")
            }
        }
        guidedUiRefresh=object:Runnable {
            override fun run() {
                if(fuelOverviewDialog?.isShowing==true || selectedTab==0 ||
                    selectedTab==2) refreshGuidedPanel()
                main.postDelayed(this,4800L)
            }
        }
        main.post(guidedUiRefresh!!)
    }

    private fun selectTab(tab:Int) {
        if(!::tabPages.isInitialized)return
        selectedTab=tab.coerceIn(0,tabPages.lastIndex)
        tabPages.forEachIndexed{index,page ->
            page.visibility=if(index==selectedTab)android.view.View.VISIBLE
                else android.view.View.GONE
        }
        navButtons.forEachIndexed {index,button ->
            button.setTextColor(if(index==selectedTab)accentGreen else tertiary)
            button.typeface=Typeface.create("sans-serif-medium",
                if(index==selectedTab)Typeface.BOLD else Typeface.NORMAL)
            button.isSelected=index==selectedTab
        }
        renderHomeState()
    }

    private fun renderHomeState() {
        if(!::homeFuelTitle.isInitialized)return
        val pref=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val id=pref.getLong(GuidedFuelTest.PREF_ID,0L)
        val hasFuel=refuelStore.hasCurrentFuel()
        val grade=pref.getString(GuidedFuelTest.PREF_GRADE,"Топливо")?:"Топливо"
        val station=pref.getString(GuidedFuelTest.PREF_STATION,"")?:""
        homeFuelTitle.text=if(hasFuel)grade else "Топливо не выбрано"
        if(!logging)loggerControl?.text=if(hasFuel)"НАЧАТЬ СБОР"
            else "ВЫБРАТЬ ТОПЛИВО И НАЧАТЬ"
        val date=if(id>0L)java.text.DateFormat.getDateInstance(
            java.text.DateFormat.MEDIUM).format(java.util.Date(id)) else "—"
        homeFuelMeta.text=if(hasFuel)
            listOf(station.takeIf{it.isNotBlank()},date,
                "Сессия #"+pref.getInt("fuel_session_id",1))
                .filterNotNull().joinToString("  •  ")
            else "Создай заправку для независимого исследования"
        val headerLabels=Octane2HeaderStatus.labels(logging,connection)
        homeConnection.text=headerLabels.dme
        homeCaptureState.text=headerLabels.logger
        homeConnection.setTextColor(when(headerLabels.effectiveConnection) {
            Octane2UiState.Connection.CONNECTED -> accentGreen
            Octane2UiState.Connection.ERROR -> Color.rgb(255,134,117)
            Octane2UiState.Connection.DISCONNECTED -> tertiary
            else -> accentBlue
        })
        homeCaptureState.setTextColor(
            if(headerLabels.effectiveConnection==Octane2UiState.Connection.ERROR)
                Color.rgb(255,134,117) else tertiary)
        val paused=hasFuel && !pref.getBoolean(GuidedFuelTest.PREF_ACTIVE,false)
            && !pref.getBoolean(GuidedFuelTest.PREF_DONE,false)
        if(::pendingRefuelBanner.isInitialized) {
            val pending=refuelStore.pending()
            (pendingRefuelBanner.tag as? android.view.View)?.visibility=
                if(pending!=null)android.view.View.VISIBLE else android.view.View.GONE
            pendingRefuelBanner.text=if(pending==null)"" else
                "Бак: ${pending.from?.toInt()?.toString()?:"?"}% → "+
                "${pending.to?.toInt()?.toString()?:"?"}%\n"+
                "Это лишь предполагаемая заправка. Необходимо выбрать бензин."
            pendingRefuelAction.isEnabled=canConfirmRefuelNow()
            pendingRefuelAction.text=if(canConfirmRefuelNow())
                "УКАЗАТЬ ТОПЛИВО" else "ПОДТВЕРЖДЕНИЕ ПОСЛЕ ОСТАНОВКИ"
        }
        val guide=Octane2UiState.guidance(firstTarget,secondTarget,
            logging && connection==Octane2UiState.Connection.CONNECTED,
            paused || refuelStore.hasPending() ||
                pref.getFloat("mixing_remaining_km",0f)>0f)
        homeNextMode.text=guide.heading+"\n"+guide.instructions
        fun stageText(target:Octane2UiState.Target)=when(target.stage) {
            Octane2UiState.Stage.NOT_STARTED -> "НЕ НАЧАТО"
            Octane2UiState.Stage.COLLECTING -> "СОБИРАЕТСЯ"
            Octane2UiState.Stage.SUFFICIENT -> "ДОСТАТОЧНО ДАННЫХ"
        }
        homeTargetAState.text="2500–2999: "+stageText(firstTarget)
        homeTargetBState.text="3000–3499: "+stageText(secondTarget)
        homeTargetAState.setTextColor(if(firstTarget.sufficient)accentGreen else tertiary)
        homeTargetBState.setTextColor(if(secondTarget.sufficient)accentGreen else tertiary)
        if(::analysisSummary.isInitialized) {
            val fuelOverview=if(hasFuel)guidedSnapshot else null
            analysisSummary.text="BMW OCTANE 2.0 • НЕДОСТАТОЧНО ДАННЫХ\n"+
                "Подтверждённой числовой оценки топлива нет.\n"+
                "STEADY 2500–2999: ${firstTarget.points} точек / ${firstTarget.segments} участков / ${firstTarget.loggerStarts} запусков\n"+
                "STEADY 3000–3499: ${secondTarget.points} точек / ${secondTarget.segments} участков / ${secondTarget.loggerStarts} запусков\n"+
                "Текущий тест: ${fuelOverview?.progress?.toString()?.plus("%")?:"не начат"}."+
                " Сравнение 1.7 ↔ 2.0 доступно по диагностическому CSV.\n"+
                "Даже при достаточном покрытии результат не является измеренным RON."
        }
        if(::diagnosticSummary.isInitialized) {
            val rpm=latestRpm?.let{String.format(java.util.Locale.US,"%.0f",it)}?:"—"
            val map=latestMap?.let{String.format(java.util.Locale.US,"%.0f",it)}?:"—"
            diagnosticSummary.text="Транспорт: $transportLabel • RAM: $lastRamState\n"+
                "RPM: $rpm (RAM при валидации / OBD fallback) • MAP: $map кПа (OBD)\n"+
                "Детонация/зажигание: UDS DID • другие показатели: OBD / DID\n"+
                "Опрос: ${lastPollHz?.let{String.format(java.util.Locale.US,"%.1f Гц",it)}?:"—"}"+
                " • реконнекты: $lastReconnectCount\n"+
                "λ: ${lastActualLambda?.let{String.format(java.util.Locale.US,"%.3f",it)}?:"недоступна"}; "+
                "данные реальны только при свежих диагностических сообщениях."
        }
    }

    override fun onSaveInstanceState(outState:Bundle) {
        outState.putInt("octane2_tab",selectedTab)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if(::homeFuelTitle.isInitialized) {
            logging=EnetLoggerService.isLoggerActive
            if(!logging) {
                connection=Octane2UiState.Connection.DISCONNECTED
                latestRpm=null;latestMap=null;lastActualLambda=null
            } else if(lastFastReceivedAt==0L) {
                connection=Octane2UiState.Connection.CONNECTING
            }
            loggerControl?.let { button ->
                button.text=if(logging)"ОСТАНОВИТЬ" else "НАЧАТЬ СБОР"
                button.backgroundTintList=ColorStateList.valueOf(
                    if(logging)Color.rgb(255,179,126) else accentBlue)
            }
            renderHomeState()
            refreshLocalOnlyWifiStatus()
            refreshGuidedPanel()
            if(refuelStore.hasPending())maybeOfferStationaryRefuel()
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


    /**
     * PRG Signal Lab is an offline view only. It cannot acquire a TCP socket,
     * invoke Service, send UDS requests or alter a fuel-session baseline.
     */
    private fun showPrgOfflineLab() {
        val entries=DmePrgEvidenceLab.specs
        val names=entries.map { spec ->
            "0x"+spec.didHex+" • "+spec.label+
                if(spec.width==null)" • LOCKED" else
                    " • PRG; SWFL не проверен"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("DME8FF_R • PRG Signal Lab (OFFLINE)")
            .setMessage("22 определения PRG. Только ручная проверка уже сохранённого RAW. " +
                "Никаких новых DID/UDS запросов и никаких изменений Fuel Score.")
            .setPositiveButton("Выбрать DID") { _,_ ->
                AlertDialog.Builder(this)
                    .setTitle("PRG • выбери сигнал")
                    .setItems(names) { _,index ->
                        showPrgOfflineSample(entries[index])
                    }
                    .setNegativeButton("Закрыть",null)
                    .show()
            }
            .setNegativeButton("Закрыть",null)
            .show()
    }

    private fun showPrgOfflineSample(spec:DmePrgEvidenceLab.Spec) {
        if(spec.width==null) {
            AlertDialog.Builder(this)
                .setTitle("0x"+spec.didHex+" • OFFLINE LOCKED")
                .setMessage("PRG содержит структурированные результаты, но не доказана " +
                    "полная форма ответа этого ECU. Простой UDS 22 и авто-декодер " +
                    "запрещены. Никакого запроса DME не выполняется.")
                .setPositiveButton("OK",null).show()
            return
        }
        val input=EditText(this).apply {
            hint="Полный UDS payload без HSFZ: 62 "+spec.didHex.take(2)+
                " "+spec.didHex.takeLast(2)+" ..."
            setSingleLine(false)
            maxLines=4
            setPadding(24,18,24,18)
        }
        AlertDialog.Builder(this)
            .setTitle("0x"+spec.didHex+" • только OFFLINE")
            .setMessage(spec.label+"\nФормат PRG: "+spec.width+
                " байт; SWFL НЕ подтверждён. Вставь сохранённый UDS payload " +
                "с SID 0x62 или NRC 0x7F 0x22.")
            .setView(input)
            .setPositiveButton("Проверить offline") { _,_ ->
                val result=DmePrgEvidenceLab.interpretHex(
                    spec.did,input.text?.toString().orEmpty())
                val text="Статус: "+result.status.name+"\n"+
                    "RAW: "+result.rawHex.ifEmpty { "нет достоверного RAW" }+"\n"+
                    (result.value?.let { "Предварительное значение: "+it+"\n" } ?: "")+
                    "\n"+result.detail+"\n\n"+
                    "Не считается измерением с автомобиля без доказанного источника."
                AlertDialog.Builder(this)
                    .setTitle("PRG RAW • 0x"+spec.didHex)
                    .setMessage(text)
                    .setPositiveButton("Закрыть",null)
                    .show()
            }
            .setNegativeButton("Отмена",null)
            .show()
    }

    private fun showAdditionalMenu() {
        val items=arrayOf("Импорт BMW DME .PRG", "История замеров", "Журнал VXSCAN + Android",
            "События сети Android", "Только TCP-реконнекты",
            "Резервный режим DME (B+ / B / A)", "Производительность опроса",
            "Считать историю и адаптации DME (LOGGER STOP)",
            "Сохранённые снимки истории DME", "Фоновый DME Monitor • журнал",
            "Обучение эталона • RPM × нагрузка", "Режим замеров AUTO / TEST",
            "История тестов топлива", "Offline replay • наблюдения",
            "BMW OCTANE 2.0 • RAM/SWFL", "BMW OCTANE 2.0 • экспорт сравнения")
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
                    10 -> showBaselineLearning()
                    11 -> {
                        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
                        val next=if(prefs.getString("measurement_mode","AUTO")=="AUTO")"TEST" else "AUTO"
                        prefs.edit().putString("measurement_mode",next).apply()
                        Toast.makeText(this,"Режим: $next. Вступит в силу после перезапуска LOGGER.",Toast.LENGTH_LONG).show()
                    }
                    12 -> showGuidedFuelHistory()
                    13 -> showObservationReplay()
                    14 -> showOctane2RamProfile()
                    15 -> showOctane2ComparisonFiles()
                }
            }
            .setNegativeButton("Закрыть",null)
            .show()
    }

    /**
     * Deterministic replay of our own versioned per-FAST-cycle observations.
     * Never connects to DME and never changes Fuel Score or calibration.
     */
    private fun showOctane2RamProfile() {
        if(logging || EnetLoggerService.isLoggerActive) {
            AlertDialog.Builder(this).setTitle("Сначала останови LOGGER")
                .setMessage("Настройка RAM применяется только при следующем старте LOGGER.")
                .setPositiveButton("OK",null).show()
            return
        }
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        AlertDialog.Builder(this)
            .setTitle("RAM 0x23 • проверенный RPM Nkw")
            .setMessage("РАЗРЕШЕНО ТОЛЬКО при точном совпадении автомобиля с RAM LAB:\n"+
                "A2L R4C9J797B_5d55-070_060_007\n"+
                "SWFL 00005D55-070.060.007\n"+
                "MG1CS201 • Nkw 0x60003B64 (2 байта).\n\n"+
                "Идентификатор ПО автоматически не подтверждён. "+
                "После подтверждения сначала требуется 0 км/ч и проверка OBD RPM. "+
                "При отказе ECU RAM выключится, останется OBD. "+
                "Не включай после перепрошивки без повторной проверки.")
            .setPositiveButton("Подтверждаю точное совпадение") { _,_ ->
                prefs.edit().putBoolean("octane2_swfl_confirmed",true).apply()
                Toast.makeText(this,"RAM будет разрешён после проверки на холостом ходу",Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Отключить RAM") { _,_ ->
                prefs.edit().putBoolean("octane2_swfl_confirmed",false).apply()
                Toast.makeText(this,"Останется диагностический OBD режим",Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена",null).show()
    }

    private fun showOctane2ComparisonFiles() {
        if(logging || EnetLoggerService.isLoggerActive) {
            AlertDialog.Builder(this).setTitle("Экспорт после STOP LOGGER")
                .setMessage("Останови LOGGER для завершения и сброса буфера CSV.")
                .setPositiveButton("OK",null).show()
            return
        }
        val dir=getExternalFilesDir(null)?:filesDir
        val files=dir.listFiles()?.filter{it.isFile &&
            (it.name.startsWith("bmw_octane2_comparison_v1_") ||
                it.name.startsWith("bmw_observations_v1_") ||
                it.name.startsWith("bmw_steady_targets_v1732_")) &&
            it.extension=="csv"}?.sortedByDescending{it.lastModified()}?:emptyList()
        if(files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Нет CSV сравнения")
                .setMessage("Запусти и останови LOGGER, затем повтори.")
                .setPositiveButton("OK",null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Выбери CSV для экспорта")
            .setItems(files.take(40).map{it.name}.toTypedArray()) { _,index ->
                val chosen=files[index]
                AlertDialog.Builder(this)
                    .setTitle(chosen.name)
                    .setItems(arrayOf("Отчёт 1.7 ↔ 2.0 (если формат 2.0)",
                        "Экспорт CSV")) { _,action ->
                        if(action==0) {
                            if(!chosen.name.startsWith("bmw_octane2_comparison_v1_")) {
                                AlertDialog.Builder(this).setMessage(
                                    "Для парного отчёта необходим CSV сравнения OCTANE 2.0.")
                                    .setPositiveButton("OK",null).show()
                            }else executor.execute {
                                val text=try {
                                    chosen.useLines { lines ->
                                        Octane2ComparisonReport.analyze(lines).russianReport()
                                    }
                                }catch(e:Exception){"Ошибка CSV: ${e.message}"}
                                main.post {
                                    if(!isFinishing && !isDestroyed)
                                        AlertDialog.Builder(this)
                                            .setTitle("RAM ↔ OBD • одна поездка")
                                            .setMessage(text).setPositiveButton("Закрыть",null).show()
                                }
                            }
                        }else {
                            pendingOctane2Export=chosen
                            @Suppress("DEPRECATION")
                            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type="text/csv"
                                putExtra(Intent.EXTRA_TITLE,chosen.name)
                            },902)
                        }
                    }.show()
            }.setNegativeButton("Отмена",null).show()
    }

    private fun showObservationReplay() {
        if(logging || EnetLoggerService.isLoggerActive) {
            AlertDialog.Builder(this).setTitle("Offline replay")
                .setMessage("Останови LOGGER, чтобы журнал был полностью сохранён.")
                .setPositiveButton("OK",null).show()
            return
        }
        val dir=getExternalFilesDir(null)?:filesDir
        val files=dir.listFiles()?.filter { it.isFile &&
            it.name.startsWith("bmw_observations_v1_") && it.name.endsWith(".csv") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if(files.isEmpty()) {
            AlertDialog.Builder(this).setTitle("Offline replay")
                .setMessage("Журналов наблюдений пока нет. Запусти LOGGER, затем останови его.")
                .setPositiveButton("OK",null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Выбери журнал наблюдений")
            .setItems(files.take(30).map { it.name }.toTypedArray()) { _,index ->
                val file=files[index]
                executor.execute {
                    val outcome=try {
                        file.useLines { lines ->
                            Pair(DriveObservationReplay.run(lines),null as String?)
                        }
                    } catch(e:Exception) {
                        Pair(null,"Ошибка чтения: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    main.post {
                        if(isFinishing || isDestroyed)return@post
                        val summary=outcome.first
                        val message=if(summary==null) outcome.second ?: "Невозможно прочитать файл"
                        else "FAST циклов: ${summary.samples} • строк с ошибками: ${summary.malformed}\n"+
                            "STEADY: ${summary.steady} • ACCEL: ${summary.accel}\n"+
                            "TRANSIENT: ${summary.transient} • недостаточно данных: ${summary.insufficient}\n"+
                            "Целевые STEADY 2500–2999: ${summary.steadyTargetA}\n"+
                            "Целевые STEADY 3000–3499: ${summary.steadyTargetB}\n"+
                            "Реконнекты: ${summary.reconnects} • сессий топлива: ${summary.sessions}\n"+
                            "Запусков LOGGER: ${summary.loggerStarts}\n\n"+
                            "Экспериментальная классификация; эти циклы НЕ являются независимыми измерениями, подтверждённым покрытием или оценкой RON."
                        AlertDialog.Builder(this).setTitle("Offline replay • ${file.name}")
                            .setMessage(message).setPositiveButton("Закрыть",null).show()
                    }
                }
            }.setNegativeButton("Закрыть",null).show()
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


    /**
     * Baseline-collection dashboard. Reads the CSV on a background thread;
     * no socket access and no DME requests from the Activity.
     *
     * A dataset is observational, fuel-scoped, and NEVER activated as the
     * frozen Fuel Score or a measured RON value.
     */


    private fun showFastTelemetry(rpm:Double?,map:Double?,load:Double?,
                                  targetIndex:Int,holdMs:Long) {
        if(!::liveMapValue.isInitialized)return
        val cue=SteadyLiveCue.evaluate(rpm,map,targetIndex,holdMs)
        liveMapValue.text=map?.let {
            String.format(java.util.Locale.getDefault(),"%.0f",it)
        }?:"—"
        liveRpmValue.text=rpm?.let {
            String.format(java.util.Locale.getDefault(),"%.0f",it)
        }?:"—"
        val accent=when(cue.band) {
            SteadyLiveCue.Band.TARGET_MAP -> Color.rgb(75,219,183)
            SteadyLiveCue.Band.HIGH_MAP -> Color.rgb(255,134,117)
            SteadyLiveCue.Band.LOW_MAP -> Color.rgb(89,171,255)
            else -> Color.rgb(158,175,196)
        }
        livePanelBorder.setStroke(
            (2*resources.displayMetrics.density+0.5f).toInt().coerceAtLeast(1),accent)
        liveMapValue.setTextColor(accent)
        liveCue.text=cue.description
        liveCue.setTextColor(accent)
        liveHold.progress=cue.holdPct
        latestRpm=rpm
        latestMap=map
        dashboard.updateFastReadings(rpm,map,load)
        renderHomeState()
    }

    private fun updateTargetStatus(a:IntArray?,b:IntArray?,hint:String?) {
        if(!::targetPointsOne.isInitialized || a==null || b==null ||
            a.size<5 || b.size<5)return
        fun textFor(v:IntArray)=
            "${v[0]} т. • ${v[1]} уч. • ${v[2]} зап. • ${v[4]}%"
        targetPointsOne.text=textFor(a)
        targetPointsTwo.text=textFor(b)
        targetProgressOne.progress=a[4].coerceIn(0,100)
        targetProgressTwo.progress=b[4].coerceIn(0,100)
        targetHint.text=hint?:"Ожидаю устойчивые RPM и MAP"
        firstTarget=Octane2UiState.target(a)
        secondTarget=Octane2UiState.target(b)
        renderHomeState()
    }

    private fun showFuelTestOverview() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        if(prefs.getLong(GuidedFuelTest.PREF_ID,0L)<=0L) {
            showNewFuelTestDialog()
            return
        }
        // The reusable card is attached to a dialog only. Never leave it
        // mounted on the main screen, even after rotation/reopening.
        (guidedPanel.parent as? android.view.ViewGroup)?.removeView(guidedPanel)
        fuelOverviewDialog=AlertDialog.Builder(this)
            .setTitle("Оценка текущей заправки")
            .setView(guidedPanel)
            .setNeutralButton("Новая заправка") { _,_->
                fuelOverviewDialog=null
                showNewFuelTestDialog()
            }
            .setPositiveButton("Закрыть",null)
            .create().also { dialog->
                dialog.setOnDismissListener { fuelOverviewDialog=null }
                dialog.show()
            }
        refreshGuidedPanel()
    }

    private fun canConfirmRefuelNow():Boolean {
        if(!EnetLoggerService.isLoggerActive)return true
        return recentVehicleSpeed==0.0 &&
            SystemClock.elapsedRealtime()-recentSpeedAt in 0L..4000L
    }

    private fun maybeOfferStationaryRefuel() {
        val pending=refuelStore.pending()?:return
        // Without live DME speed, never infer the car is stationary:
        // keep a persistent banner and wait for a deliberate user action.
        if(!EnetLoggerService.isLoggerActive || !canConfirmRefuelNow() ||
            presentedRefuelId==pending.id || isFinishing || isDestroyed ||
            stoppedSinceMs<=0L ||
            SystemClock.elapsedRealtime()-stoppedSinceMs<15000L)
            return
        presentedRefuelId=pending.id
        main.post {
            if(!isFinishing && !isDestroyed &&
                refuelStore.pending()?.id==pending.id && canConfirmRefuelNow())
                showPendingRefuelDialog()
        }
    }

    private fun showPendingRefuelDialog() {
        val pending=refuelStore.pending()
        if(pending==null) {
            Toast.makeText(this,"Ожидающих заправок нет",Toast.LENGTH_SHORT).show()
            renderHomeState()
            return
        }
        if(!canConfirmRefuelNow()) {
            Toast.makeText(this,"Подтверждение доступно только после остановки",
                Toast.LENGTH_LONG).show()
            return
        }
        showRefuelEditor(pending)
    }

    private fun showNewFuelTestDialog(startAfterInitialSelection:Boolean=false) {
        if(!canConfirmRefuelNow()) {
            Toast.makeText(this,"Создай заправку после остановки автомобиля",
                Toast.LENGTH_LONG).show()
            return
        }
        // Manual and automatic creation always call the same state transition.
        showRefuelEditor(refuelStore.pending(),manual=true,
            startAfterInitialSelection=startAfterInitialSelection)
    }

    private fun showRefuelEditor(
        pending:RefuelSessionStore.Pending?,manual:Boolean=false,
        startAfterInitialSelection:Boolean=false
    ) {
        if(!canConfirmRefuelNow())return
        val grades=GuidedFuelTest.selectableGrades
        val parent=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(24,12,24,4)
        }
        if(pending!=null)parent.addView(TextView(this).apply {
            text="Бак до: ${pending.from?.let{"%.0f%%".format(java.util.Locale.US,it)}?:"—"}"+
                "   •   после: ${pending.to?.let{"%.0f%%".format(java.util.Locale.US,it)}?:"—"}\n"+
                "Уровень топлива — косвенное предположение. Марку бензина выбирает пользователь."
            textSize=13f
        })
        parent.addView(TextView(this).apply{
            text="Заявленная марка бензина";textSize=13f
        })
        val grade=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,grades)
            setSelection(grades.indexOf("АИ-95").coerceAtLeast(0))
        }
        parent.addView(grade)
        val station=EditText(this).apply {
            hint="АЗС или примечание (необязательно)"
            setSingleLine(true)
            setTextSize(14f)
        }
        parent.addView(station)
        parent.addView(TextView(this).apply{
            text="Субъективная оценка (не влияет на диагностику)"
            textSize=12f
        })
        val options=listOf("Без оценки","Понравилось","Нейтрально","Не понравилось")
        val rating=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,options)
        }
        parent.addView(rating)
        val firstSelection=!refuelStore.hasCurrentFuel() && pending==null
        val dialog=AlertDialog.Builder(this)
            .setTitle(when {
                pending!=null -> "Обнаружена новая заправка"
                firstSelection -> "Укажите текущее топливо"
                else -> "Новая заправка"
            })
            .setView(parent)
            .setMessage(if(firstSelection)
                "Первый выбор создаёт исходную сессию №1. "+
                    "Это не новая заправка: MIXING не назначается. "+
                    "Укажите бензин, который уже находится в баке."
                else "После подтверждения создаётся отдельная сессия. "+
                    "Переходный период смешивания исключён из оценок; "+
                    "5 км — эвристика, не доказательство полного перемешивания.")
            .setPositiveButton("Подтвердить и начать анализ",null)
            .setNegativeButton(if(pending!=null)"Заправки не было" else "Отмена",null)
            .setNeutralButton("Позже",null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if(!canConfirmRefuelNow()) {
                    Toast.makeText(this,"Сначала останови автомобиль",Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val confirmed=refuelStore.confirm(
                    grades[grade.selectedItemPosition],station.text.toString(),
                    options[rating.selectedItemPosition],
                    manual=manual || pending==null)
                if(confirmed==null) {
                    Toast.makeText(this,"Не удалось сохранить заправку. Попробуй ещё раз.",
                        Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                presentedRefuelId=confirmed.id
                guidedSnapshot=null
                updateTargetStatus(intArrayOf(0,0,0,0,0),intArrayOf(0,0,0,0,0),
                    "Новое топливо • MIXING • ожидаем пригодные измерения")
                refreshGuidedPanel()
                selectTab(0)
                renderHomeState()
                Toast.makeText(this,if(firstSelection)
                    "Топливо сохранено. Исходная сессия готова к сбору."
                    else "Заправка подтверждена. Анализ продолжится без второго "+
                        "соединения; переходные измерения исключаются.",
                    Toast.LENGTH_LONG).show()
                if(firstSelection && startAfterInitialSelection) {
                    main.post {
                        if(!isFinishing && !isDestroyed &&
                            !EnetLoggerService.isLoggerActive &&
                            refuelStore.hasCurrentFuel())loggerControl?.performClick()
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                if(pending!=null) {
                    val rejected=refuelStore.reject(pending.id)
                    if(!rejected) {
                        Toast.makeText(this,"Событие уже изменилось",Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    renderHomeState()
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun editCurrentFuel() {
        val id=getSharedPreferences("bmw_native",MODE_PRIVATE)
            .getLong(GuidedFuelTest.PREF_ID,0L)
        if(id<=0L) {
            Toast.makeText(this,"Сначала создай заправку",Toast.LENGTH_SHORT).show()
            return
        }
        editFuelMetadata(id)
    }

    private fun editFuelMetadata(id:Long) {
        if(!canConfirmRefuelNow()) {
            Toast.makeText(this,"Редактирование после остановки",Toast.LENGTH_LONG).show()
            return
        }
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val gradeValue=prefs.getString(RefuelSessionStore.PREF_META_GRADE_PREFIX+id,
            if(prefs.getLong(GuidedFuelTest.PREF_ID,0L)==id)
                prefs.getString(GuidedFuelTest.PREF_GRADE,"АИ-95") else "АИ-95")
            ?:"АИ-95"
        // Preserve the exact stored legacy grade during metadata edits.
        val options=(GuidedFuelTest.selectableGrades+
            listOf(gradeValue).filter{
                it in GuidedFuelTest.grades && it !in GuidedFuelTest.selectableGrades
            }).distinct()
        val stationValue=prefs.getString(RefuelSessionStore.PREF_META_STATION_PREFIX+id,
            if(prefs.getLong(GuidedFuelTest.PREF_ID,0L)==id)
                prefs.getString(GuidedFuelTest.PREF_STATION,"") else "")?:""
        val parent=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(24,16,24,6)
        }
        parent.addView(TextView(this).apply {
            text="Меняются только метаданные. ID, CSV и прогресс сохраняются."
        })
        val grade=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,options)
            setSelection(options.indexOf(gradeValue).coerceAtLeast(0))
        }
        parent.addView(grade)
        val station=EditText(this).apply {
            hint="АЗС / примечание";setSingleLine(true);setText(stationValue)
        }
        parent.addView(station)
        val ratings=listOf("Без оценки","Понравилось","Нейтрально","Не понравилось")
        val rating=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,ratings)
            val old=prefs.getString(RefuelSessionStore.PREF_RATING_PREFIX+id,"Без оценки")
            setSelection(ratings.indexOf(old).coerceAtLeast(0))
        }
        parent.addView(rating)
        val dialog=AlertDialog.Builder(this).setTitle("Изменить топливо")
            .setView(parent).setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val success=refuelStore.editFuel(id,
                    options[grade.selectedItemPosition],station.text.toString(),
                    ratings[rating.selectedItemPosition])
                if(success) {
                    dialog.dismiss();refreshGuidedPanel();renderHomeState()
                } else Toast.makeText(this,"Не удалось сохранить",Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun readGuided(id:Long,fuelSession:Int):GuidedFuelTest.Coverage {
        val coverage=GuidedFuelTest.Coverage()
        if(id<=0)return coverage
        val file=File(getExternalFilesDir(null)?:filesDir,GuidedFuelTest.filename(id))
        if(file.isFile)try {
            file.useLines { lines ->
                lines.forEach { line ->
                    BaselineLearning.parse(line)?.let { point ->
                        if(point.fuelSessionId==fuelSession)coverage.accept(point)
                    }
                }
            }
        }catch(_:Exception){}
        return coverage
    }

    private fun refreshGuidedPanel() {
        if(!::guidedTitle.isInitialized)return
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val id=prefs.getLong(GuidedFuelTest.PREF_ID,0L)
        if(id<=0L) {
            guidedTitle.text="Новая заправка → Тестирование → Результат"
            guidedDetail.text="Нажми «НОВАЯ ЗАПРАВКА» и выбери топливо"
            guidedProgress.progress=0
            guidedNext.text="Приложение само подскажет недостающие RPM и нагрузку."
            guidedAction.isEnabled=false
            return
        }
        val fuel=prefs.getInt(GuidedFuelTest.PREF_FUEL_SESSION,-1)
        val grade=prefs.getString(GuidedFuelTest.PREF_GRADE,"Другое")?:"Другое"
        val station=prefs.getString(GuidedFuelTest.PREF_STATION,"")?:""
        val active=prefs.getBoolean(GuidedFuelTest.PREF_ACTIVE,false)
        val done=prefs.getBoolean(GuidedFuelTest.PREF_DONE,false)
        guidedTitle.text=grade+(if(station.isNotBlank())" • $station" else "")
        guidedDetail.text="Чтение данных теста…"
        executor.execute {
            val state=readGuided(id,fuel).snapshot()
            runOnUiThread {
                if(isFinishing || isDestroyed ||
                    prefs.getLong(GuidedFuelTest.PREF_ID,0L)!=id)return@runOnUiThread
                guidedSnapshot=state
                guidedProgress.progress=state.progress
                renderHomeState()
                guidedDetail.text=when {
                    fuel!=prefs.getInt("fuel_session_id",1) -> "Заправка изменилась • тест остановлен"
                    done -> "Измерения завершены • STEADY ${state.steadyReady}/2 · ACCEL ${state.accelReady}/3"
                    active -> "Сбор ${state.progress}% • STEADY ${state.steadyReady}/2 · ACCEL ${state.accelReady}/3"
                    else -> "Тест на паузе • STEADY ${state.steadyReady}/2 · ACCEL ${state.accelReady}/3"
                }
                guidedNext.text=if(done) "Результат предварительный: сравнение с архивным профилем, не измерение RON."
                    else state.nextStep
                guidedAction.isEnabled=true
                guidedAction.text=if(done)"РЕЗУЛЬТАТ ТЕСТА"
                    else "ПРОГРЕСС И РЕЗУЛЬТАТ"
            }
        }
    }


    private fun showGuidedCoverageMap() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val id=prefs.getLong(GuidedFuelTest.PREF_ID,0L)
        if(id<=0L) {
            Toast.makeText(this,"Сначала выбери новую заправку",Toast.LENGTH_SHORT).show()
            return
        }
        val fuel=prefs.getInt(GuidedFuelTest.PREF_FUEL_SESSION,-1)
        executor.execute {
            val c=readGuided(id,fuel)
            runOnUiThread {
                if(isFinishing || isDestroyed ||
                    prefs.getLong(GuidedFuelTest.PREF_ID,0L)!=id)return@runOnUiThread
                val dp={v:Int->(resources.displayMetrics.density*v+0.5f).toInt()}
                val container=LinearLayout(this).apply {
                    orientation=LinearLayout.VERTICAL
                    setPadding(dp(9),dp(7),dp(9),dp(8))
                }
                fun section(phase:BaselineLearning.Phase) {
                    val rpms=if(phase==BaselineLearning.Phase.STEADY)
                        BaselineLearning.steadyRpm else BaselineLearning.accelRpm
                    val loads=if(phase==BaselineLearning.Phase.STEADY)
                        BaselineLearning.steadyLoad else BaselineLearning.accelLoad
                    container.addView(TextView(this).apply {
                        text=phase.name+" • RPM × нагрузка"
                        textSize=15f;setTextColor(Color.WHITE)
                        setPadding(0,dp(12),0,dp(4))
                    })
                    val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                    header.addView(TextView(this).apply{
                        text="RPM";textSize=11f;setTextColor(Color.LTGRAY)
                    },LinearLayout.LayoutParams(dp(88),dp(27)))
                    for(j in 0 until loads.size-1) {
                        header.addView(TextView(this).apply{
                            text="${loads[j]}–${loads[j+1]-1}%"
                            textSize=11f;setTextColor(Color.LTGRAY)
                            gravity=Gravity.CENTER
                        },LinearLayout.LayoutParams(0,dp(27),1f))
                    }
                    container.addView(header)
                    for(i in 0 until rpms.size-1) {
                        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                        row.addView(TextView(this).apply {
                            text="${rpms[i]}–${rpms[i+1]-1}"
                            textSize=10f;setTextColor(Color.WHITE)
                            gravity=Gravity.CENTER_VERTICAL
                        },LinearLayout.LayoutParams(dp(88),dp(42)))
                        for(j in 0 until loads.size-1) {
                            val progress=c.cellProgress(BaselineLearning.Cell(phase,i,j))
                            val bg=android.graphics.drawable.GradientDrawable().apply {
                                setColor(when {
                                    progress.reached->Color.rgb(29,117,99)
                                    progress.count>0->Color.rgb(50,95,139)
                                    else->Color.rgb(49,59,74)
                                })
                                cornerRadius=dp(5).toFloat()
                            }
                            row.addView(TextView(this).apply {
                                text=if(progress.count==0)"—"
                                    else "${progress.count} / ${progress.segments}с"
                                textSize=11f
                                setTextColor(Color.WHITE)
                                gravity=Gravity.CENTER
                                background=bg
                            },LinearLayout.LayoutParams(0,dp(38),1f).apply {
                                setMargins(dp(2),dp(2),dp(2),dp(2))
                            })
                        }
                        container.addView(row)
                    }
                }
                section(BaselineLearning.Phase.STEADY)
                section(BaselineLearning.Phase.ACCEL)
                container.addView(TextView(this).apply {
                    text="Ячейка: точки / участки. Зелёный — 4+ точки "+
                        "из 2+ участков; это только заполнение, не качество топлива. "+
                        "ACCEL группируется по RPM для остановки теста. "+
                        "Показаны данные именно текущей заправки."
                    textSize=12f;setTextColor(Color.LTGRAY)
                    setPadding(0,dp(12),0,0)
                })
                val scroll=ScrollView(this).apply { addView(container) }
                AlertDialog.Builder(this)
                    .setTitle("Карта сбора текущей заправки")
                    .setView(scroll)
                    .setPositiveButton("Закрыть",null).show()
            }
        }
    }

    private fun showGuidedResult() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val id=prefs.getLong(GuidedFuelTest.PREF_ID,0L)
        val snap=guidedSnapshot
        if(id<=0 || snap==null) {
            Toast.makeText(this,"Данные теста пока не доступны",Toast.LENGTH_SHORT).show()
            return
        }
        val fuel=prefs.getInt(GuidedFuelTest.PREF_FUEL_SESSION,-1)
        val changed=fuel!=prefs.getInt("fuel_session_id",1)
        val finished=prefs.getBoolean(GuidedFuelTest.PREF_DONE,false)
        val state=when {
            changed -> "Топливная сессия сменилась — дальнейшая запись запрещена."
            !snap.captureReady -> "Измерений для завершения автоматического теста пока недостаточно."
            else -> "Объём измерений для предварительного сравнения достигнут."
        }
        val delta=snap.relativeKnockDeltaPct
        val compare=if(delta!=null && snap.captureReady)
            "Относительный сигнал детонации: "+
                String.format(java.util.Locale.US,"%+.1f%%",delta)+
                " к архивному профилю АИ-95 в сравнимых диапазонах RPM/MAP. "+
                "Это отклонение сигнала, НЕ разница октанового числа и НЕ "+
                "подтверждённая оценка качества топлива."
        else "Сравнение с независимой базой пока невозможно: "+
            "недостаточно принятых ACCEL-сегментов и диапазонов."
        val dialog=AlertDialog.Builder(this)
            .setTitle("Результат • "+(prefs.getString(GuidedFuelTest.PREF_GRADE,"Бензин")?:"Бензин"))
            .setMessage("$state\n\nПрогресс сбора: ${snap.progress}%\n"+
                "STEADY: ${snap.steadyReady}/2 ячеек, ${snap.steadyPoints} точек\n"+
                "ACCEL: ${snap.accelReady}/3 областей, ${snap.accelPoints} точек\n"+
                "Сравнимых ACCEL областей: ${snap.comparableAccelBins}\n\n"+
                "$compare\n\n"+
                "Реальное качество и лабораторный RON по этим данным не определены. "+
                "Без проверенного внешнего топлива эталон нельзя считать абсолютным.")
            .setPositiveButton("Закрыть",null)
        if(!finished && !changed) {
            dialog.setNeutralButton(if(prefs.getBoolean(GuidedFuelTest.PREF_ACTIVE,false))
                "Пауза" else "Продолжить") { _,_->
                prefs.edit().putBoolean(GuidedFuelTest.PREF_ACTIVE,
                    !prefs.getBoolean(GuidedFuelTest.PREF_ACTIVE,false)).apply()
                refreshGuidedPanel()
            }
            dialog.setNegativeButton("Завершить сбор") { _,_->
                AlertDialog.Builder(this)
                    .setTitle("Завершить текущий тест?")
                    .setMessage("Недостающие области останутся непокрытыми. "+
                        "История и CSV сохранятся; это не подтвердит качество топлива.")
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Завершить") { _,_->
                        prefs.edit().putBoolean(GuidedFuelTest.PREF_ACTIVE,false)
                            .putBoolean(GuidedFuelTest.PREF_DONE,true).apply()
                        refreshGuidedPanel()
                    }.show()
            }
        }
        dialog.show()
    }

    private fun showGuidedFuelHistory() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val dir=getExternalFilesDir(null)?:filesDir
        val legacyIds=dir.listFiles()?.mapNotNull { f ->
            if(f.isFile && f.name.startsWith("bmw_fuel_test_v1731_") &&
                f.name.endsWith(".csv"))
                f.name.removePrefix("bmw_fuel_test_v1731_")
                    .removeSuffix(".csv").toLongOrNull()
            else null
        }.orEmpty()
        val confirmedIds=prefs.all.keys.mapNotNull { key ->
            if(key.startsWith(RefuelSessionStore.PREF_META_GRADE_PREFIX))
                key.removePrefix(RefuelSessionStore.PREF_META_GRADE_PREFIX).toLongOrNull()
            else null
        }
        val currentId=prefs.getLong(GuidedFuelTest.PREF_ID,0L)
        val ids=(legacyIds+confirmedIds+listOf(currentId).filter{it>0L})
            .distinct().sortedDescending().take(50)
        if(ids.isEmpty()) {
            AlertDialog.Builder(this).setTitle("История заправок")
                .setMessage("Подтверждённых заправок пока нет.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val summaries=ids.map { id ->
                try {
                    val file=File(dir,GuidedFuelTest.filename(id))
                    val metadata=if(file.isFile)file.useLines{it.firstOrNull()}?:"" else ""
                    val current=id==prefs.getLong(GuidedFuelTest.PREF_ID,-1L)
                    val oldSession=Regex("fuel_session=(\\d+)")
                        .find(metadata)?.groupValues?.get(1)?.toIntOrNull()?:-1
                    val fuelSession=prefs.getInt(
                        RefuelSessionStore.PREF_META_SESSION_PREFIX+id,
                        if(current)prefs.getInt(GuidedFuelTest.PREF_FUEL_SESSION,-1)
                        else oldSession)
                    val historicalGrade=Regex("grade=([^;]+)")
                        .find(metadata)?.groupValues?.get(1)?:"Топливо"
                    val grade=prefs.getString(RefuelSessionStore.PREF_META_GRADE_PREFIX+id,
                        if(current)prefs.getString(GuidedFuelTest.PREF_GRADE,historicalGrade)
                            else historicalGrade) ?:historicalGrade
                    val station=prefs.getString(RefuelSessionStore.PREF_META_STATION_PREFIX+id,
                        if(current)prefs.getString(GuidedFuelTest.PREF_STATION,"") else "")?:""
                    val rating=prefs.getString(RefuelSessionStore.PREF_RATING_PREFIX+id,"")?:""
                    val progress=if(file.isFile && fuelSession>0)
                        readGuided(id,fuelSession).snapshot().progress else 0
                    val date=java.text.SimpleDateFormat("dd.MM.yyyy",
                        java.util.Locale.getDefault()).format(java.util.Date(id))
                    "$date • $grade"+
                        (if(station.isNotBlank())" • $station" else "")+
                        " • $progress%"+
                        (if(rating.isNotBlank() && rating!="Без оценки")" • $rating" else "")
                }catch(_:Exception){"Заправка #$id • данные сохранены"}
            }
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                AlertDialog.Builder(this).setTitle("История заправок")
                    .setItems(summaries.toTypedArray()) { _,index ->
                        AlertDialog.Builder(this)
                            .setTitle("Заправка • данные сохранены")
                            .setMessage(summaries[index]+"\nID: ${ids[index]}\n"+
                                "Изменение метаданных не меняет диагностические CSV.")
                            .setPositiveButton("Изменить топливо") { _,_ ->
                                editFuelMetadata(ids[index])
                            }
                            .setNegativeButton("Закрыть",null).show()
                    }
                    .setNegativeButton("Закрыть",null).show()
            }
        }
    }

    private fun showBaselineLearning() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        fun dp(v:Int)=(resources.displayMetrics.density*v+0.5f).toInt()
        fun title(s:String,size:Float=15f)=TextView(this).apply {
            text=s; textSize=size; setTextColor(Color.WHITE)
            setPadding(0,dp(6),0,dp(6))
        }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(12),dp(8),dp(12),dp(10))
        }
        val scroll=ScrollView(this).apply { addView(content) }
        val actions=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        val newButton=Button(this).apply { text="Новый набор";isAllCaps=false;textSize=12f }
        val toggleButton=Button(this).apply { text="Пауза";isAllCaps=false;textSize=12f }
        val refreshButton=Button(this).apply { text="Обновить";isAllCaps=false;textSize=12f }
        actions.addView(newButton,LinearLayout.LayoutParams(0,dp(52),1.25f))
        actions.addView(toggleButton,LinearLayout.LayoutParams(0,dp(52),1f))
        actions.addView(refreshButton,LinearLayout.LayoutParams(0,dp(52),1f))
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            addView(actions)
            addView(scroll,LinearLayout.LayoutParams(-1,
                (resources.displayMetrics.heightPixels-dp(220)).coerceIn(dp(260),dp(530))))
        }
        val dialog=AlertDialog.Builder(this).setTitle("Обучение эталона • v1.7.32")
            .setView(root).setPositiveButton("Закрыть",null).create()

        fun grid(phase:BaselineLearning.Phase,stats:BaselineLearning.Coverage) {
            val rpm=if(phase==BaselineLearning.Phase.STEADY)
                BaselineLearning.steadyRpm else BaselineLearning.accelRpm
            val loads=if(phase==BaselineLearning.Phase.STEADY)
                BaselineLearning.steadyLoad else BaselineLearning.accelLoad
            content.addView(title(if(phase==BaselineLearning.Phase.STEADY)
                "STEADY · ровный режим" else "ACCEL · допустимые разгоны",17f))
            val heading=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            heading.addView(TextView(this).apply {
                text="RPM / LOAD";textSize=10f;setTextColor(Color.LTGRAY)
                gravity=android.view.Gravity.CENTER
            },LinearLayout.LayoutParams(dp(82),dp(29)))
            for(j in 0 until loads.size-1) {
                heading.addView(TextView(this).apply {
                    text="${loads[j]}–${loads[j+1]-1}%"
                    textSize=11f;setTextColor(Color.LTGRAY)
                    gravity=android.view.Gravity.CENTER
                },LinearLayout.LayoutParams(0,dp(29),1f))
            }
            content.addView(heading)
            for(i in 0 until rpm.size-1) {
                val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
                row.addView(TextView(this).apply {
                    text="${rpm[i]}–${rpm[i+1]-1}"
                    textSize=10f;setTextColor(Color.WHITE)
                    gravity=android.view.Gravity.CENTER
                },LinearLayout.LayoutParams(dp(82),dp(49)))
                for(j in 0 until loads.size-1) {
                    val cell=BaselineLearning.Cell(phase,i,j)
                    val stat=stats.stats(cell)
                    val color=when {
                        stat.readyForReview -> Color.rgb(28,112,99)
                        stat.count>=12 -> Color.rgb(49,101,154)
                        stat.count>0 -> Color.rgb(121,87,50)
                        else -> Color.rgb(36,48,63)
                    }
                    val bg=android.graphics.drawable.GradientDrawable().apply {
                        setColor(color);cornerRadius=dp(6).toFloat()
                    }
                    val tile=TextView(this).apply {
                        text=if(stat.readyForReview)"✓ ${stat.count}"
                            else if(stat.count>0)"${stat.count}\n${stat.progress}%"
                            else "—"
                        textSize=12f;setTextColor(Color.WHITE)
                        gravity=android.view.Gravity.CENTER
                        background=bg
                        setOnClickListener {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle(BaselineLearning.label(cell))
                                .setMessage("Записей: ${stat.count}\nУчастков: ${stat.segments}\n"+
                                    "Запусков LOGGER: ${stat.trips}\n"+
                                    "Диапазонов IAT (6 °C): ${stat.iatBuckets}\n"+
                                    "Первичный объём: ${stat.progress}%\n"+
                                    "Предварительная медиана: "+
                                    (stat.provisionalMedianVms?.let{
                                        String.format(java.util.Locale.US,"%.6f V·ms",it)
                                    }?:"недостаточно независимых запусков")+"\n"+
                                    "Разброс между запусками (MAD): "+
                                    (stat.betweenTripMadPct?.let{
                                        String.format(java.util.Locale.US,"%.1f%%",it)
                                    }?:"—")+"\n\n"+
                                    "24 точки, 4 участка, 2 запуска — лишь допуск к "+
                                    "независимой проверке, не подтверждение RON.")
                                .setPositiveButton("OK",null).show()
                        }
                    }
                    row.addView(tile,LinearLayout.LayoutParams(0,dp(44),1f).apply {
                        setMargins(dp(2),dp(2),dp(2),dp(2))
                    })
                }
                content.addView(row)
            }
            content.addView(title("Ячеек с объёмом для проверки: ${stats.reviewed(phase)} / ${stats.cells(phase).size}",12f))
            val advice=stats.advice(phase,liveLearningRpm,liveLearningLoad)
            content.addView(title("→ "+advice.text,12.5f))
        }

        fun refresh() {
            val id=prefs.getLong(BaselineLearning.PREF_DATASET,0L)
            val enabled=prefs.getBoolean(BaselineLearning.PREF_ENABLED,false)
            val label=prefs.getString(BaselineLearning.PREF_LABEL,"Текущее топливо")?:"Текущее топливо"
            val fuel=prefs.getInt(BaselineLearning.PREF_FUEL_ID,-1)
            val carFuel=prefs.getInt("fuel_session_id",1)
            toggleButton.text=if(enabled)"Пауза" else "Продолжить"
            executor.execute {
                val coverage=BaselineLearning.Coverage()
                if(id>0) try {
                    val file=File(getExternalFilesDir(null)?:filesDir,BaselineLearning.filename(id))
                    if(file.isFile)file.useLines { lines ->
                        lines.forEach { line ->
                            BaselineLearning.parse(line)?.let { sample ->
                                if(sample.fuelSessionId==fuel)coverage.accept(sample)
                            }
                        }
                    }
                }catch(_:Exception) { /* Show available counts on read failure. */ }
                runOnUiThread {
                    if(isFinishing || isDestroyed || !dialog.isShowing ||
                        prefs.getLong(BaselineLearning.PREF_DATASET,0L)!=id)return@runOnUiThread
                    content.removeAllViews()
                    content.addView(title("Набор: "+if(id==0L)"не создан" else label,16f))
                    content.addView(title(
                        "Топливо: НЕ подтверждено как эталонное • MG Flasher ST1\n"+
                        "Запись: "+when {
                            id==0L -> "сначала создай набор"
                            fuel!=carFuel -> "ПРИОСТАНОВЛЕНО: изменилась топливная сессия"
                            enabled -> "ВКЛЮЧЕНА"
                            else -> "ПАУЗА"
                        }+" • точек ${coverage.accepted}",12f))
                    if(prefs.getString("measurement_mode","AUTO")!="AUTO") {
                        content.addView(title(
                            "Для записи перейди в режим замеров AUTO и перезапусти LOGGER.",12f))
                    }
                    if(prefs.getFloat("mixing_remaining_km",0f)>0f) {
                        content.addView(title("Топливо смешивается: до завершения MIXING новые точки не принимаются.",12f))
                    }
                    content.addView(title(
                        "Сначала заполняем повторяемые ячейки. Цвет: серый — нет данных; "+
                        "янтарный — мало; синий — идёт набор; зелёный — можно проверять.",12f))
                    grid(BaselineLearning.Phase.STEADY,coverage)
                    grid(BaselineLearning.Phase.ACCEL,coverage)
                    content.addView(Button(this).apply {
                        text="Экспортировать черновую модель (CSV)"
                        isAllCaps=false
                        isEnabled=coverage.reviewed(BaselineLearning.Phase.STEADY)+
                            coverage.reviewed(BaselineLearning.Phase.ACCEL)>0
                        setOnClickListener {
                            val candidate=coverage.exportCandidate()
                            executor.execute {
                                val out=File(getExternalFilesDir(null)?:filesDir,
                                    "bmw_baseline_candidate_v1730_${id}.csv")
                                val ok=try { out.writeText(candidate);true }catch(_:Exception){false}
                                runOnUiThread {
                                    if(!isFinishing && !isDestroyed)
                                        Toast.makeText(this@MainActivity,if(ok)
                                            "Черновая модель сохранена: ${out.name}"
                                            else "Не удалось сохранить CSV",
                                            Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    })
                    content.addView(title(
                        "Это предварительная база реакции двигателя на текущую смесь, "+
                        "а не калибровка октанового числа. Смена топлива требует нового набора. "+
                        "Fuel Score 0.9 не меняется. Подсказки применяй только при безопасных и разрешённых условиях движения.",12f))
                }
            }
        }

        newButton.setOnClickListener {
            if(EnetLoggerService.isLoggerActive) {
                Toast.makeText(this,"Останови LOGGER перед созданием нового набора",Toast.LENGTH_LONG).show()
            } else {
                val label=EditText(this).apply {
                    setSingleLine(true)
                    setText("MG Flasher ST1 • текущее топливо (RON не проверен)")
                    setSelection(text.length)
                }
                AlertDialog.Builder(this)
                    .setTitle("Новая серия обучения")
                    .setMessage("Старые CSV сохраняются. Новая серия не меняет Fuel Score. "+
                        "Подтверди топливо/прошивку и выполняй измерения в AUTO.")
                    .setView(label)
                    .setNegativeButton("Отмена",null)
                    .setPositiveButton("Создать") { _,_ ->
                        val name=label.text.toString().trim().ifBlank{"Текущее топливо (RON не проверен)"}
                        prefs.edit()
                            .putLong(BaselineLearning.PREF_DATASET,System.currentTimeMillis())
                            .putInt(BaselineLearning.PREF_FUEL_ID,prefs.getInt("fuel_session_id",1))
                            .putString(BaselineLearning.PREF_LABEL,name.take(100))
                            .putBoolean(BaselineLearning.PREF_ENABLED,true).apply()
                        refresh()
                    }.show()
            }
        }
        toggleButton.setOnClickListener {
            val id=prefs.getLong(BaselineLearning.PREF_DATASET,0L)
            if(id==0L)newButton.performClick()
            else if(prefs.getInt(BaselineLearning.PREF_FUEL_ID,-1)!=prefs.getInt("fuel_session_id",1)) {
                Toast.makeText(this,"Топливная сессия изменилась: создай отдельный набор при остановленном LOGGER",Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putBoolean(BaselineLearning.PREF_ENABLED,
                    !prefs.getBoolean(BaselineLearning.PREF_ENABLED,false)).apply()
                refresh()
            }
        }
        refreshButton.setOnClickListener { refresh() }
        dialog.show()
        val periodic=object:Runnable {
            override fun run() {
                if(!dialog.isShowing)return
                refresh()
                main.postDelayed(this,4500L)
            }
        }
        dialog.setOnDismissListener { main.removeCallbacks(periodic) }
        refresh()
        main.postDelayed(periodic,4500L)
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

    private fun showFastDidCandidateAudit() {
        val text=buildString {
            append("Диагностические профили:\n")
            for(profile in FastDidValidation.Profile.entries) {
                append(profile.name).append(": ")
                append(if(FastDidValidation.canActivate(profile,false,false))
                    "BASELINE (существующие OBD/B+)" else
                    "BLOCKED — документальный допуск отсутствует")
                append('\n')
            }
            append("\nКандидаты из bootmod3 mock_channels.json:\n")
            for(spec in FastDidValidation.candidates) {
                append(spec.name).append(" • ").append(spec.label).append(" • ")
                append(FastDidValidation.status(spec.did)).append('\n')
            }
            append("\n0x4A37–0x4A3A: SEMANTICS_UNVERIFIED. "+
                "Текущий 4-байтный декодер не подтверждает физические единицы; "+
                "демонстрационный 2-байтный коэффициент НЕ применяется.")
        }
        AlertDialog.Builder(this).setTitle("FAST DID Validation • AUDIT")
            .setMessage(text).setPositiveButton("OK",null).show()
    }

    private fun showFastDidAuditCsv() {
        val dir=getExternalFilesDir(null)?:filesDir
        val file=dir.listFiles()?.filter{
            it.isFile && it.name.startsWith("bmw_fast_did_validation_v1_") &&
                it.name.endsWith(".csv")
        }?.maxByOrNull{it.lastModified()}
        if(file==null) {
            AlertDialog.Builder(this).setTitle("FAST DID • RAW журнал")
                .setMessage("Нет записей. Исследовательский CSV появится "+
                    "после запуска обычного LOGGER. Новые DID не опрашиваются.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val report=try {
                file.useLines { lines->
                    val audit=FastDidAuditCsv.analyze(lines)
                    "${file.name}\nСтрок: ${audit.total}; "+
                    "валидных: ${audit.valid}; повреждённых: ${audit.invalid}\n"+
                    "Топливные сессии: ${audit.fuelSessions}\n"+
                    "DID: ${audit.dids}\n"+
                    "Статусы: ${audit.statuses}\n"+
                    "RAW снят только с существующих каналов."+
                    "\n\nПоследние RAW/статус по каждому DID:\n"+
                    file.useLines { rawLines->
                        val latest=FastDidAuditCsv.latestSamples(rawLines)
                        FastDidValidation.candidates.joinToString("\n") { c ->
                            val id="%04X".format(java.util.Locale.US,c.did)
                            val row=latest[id]
                            if(row==null) "$id — нет записей"
                            else "$id • ${row.status} • ${row.source}"+
                                "\n  RAW: ${row.rawHex.ifEmpty{"отсутствует"}}"+
                                " | delay: ${row.durationMs.ifEmpty{"—"}} мс"+
                                " | OBD age: ${row.ageMs.ifEmpty{"—"}} мс"+
                                if(row.legacyValue.isNotEmpty())
                                    "\n  legacy: ${row.legacyValue} "+
                                        "(SEMANTICS_UNVERIFIED)" else ""
                        }
                    }
                }
            }catch(e:Exception){"Не удалось проверить CSV: ${e.message}"}
            runOnUiThread {
                if(!isFinishing && !isDestroyed)
                    AlertDialog.Builder(this).setTitle("FAST DID • OFFLINE")
                        .setMessage(report).setPositiveButton("OK",null).show()
            }
        }
    }

    private fun showHighSpeedProfileDialog() {
        val prefs=getSharedPreferences("bmw_native",MODE_PRIVATE)
        val profiles=HighSpeedProfilePolicy.Profile.entries
        val current=HighSpeedProfilePolicy.resolve(
            prefs.getString(HighSpeedProfilePolicy.PREF_PROFILE,null))
        val labels=arrayOf(
            "LEGACY — прежний планировщик, контрольная диагностика",
            "HIGH_SPEED_CORE — FAST приоритет, ограничение SLOW до 1 запроса",
            "HIGH_SPEED_EXTENDED — FAST + полная прежняя SLOW-полоса")
        AlertDialog.Builder(this).setTitle("High-Speed Engine • эксперимент")
            .setSingleChoiceItems(labels,profiles.indexOf(current)) { dialog,index ->
                val chosen=profiles[index]
                prefs.edit().putString(HighSpeedProfilePolicy.PREF_PROFILE,chosen.name)
                    .apply()
                // The polling coroutine applies changes between transactions
                // and breaks the current partial STEADY/ACCEL segment.
                dialog.dismiss()
                Toast.makeText(this,
                    "Выбран ${chosen.name}. Частота 20–50 Гц не подтверждена.",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена",null).show()
    }

    private fun showHighSpeedBenchmark() {
        val directory=getExternalFilesDir(null)?:filesDir
        val file=directory.listFiles()?.filter { it.isFile &&
            it.name.startsWith("bmw_highspeed_benchmark_v1_") &&
            it.name.endsWith(".csv") }?.maxByOrNull{it.lastModified()}
        if(file==null) {
            AlertDialog.Builder(this).setTitle("High-Speed Benchmark")
                .setMessage("Нет данных. Включи LOGGER для сбора измерений.")
                .setPositiveButton("OK",null).show()
            return
        }
        executor.execute {
            val report=try {
                file.useLines { lines ->
                    val offline=HighSpeedBenchmark.analyze(lines)
                    "${file.name}\n\n"+
                        "Строк: ${offline.rows}, валидных: ${offline.validRows}"+
                        ", повреждённых: ${offline.incompleteRows}\n"+
                        "Топливные сессии: ${offline.sessions}\n"+
                        "Профили: ${offline.modes}\n"+
                        "Каналы: ${offline.channels.joinToString()}\n\n"+
                        "Истинный ECU sampling rate без ECU timestamp неизвестен."
                }
            }catch(e:Exception){"Не удалось проверить CSV: ${e.message}"}
            runOnUiThread {
                if(isFinishing || isDestroyed)return@runOnUiThread
                AlertDialog.Builder(this).setTitle("High-Speed Benchmark • offline")
                    .setMessage(report).setPositiveButton("OK",null).show()
            }
        }
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
                            // v1.7 already changed the session before confirmation.
                            // Never repeat that destructive legacy action from v2 UI.
                            builder.setPositiveButton("Закрыть",null)
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

    // Legacy bmw_fuel_events.csv is a read-only archive. All new
    // confirmations are durable RefuelSessionStore transitions.
    override fun onDestroy() {
        main.removeCallbacks(fastStaleClear)
        guidedUiRefresh?.let { main.removeCallbacks(it) }
        guidedUiRefresh=null
        historyCancel=true
        if(!EnetLoggerService.isLoggerActive) VxscanLocalOnlyWifi.stop(this)
        statusReceiver?.let{try{unregisterReceiver(it)}catch(_:Exception){}}
        statusReceiver=null
        super.onDestroy()
    }


    override fun onActivityResult(requestCode:Int, resultCode:Int, data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==902) {
            val source=pendingOctane2Export
            pendingOctane2Export=null
            val dest=data?.data
            if(resultCode==RESULT_OK && source!=null && dest!=null &&
                source.isFile) {
                executor.execute {
                    val result=try {
                        contentResolver.openOutputStream(dest)?.use{out ->
                            source.inputStream().use{input->input.copyTo(out)}
                        } ?: throw IllegalStateException("Export stream unavailable")
                        "CSV экспортирован: ${source.name}"
                    }catch(e:Exception){"Ошибка экспорта: ${e.message}"}
                    runOnUiThread {
                        if(!isFinishing && !isDestroyed)
                            Toast.makeText(this,result,Toast.LENGTH_LONG).show()
                    }
                }
            }
            return
        }
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
