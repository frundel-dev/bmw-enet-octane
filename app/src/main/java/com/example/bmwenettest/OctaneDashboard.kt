package com.example.bmwenettest

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.Locale

/**
 * Compact, readable dashboard for live telemetry. History and raw diagnostic
 * data stay available from the separate "Дополнительно" menu.
 */
class OctaneDashboard(context: Context) : LinearLayout(context) {
    private val bg=Color.rgb(12,19,32)
    private val panel=Color.rgb(23,34,52)
    private val panelBorder=Color.rgb(45,62,83)
    private val foreground=Color.rgb(242,248,255)
    private val muted=Color.rgb(158,175,196)
    private val blue=Color.rgb(89,171,255)
    private val teal=Color.rgb(75,219,183)
    private val amber=Color.rgb(255,194,102)
    private val warning=Color.rgb(255,134,117)
    private val progress=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal)

    private lateinit var score:TextView
    private lateinit var scoreState:TextView
    private lateinit var confidence:TextView
    private lateinit var speed:TextView
    private lateinit var rpm:TextView
    private lateinit var load:TextView
    private lateinit var map:TextView
    private lateinit var steadyScore:TextView
    private lateinit var steadyDetail:TextView
    private lateinit var accelScore:TextView
    private lateinit var accelDetail:TextView
    private lateinit var phaseLabel:TextView
    private lateinit var steadySurvey:TextView
    private lateinit var accelCalibration:TextView
    private lateinit var coolant:TextView
    private lateinit var oil:TextView
    private lateinit var iat:TextView
    private lateinit var fuel:TextView
    private lateinit var network:TextView
    private lateinit var knock:TextView
    private lateinit var highRpm:TextView
    val connectionView:TextView

    private fun dp(value:Int)=(value*resources.displayMetrics.density+0.5f).toInt()

    private fun text(value:String,size:Float=14f,color:Int=foreground,bold:Boolean=false):TextView =
        TextView(context).apply {
            this.text=value
            textSize=size
            setTextColor(color)
            if(bold) typeface=Typeface.create("sans-serif-medium",Typeface.BOLD)
            includeFontPadding=false
        }

    private fun round(fill:Int,stroke:Int=panelBorder,radius:Int=16):GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius=dp(radius).toFloat()
            setStroke(dp(1),stroke)
        }

    private fun card(title:String):LinearLayout {
        val box=LinearLayout(context).apply {
            orientation=VERTICAL
            background=round(panel)
            setPadding(dp(14),dp(14),dp(14),dp(13))
        }
        if(title.isNotEmpty()) {
            box.addView(text(title,12f,muted,true))
            box.addView(View(context),LayoutParams(1,dp(10)))
        }
        addView(box,LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT).apply {
            bottomMargin=dp(10)
        })
        return box
    }

    private fun row():LinearLayout=LinearLayout(context).apply {
        orientation=HORIZONTAL
        gravity=Gravity.CENTER_VERTICAL
    }

    private fun tile(label:String):Pair<LinearLayout,TextView> {
        val column=LinearLayout(context).apply { orientation=VERTICAL }
        column.addView(text(label,11f,muted,true))
        val value=text("—",27f,foreground,true)
        value.setPadding(0,dp(5),0,0)
        column.addView(value)
        return column to value
    }

    private fun addTile(parent:LinearLayout,label:String,onValue:(TextView)->Unit) {
        val pair=tile(label)
        parent.addView(pair.first,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        onValue(pair.second)
    }

    private fun format(value:Double,decimals:Int=0):String {
        if(!value.isFinite()) return "—"
        return String.format(Locale.getDefault(),"%."+decimals+"f",value)
    }

    init {
        orientation=VERTICAL
        setPadding(0,dp(10),0,dp(14))
        setBackgroundColor(bg)

        val hero=card("")
        val titleRow=row()
        titleRow.addView(text("КАЧЕСТВО ТОПЛИВА",12f,muted,true),
            LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        scoreState=text("ОЖИДАНИЕ",11f,amber,true).apply {
            gravity=Gravity.END
        }
        titleRow.addView(scoreState)
        hero.addView(titleRow)
        score=text("—",54f,foreground,true).apply { setPadding(0,dp(5),0,0) }
        hero.addView(score)
        hero.addView(text("Относительный индекс • эталон АИ-95 = 100",12f,muted))
        val confidenceRow=row().apply { setPadding(0,dp(17),0,dp(7)) }
        confidenceRow.addView(text("ДОСТОВЕРНОСТЬ",11f,muted,true),
            LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        confidence=text("0%",15f,teal,true)
        confidenceRow.addView(confidence)
        hero.addView(confidenceRow)
        progress.max=100
        progress.progress=0
        progress.progressTintList=ColorStateList.valueOf(teal)
        progress.progressBackgroundTintList=ColorStateList.valueOf(panelBorder)
        hero.addView(progress,LayoutParams(LayoutParams.MATCH_PARENT,dp(7)))
        hero.addView(View(context),LayoutParams(1,dp(15)))
        val fuelDivider=View(context).apply { setBackgroundColor(panelBorder) }
        hero.addView(fuelDivider,LayoutParams(LayoutParams.MATCH_PARENT,dp(1)))
        hero.addView(View(context),LayoutParams(1,dp(12)))
        hero.addView(text("УРОВЕНЬ ТОПЛИВА И СЕССИЯ",11f,muted,true))
        fuel=text("Бак —   •   сессия №1",14f,foreground,true).apply {
            setPadding(0,dp(5),0,0)
        }
        hero.addView(fuel)

        val driving=card("ДВИЖЕНИЕ И ДВИГАТЕЛЬ")
        val top=row()
        addTile(top,"СКОРОСТЬ") { speed=it }
        addTile(top,"ОБОРОТЫ") { rpm=it }
        driving.addView(top)
        driving.addView(View(context),LayoutParams(1,dp(17)))
        val bottom=row()
        addTile(bottom,"НАГРУЗКА") { load=it }
        addTile(bottom,"MAP • АБС.") { map=it }
        driving.addView(bottom)
        driving.addView(View(context),LayoutParams(1,dp(16)))
        val tempCaption=text("ТЕМПЕРАТУРЫ",11f,muted,true)
        driving.addView(tempCaption)
        driving.addView(View(context),LayoutParams(1,dp(9)))
        val temp=row()
        addTile(temp,"ОЖ") { coolant=it }
        addTile(temp,"МАСЛО") { oil=it }
        addTile(temp,"ВПУСК") { iat=it }
        driving.addView(temp)

        val measurement=card("РЕЖИМЫ ИЗМЕРЕНИЙ")
        phaseLabel=text("AUTO • ожидание подходящего участка",12f,blue)
        measurement.addView(phaseLabel)
        measurement.addView(View(context),LayoutParams(1,dp(13)))
        val modes=row()
        val steadyColumn=LinearLayout(context).apply { orientation=VERTICAL }
        steadyColumn.addView(text("STEADY",11f,muted,true))
        steadyScore=text("—",22f,foreground,true).apply { setPadding(0,dp(5),0,0) }
        steadyColumn.addView(steadyScore)
        steadyDetail=text("0% · 0 точек · 0 участков",11f,muted)
        steadyColumn.addView(steadyDetail)
        modes.addView(steadyColumn,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        val accelColumn=LinearLayout(context).apply { orientation=VERTICAL }
        accelColumn.addView(text("ACCELERATION",11f,muted,true))
        accelScore=text("—",22f,foreground,true).apply { setPadding(0,dp(5),0,0) }
        accelColumn.addView(accelScore)
        accelDetail=text("0% · 0 точек · 0 участков",11f,muted)
        accelColumn.addView(accelDetail)
        modes.addView(accelColumn,LayoutParams(0,LayoutParams.WRAP_CONTENT,1f))
        measurement.addView(modes)
        measurement.addView(View(context),LayoutParams(1,dp(12)))
        steadySurvey=text("STEADY v0.8 • собираем новую эталонную базу",12f,amber)
        measurement.addView(steadySurvey)
        accelCalibration=text("ACCEL v0.8 • экспериментальный эталон: —",12f,blue)
        accelCalibration.setPadding(0,dp(7),0,0)
        measurement.addView(accelCalibration)
        measurement.addView(text("Общий Fuel Score по-прежнему по исходному эталону АИ-95.",11f,muted).apply {
            setPadding(0,dp(7),0,0)
        })

        val diagnostic=card("СВЯЗЬ И ДИАГНОСТИКА")
        network=text("Ожидание VXSCAN",13f,blue,true)
        diagnostic.addView(network)
        diagnostic.addView(View(context),LayoutParams(1,dp(9)))
        knock=text("Детонация: —  •  Superknock: —",12f,muted)
        diagnostic.addView(knock)
        highRpm=text("3500+ об/мин: данных пока нет",12f,muted)
        highRpm.setPadding(0,dp(6),0,0)
        diagnostic.addView(highRpm)
        connectionView=text("Подключите USB ENET или Wi-Fi VXSCAN и нажмите START LOGGER.",
            12f,muted)
        connectionView.setPadding(0,dp(11),0,0)
        connectionView.setTextIsSelectable(true)
        diagnostic.addView(connectionView)
    }

    fun setMessage(message:String) {
        connectionView.text=message.take(450)
        if(message.startsWith("ENET reconnect",true)) {
            network.text="Соединение прервано • переподключение"
            network.setTextColor(warning)
        } else if(message.startsWith("CONNECTED",true)) {
            network.text="VXSCAN подключён"
            network.setTextColor(teal)
        }
    }

    fun setLogging(active:Boolean) {
        if(!active) {
            network.text="Логгер остановлен"
            network.setTextColor(muted)
            phaseLabel.text="Измерение остановлено"
        } else {
            network.text="Поиск диагностического шлюза…"
            network.setTextColor(blue)
        }
    }

    fun render(intent:Intent) {
        fun number(key:String)=intent.getDoubleExtra(key,Double.NaN)
        fun count(key:String)=intent.getIntExtra(key,0)
        val sessionScore=number(EnetLoggerService.EXTRA_SESSION_SCORE)
        val runScore=number(EnetLoggerService.EXTRA_FUEL_SCORE)
        val result=intent.getStringExtra(EnetLoggerService.EXTRA_RESULT_STATE) ?: "COLLECTING"
        val shownScore=if(result=="MIXING" || result=="REFUEL CHECK") Double.NaN
                       else if(sessionScore.isFinite()) sessionScore else runScore
        score.text=format(shownScore,1)
        scoreState.text=when(result) {
            "NORMAL"->"В НОРМЕ"
            "BORDERLINE"->"ПОГРАНИЧНО"
            "BELOW BASELINE"->"НИЖЕ ЭТАЛОНА"
            "MIXING"->"ПЕРЕМЕШИВАНИЕ"
            "REFUEL CHECK"->"ПРОВЕРКА ЗАПРАВКИ"
            else->"СБОР ДАННЫХ"
        }
        scoreState.setTextColor(when(result) {
            "NORMAL"->teal
            "BELOW BASELINE"->warning
            else->amber
        })
        val conf=count(EnetLoggerService.EXTRA_SESSION_CONFIDENCE).coerceIn(0,100)
        confidence.text="$conf%"
        progress.progress=conf
        speed.text=format(number(EnetLoggerService.EXTRA_SPEED)) + " км/ч"
        rpm.text=format(number(EnetLoggerService.EXTRA_RPM))
        load.text=format(number(EnetLoggerService.EXTRA_LOAD)) + "%"
        map.text=format(number(EnetLoggerService.EXTRA_MAP)) + " кПа"
        coolant.text=format(number(EnetLoggerService.EXTRA_COOLANT)) + "°"
        oil.text=format(number(EnetLoggerService.EXTRA_OIL)) + "°"
        iat.text=format(number(EnetLoggerService.EXTRA_IAT)) + "°"

        val phase=intent.getStringExtra(EnetLoggerService.EXTRA_PHASE) ?: "—"
        val mode=intent.getStringExtra(EnetLoggerService.EXTRA_MODE) ?: "AUTO"
        val phaseSegments=count(EnetLoggerService.EXTRA_SEGMENTS)
        phaseLabel.text="$mode • $phase • $phaseSegments зачтённых участков"
        steadyScore.text=format(number(EnetLoggerService.EXTRA_STEADY_SCORE),1)
        accelScore.text=format(number(EnetLoggerService.EXTRA_ACCEL_SCORE),1)
        steadyDetail.text=count(EnetLoggerService.EXTRA_STEADY_CONF).toString()+"% · "+
            count(EnetLoggerService.EXTRA_STEADY_POINTS)+" тчк · "+
            count(EnetLoggerService.EXTRA_STEADY_SEGMENTS)+" уч."
        accelDetail.text=count(EnetLoggerService.EXTRA_ACCEL_CONF).toString()+"% · "+
            count(EnetLoggerService.EXTRA_ACCEL_POINTS)+" тчк · "+
            count(EnetLoggerService.EXTRA_ACCEL_SEGMENTS)+" уч."

        val surveyed=count(EnetLoggerService.EXTRA_STEADY_SURVEY)
        val covered=count(EnetLoggerService.EXTRA_STEADY_CELLS)
        steadySurvey.text="STEADY v0.8 • наблюдений $surveyed • ячеек $covered"+
            " • пробный балл, не лабораторный RON"
        val newAccel=number(EnetLoggerService.EXTRA_ACCEL_V08_SCORE)
        val newAccelPoints=count(EnetLoggerService.EXTRA_ACCEL_V08_POINTS)
        accelCalibration.text="ACCEL v0.8 (проверочная): "+format(newAccel,1)+
            " • точек $newAccelPoints"

        val tankPct=number(EnetLoggerService.EXTRA_FUEL_PCT)
        val fuelId=intent.getIntExtra(EnetLoggerService.EXTRA_FUEL_ID,1)
        val mixing=number(EnetLoggerService.EXTRA_MIXING_KM)
        fuel.text="Бак "+(if(tankPct.isFinite()) format(tankPct,1)+"%" else "—")+
            "   •   сессия №$fuelId"+
            (if(mixing.isFinite() && mixing>0.0) "   •   смешивание "+format(mixing,1)+" км" else "")

        val transport=intent.getStringExtra(EnetLoggerService.EXTRA_TRANSPORT) ?: "—"
        val hz=number(EnetLoggerService.EXTRA_HZ)
        val reconnects=count(EnetLoggerService.EXTRA_RECONNECTS)
        network.text="$transport   •   "+format(hz,2)+" Гц   •   реконнектов $reconnects"
        network.setTextColor(if(reconnects>0)amber else teal)
        knock.text="Knock: "+count(EnetLoggerService.EXTRA_KNOCK_EVENTS)+
            "   •   Superknock: "+count(EnetLoggerService.EXTRA_SUPER_EVENTS)+
            "   •   Run Q: "+count(EnetLoggerService.EXTRA_RUN_QUALITY)+"%"
        val highRatio=number(EnetLoggerService.EXTRA_HIGH_RPM_RATIO)
        val highPoints=count(EnetLoggerService.EXTRA_HIGH_RPM_POINTS)
        highRpm.text="3500+ RPM • MAP 180+: "+highPoints+" точек"+
            "   •   сигнал/эталон "+format(highRatio,2)+
            "   •   >1,20: "+count(EnetLoggerService.EXTRA_HIGH_RPM_OVER120)
        val summary=intent.getStringExtra(EnetLoggerService.EXTRA_SUMMARY)
        connectionView.text=if(!summary.isNullOrBlank()) summary.take(350)
            else "Замер #"+count(EnetLoggerService.EXTRA_RUN_ID)+
                "   •   эталон АИ-95   •   показатель не равен RON"
    }
}
