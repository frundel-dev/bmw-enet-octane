package com.example.bmwenettest

/**
 * The fuel-oriented UI uses ONLY persisted, validated STEADY counts (not
 * elapsed LOGGER time) for coverage. Pure, deterministic and JVM-testable.
 */
internal object Octane2UiState {
    enum class Stage { NOT_STARTED, COLLECTING, SUFFICIENT }
    enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
    data class Target(val points:Int,val segments:Int,val loggerStarts:Int,
                      val progress:Int,val stage:Stage) {
        val sufficient:Boolean get()=stage==Stage.SUFFICIENT
    }
    data class Guidance(val heading:String,val instructions:String,
                        val nextTarget:Int,val allCovered:Boolean)

    fun target(counts:IntArray?):Target {
        if(counts==null || counts.size<5)return Target(0,0,0,0,Stage.NOT_STARTED)
        val points=counts[0].coerceAtLeast(0)
        val segments=counts[1].coerceAtLeast(0)
        val starts=counts[2].coerceAtLeast(0)
        // Do not trust a UI percentage if the source's support gate has
        // not been met. Cross-check against the canonical SteadyTargets.Cell.
        val cell=SteadyTargets.Cell(points,segments,starts,counts[3].coerceAtLeast(0))
        val stage=when {
            cell.candidateSupport -> Stage.SUFFICIENT
            points>0 || segments>0 || starts>0 -> Stage.COLLECTING
            else -> Stage.NOT_STARTED
        }
        return Target(points,segments,starts,cell.coveragePct,stage)
    }

    fun guidance(first:Target,second:Target,connected:Boolean,
                 capturePaused:Boolean):Guidance {
        val all=first.sufficient && second.sufficient
        if(all)return Guidance("Данные для проверки собраны",
            "Открой «Анализ». Режимы покрыты предварительно, оценка топлива ещё не валидирована.",-1,true)
        val idx=when {
            first.sufficient -> 1
            second.sufficient -> 0
            first.progress<=second.progress -> 0
            else -> 1
        }
        val range=if(idx==0)"2500–2999" else "3000–3499"
        val header=if(!connected)"Следующий режим • STEADY"
            else if(capturePaused)"Сбор приостановлен • STEADY"
            else "Следующий режим • STEADY"
        val details="$range об/мин  ·  MAP 105–129 кПа"
        return Guidance(header,
            if(capturePaused) "$details\nПродолжи тест, когда будет безопасно."
            else if(!connected) "$details\nСначала подключи DME и запусти сбор."
            else "$details\nПоддерживай плавный режим только если позволяют дорожные условия.",
            idx,false)
    }

    fun stateText(logging:Boolean,connected:Connection,hasFuel:Boolean,
                  first:Target,second:Target,paused:Boolean):String = when {
        connected==Connection.ERROR -> "Ошибка подключения"
        connected==Connection.RECONNECTING -> "Переподключение"
        connected==Connection.CONNECTING -> "Ожидание подключения"
        !logging -> "Диагностика остановлена"
        connected!=Connection.CONNECTED -> "Ожидание DME"
        paused -> "Сбор приостановлен"
        !hasFuel -> "Выбери заправленное топливо"
        first.sufficient && second.sufficient -> "Данные собраны • проверить в Анализе"
        else -> "Сбор данных"
    }
}
