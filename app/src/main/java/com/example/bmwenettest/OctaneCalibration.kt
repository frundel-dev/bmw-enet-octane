package com.example.bmwenettest

/**
 * Octane Engine v0.9: stratified STEADY survey + frozen v0.8 acceleration reference.
 *
 * The published v1.7.2/v1.7.3 high-load baseline is intentionally unchanged.
 *
 * ACCELERATION calibration: medians of accepted acceleration samples from
 * independent AI-95 logs v1.7.11, v1.7.12 and v1.7.16, restricted to
 * 200+ kPa MAP and 2500..4500 RPM. v1.7.17 was withheld for validation.
 * The hold-out ratios were approximately 1.048, 1.052, 1.057, 1.011.
 *
 * STEADY survey reference: legacy AI-95 v1.7.2 from two independent logs,
 * 8 rate-filtered observations for RPM 1300..2400 and MAP 88..105 kPa.
 * Five additional observations from other trips were inspected separately.
 * No other steady bins are calibrated yet; missing cells MUST NOT be
 * extrapolated or assigned a score. This model is NOT a laboratory RON test.
 */
object OctaneCalibration {
    const val VERSION = "0.9-stratified-survey"
    data class Reference(
        val knockMeanVms:Double,
        val trainingSamples:Int,
        val trainingTrips:Int,
        val heldOutSamples:Int,
        val validated:Boolean
    )

    // Use the *same* load/temperature/gas rules when comparing against these.
    fun accelerationReference(rpm:Double?,map:Double?):Reference? {
        if(rpm==null || map==null || !rpm.isFinite() || !map.isFinite() ||
            map<200.0 || rpm<2500.0 || rpm>=4500.0) return null
        return when {
            rpm<3000.0 -> Reference(0.092081,15,3,6,true)
            rpm<3500.0 -> Reference(0.109775,15,3,5,true)
            rpm<4000.0 -> Reference(0.121829,15,3,5,true)
            else -> Reference(0.131983,14,3,5,true)
        }
    }

    fun steadyReference(rpm:Double?,map:Double?,load:Double?):Reference? {
        if(rpm==null || map==null || load==null) return null
        if(rpm in 1300.0..<2400.0 && map in 88.0..<105.0 &&
            load in 15.0..68.0) return Reference(0.0690528,8,2,5,false)
        return null
    }

    fun steadyCell(rpm:Double,map:Double):String {
        val rb=when {
            rpm<2000.0->"1300-1999"
            rpm<2500.0->"2000-2499"
            rpm<3000.0->"2500-2999"
            rpm<3500.0->"3000-3499"
            else->"3500-4499"
        }
        val mb=when {map<105.0->"088-104";map<130.0->"105-129";else->"130-150"}
        return "$rb:$mb"
    }

    /**
     * Fine comparison key: RPM 250, MAP 8 kPa, load 12 percentage points,
     * IAT 6 C, coolant temperature 10 C. Each component is measured, not
     * corrected: do not infer a reference for an unseen combination.
     */
    fun fineSteadyCell(rpm:Double,map:Double,load:Double,iat:Double,coolant:Double):String {
        fun bucket(v:Double,width:Int)=(kotlin.math.floor(v/width).toInt()*width).toString()
        return "r${bucket(rpm,250)}:m${bucket(map-88.0,8)}:"+
            "l${bucket(load,12)}:i${bucket(iat,6)}:c${bucket(coolant,10)}"
    }

    /** A nominal trim marks a comparable observation, never an octane number. */
    fun trimsComparable(stft:Double?,ltft:Double?):Boolean {
        if(stft!=null && (!stft.isFinite() || kotlin.math.abs(stft)>15.0))return false
        if(ltft!=null && (!ltft.isFinite() || kotlin.math.abs(ltft)>15.0))return false
        return true
    }

    fun experimentalScore(weightedKnockRatio:Double):Double=
        (100.0+(1.0-weightedKnockRatio)*50.0).coerceIn(0.0,120.0)
}
