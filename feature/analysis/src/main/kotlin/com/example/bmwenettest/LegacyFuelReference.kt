package com.example.bmwenettest

/** Frozen historic relative signal reference; values reproduced unchanged from v1.7. */
object LegacyFuelReference {
    data class Cell(val v:Double,val n:Int)
    fun lookup(rpm:Double?, map:Double?):Cell? {
        if(rpm==null || map==null) return null
        val ri=when { rpm<2000->return null; rpm<2500->0; rpm<3000->1; rpm<3500->2; rpm<4000->3; rpm<4500->4; else->5 }
        val mi=when { map<130->return null; map<160->0; map<180->1; map<200->2; else->3 }
        val v=arrayOf(
            arrayOf<Cell?>(Cell(.073798,12),Cell(.070848,1),Cell(.069070,2),Cell(.071781,2)),
            arrayOf<Cell?>(Cell(.074039,19),Cell(.071964,10),Cell(.074590,5),Cell(.081251,9)),
            arrayOf<Cell?>(Cell(.076440,10),Cell(.082133,4),Cell(.082402,6),Cell(.093991,7)),
            arrayOf<Cell?>(Cell(.079952,8),Cell(.086841,3),Cell(.097288,2),Cell(.097531,10)),
            arrayOf<Cell?>(Cell(.085714,9),Cell(.095348,3),Cell(.104305,2),Cell(.114581,9)),
            arrayOf<Cell?>(null,null,null,null)
        )
        return v[ri][mi]
    }

}
