package com.example.bmwenettest

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Compiled into LAB only. All diagnostic socket access stays in Service. */
internal object DmeBenchmarkUiFactory {
    fun create(activity:Activity, dataDir:File, canStart:()->String?,
               start:()->Unit, export:(File)->Unit):DmeBenchmarkUi =
        LabDmeBenchmarkUi(activity,dataDir,canStart,start,export)
}

private class LabDmeBenchmarkUi(
    private val activity:Activity, private val dataDir:File,
    private val canStart:()->String?, private val start:()->Unit,
    private val export:(File)->Unit
):DmeBenchmarkUi {
    private val dp=activity.resources.displayMetrics.density
    private val status=TextView(activity).apply {
        text="Тест не запущен";textSize=12f;setTextColor(Color.rgb(101,190,255))
        setPadding(0,(8*dp).toInt(),0,(8*dp).toInt())
    }
    private fun button(label:String,onClick:()->Unit)=Button(activity).apply {
        text=label;isAllCaps=false
        setOnClickListener{onClick()}
    }
    private val card=LinearLayout(activity).apply {
        orientation=LinearLayout.VERTICAL
        setPadding((12*dp).toInt(),(12*dp).toInt(),(12*dp).toInt(),(12*dp).toInt())
        background=GradientDrawable().apply {
            setColor(Color.rgb(25,37,53));cornerRadius=12*dp
            setStroke((1*dp).toInt().coerceAtLeast(1),Color.rgb(45,62,83))
        }
        addView(TextView(activity).apply {
            text="КОМПЛЕКСНЫЙ ТЕСТ DME";textSize=15f
            setTextColor(Color.WHITE)
        })
        addView(TextView(activity).apply {
            text="6 этапов • 15 минут • только стоящий автомобиль; "+
                "двигатель прогрет, свежие RPM/SPEED/ECT обязательны. "+
                "Не делай ускорений. В фон уходят только на 6-м этапе."
            textSize=12f;setTextColor(Color.rgb(159,176,197))
        })
        addView(status)
        addView(button("КОМПЛЕКСНЫЙ ТЕСТ DME") {confirmStart()})
        addView(button("ПОСЛЕДНИЙ ОТЧЁТ DME"){showReport()})
    }
    override fun panel():View=card
    override fun status(intent:Intent) {
        status.text=intent.getStringExtra(EnetLoggerService.EXTRA_BENCHMARK_STATUS)
            ?: "Отсутствуют данные о состоянии испытания"
    }
    override fun visibility(visible:Boolean) {
        if(EnetLoggerService.isBenchmarkRunning) {
            activity.startService(Intent(activity,EnetLoggerService::class.java)
                .setAction(EnetLoggerService.ACTION_BENCHMARK_UI_VISIBILITY)
                .putExtra(EnetLoggerService.EXTRA_BENCHMARK_VISIBLE,visible))
        }
    }
    private fun confirmStart() {
        canStart()?.let{reason->
            AlertDialog.Builder(activity).setTitle("Комплексный тест DME")
                .setMessage(reason).setPositiveButton("OK",null).show()
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("КОМПЛЕКСНЫЙ ТЕСТ DME • 15 минут")
            .setMessage("Только на открытом воздухе или в безопасно "+
                "вентилируемом месте. Автомобиль стоит, селектор в P, "+
                "стояночный тормоз включён, двигатель прогрет. "+
                "OBD не подтверждает P или стояночный тормоз.\n\n"+
                "B+/LEGACY → B+/CORE → B+/EXTENDED → B/CORE → "+
                "B+/CORE повтор → CORE в фоне 5 мин. "+
                "На последнем этапе можно свернуть приложение или погасить экран. "+
                "При движении, неполных ответах, потере VXSCAN и ошибках тест "+
                "прервётся с сохранением частичных результатов. "+
                "Кнопка ОСТАНОВИТЬ на главной отменяет испытание.")
            .setPositiveButton("НАЧАТЬ ТЕСТ"){_,_->if(canStart()==null)start()}
            .setNegativeButton("ОТМЕНА",null).show()
    }
    private fun showReport() {
        val report=dataDir.listFiles()?.filter{
            it.isFile && it.name.startsWith("bmw_dme_benchmark_suite_v1_") &&
                it.name.endsWith(".txt")
        }?.maxByOrNull{it.lastModified()}
        if(report==null) {
            AlertDialog.Builder(activity).setTitle("Комплексный тест DME")
                .setMessage("Отчёт не найден; запусти тест.")
                .setPositiveButton("OK",null).show()
            return
        }
        val body=try {report.readText().take(20000)}
            catch(e:Exception){"Ошибка чтения отчёта: "+e.javaClass.simpleName}
        if(activity.isFinishing || activity.isDestroyed)return
        val csv=File(report.parentFile,report.name.removeSuffix(".txt")+".csv")
        AlertDialog.Builder(activity).setTitle("Отчёт DME • "+report.name)
            .setMessage(body)
            .setPositiveButton("ЭКСПОРТ CSV"){_,_->
                if(csv.isFile)export(csv)
                else Toast.makeText(activity,"CSV не найден",Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("ПОДЕЛИТЬСЯ ТЕКСТОМ"){_,_->
                activity.startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type="text/plain";putExtra(Intent.EXTRA_TEXT,body)
                    },"Отчёт DME"))
            }
            .setNegativeButton("ЗАКРЫТЬ",null).show()
    }
}
