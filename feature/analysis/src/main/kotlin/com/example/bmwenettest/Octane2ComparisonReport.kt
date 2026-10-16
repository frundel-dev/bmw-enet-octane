package com.example.bmwenettest

import kotlin.math.abs

/**
 * Single-file, same-cycle 1.7 OBD vs 2.0 RAM diagnostic comparison.
 * This is not a measurement of octane rating or statistical repeatability
 * across different fills/trips. Never infer missing samples from a zero.
 */
object Octane2ComparisonReport {
    data class Summary(
        val rows:Int,val malformed:Int,val obdValid:Int,val ramValid:Int,
        val lambdaValid:Int,val knockValid:Int,val ignitionValid:Int,
        val legacyScored:Int,val targetA:Int,val targetB:Int,
        val reconnectEpochs:Int,val meanCycleMs:Double?,
        val meanObdLatencyMs:Double?,val meanRamLatencyMs:Double?,
        val meanAbsRpmDifference:Double?,val achievedHz:Double?,
        val gapsOver1500Ms:Int
    ) {
        fun russianReport():String =
            "Сопоставимые циклы: $rows; повреждённых строк: $malformed\n"+
            "RPM 1.7 (OBD): $obdValid; 2.0 (RAM): $ramValid\n"+
            "Детонация: $knockValid; УОЗ: $ignitionValid; actual λ: $lambdaValid\n"+
            "Целевые RPM/MAP (цикл, не зачтённый участок): $targetA / $targetB\n"+
            "Переподключений (смен epoch): $reconnectEpochs; пауз >1,5 с: $gapsOver1500Ms\n"+
            "Наблюдаемая частота: ${fmt(achievedHz)} Гц\n"+
            "Средний цикл: ${fmt(meanCycleMs)} мс; OBD RPM: ${fmt(meanObdLatencyMs)} мс; RAM RPM: ${fmt(meanRamLatencyMs)} мс\n"+
            "Средняя |RAM−OBD|: ${fmt(meanAbsRpmDifference)} об/мин\n"+
            "Legacy 1.7 score был записан в $legacyScored циклах.\n"+
            "BMW OCTANE 2.0: НЕДОСТАТОЧНО ДАННЫХ для валидированного Fuel Score.\n"+
            "Сигналы опрошены последовательно; частоты и разница RPM не являются одновременным снимком DME."
    }

    private fun fmt(x:Double?):String =
        x?.takeIf{it.isFinite()}?.let{
            String.format(java.util.Locale.US,"%.2f",it)
        }?:"—"

    fun analyze(lines:Sequence<String>):Summary {
        var rows=0;var malformed=0;var obd=0;var ram=0;var lambda=0
        var knock=0;var ignition=0;var legacy=0;var a=0;var b=0
        var prevEpoch:Int?=null;var reconnects=0
        var firstTime:Long?=null;var lastTime:Long?=null;var gaps=0
        var cycleSum=0.0;var cycleN=0;var obdSum=0.0;var obdN=0
        var ramSum=0.0;var ramN=0;var diffSum=0.0;var diffN=0
        for(line in lines) {
            if(line.isBlank() || line.startsWith("#") ||
                line==Octane2CycleCsv.HEADER)continue
            val f=line.split(',')
            if(f.size!=36){malformed++;continue}
            val time=f[1].toLongOrNull()
            val epoch=f[4].toIntOrNull()
            val cycle=f[14].toLongOrNull()
            val region=f[32].toIntOrNull()
            if(time==null || time<0L || epoch==null || epoch<0 ||
                cycle==null || cycle<0 || region==null) {
                malformed++;continue
            }
            fun num(i:Int):Double?=f[i].toDoubleOrNull()?.takeIf{it.isFinite()}
            rows++
            if(prevEpoch!=null && prevEpoch!=epoch)reconnects++
            prevEpoch=epoch
            if(firstTime==null)firstTime=time
            lastTime=time
            if((f[15].toLongOrNull()?:0L)>1500L)gaps++
            cycleSum+=cycle;cycleN++
            num(11)?.let{obdSum+=it;obdN++}
            num(12)?.let{ramSum+=it;ramN++}
            val o=num(6);val r=num(7)
            if(o!=null)obd++
            if(r!=null)ram++
            if(o!=null && r!=null){diffSum+=abs(o-r);diffN++}
            if(num(23)!=null)lambda++
            if(num(21)!=null)knock++
            if(num(22)!=null)ignition++
            if(num(34)!=null)legacy++
            when(region){0->a++;1->b++}
        }
        fun mean(sum:Double,n:Int)=if(n>0)sum/n else null
        val elapsed=if(firstTime!=null && lastTime!=null)
            lastTime-firstTime else 0L
        val hz=if(rows>=2 && elapsed>0) (rows-1)*1000.0/elapsed else null
        return Summary(rows,malformed,obd,ram,lambda,knock,ignition,legacy,
            a,b,reconnects,mean(cycleSum,cycleN),mean(obdSum,obdN),
            mean(ramSum,ramN),mean(diffSum,diffN),hz,gaps)
    }
}
