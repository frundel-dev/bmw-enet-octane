package com.example.bmwenettest

/**
 * Presentation-only cue for the two research STEADY targets.
 * No ECU requests and no claim of fuel quality or persisted evidence.
 */
internal object SteadyLiveCue {
    enum class Band { NO_DATA, OUTSIDE_RPM, LOW_MAP, TARGET_MAP, HIGH_MAP }
    data class Cue(val band:Band, val description:String, val holdPct:Int)

    fun evaluate(rpm:Double?, map:Double?, targetIndex:Int, holdMs:Long):Cue {
        if(rpm==null || map==null || !rpm.isFinite() || !map.isFinite())
            return Cue(Band.NO_DATA,"Ожидание свежих RPM / MAP от DME",0)
        if(rpm<2500.0 || rpm>=3500.0)
            return Cue(Band.OUTSIDE_RPM,"Цель: 2500–3499 об/мин",0)
        if(map<SteadyTargets.MAP_MIN)
            return Cue(Band.LOW_MAP,"MAP ниже 105 кПа",0)
        if(map>=SteadyTargets.MAP_MAX_EXCLUSIVE)
            return Cue(Band.HIGH_MAP,"MAP выше 129 кПа",0)
        if(SteadyTargets.targetIndex(rpm,map)!=targetIndex)
            return Cue(Band.TARGET_MAP,"RPM/MAP подходят • ожидаем стабильный STEADY",0)
        val held=holdMs.coerceIn(0L,SteadyTargets.MIN_DWELL_MS)
        val pct=(held*100L/SteadyTargets.MIN_DWELL_MS).toInt()
        val secondsLeft=((SteadyTargets.MIN_DWELL_MS-held+999L)/1000L)
        return Cue(Band.TARGET_MAP,
            if(secondsLeft>0L) "STEADY • удерживай ещё $secondsLeft с"
            else "STEADY 5 с • ждём подтверждения записи",pct)
    }
}
