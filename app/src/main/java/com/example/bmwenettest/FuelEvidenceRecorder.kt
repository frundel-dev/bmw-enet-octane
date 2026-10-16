package com.example.bmwenettest

import android.content.SharedPreferences
import java.io.File

/**
 * Owner-thread state for the existing read-only baseline/guided fuel CSV recorder.
 * No DME socket, no ECU requests. The control conditions, filenames and samples
 * were copied from EnetLoggerService.loop() with no scoring changes.
 */
internal class FuelEvidenceRecorder(
    private val prefs:SharedPreferences,
    private val dataDir:File,
    private val emit:(String)->Unit
) {
    private var learningSteadySegment=0
    private var learningAccelSegment=0
    private var learningLastSteadyPhaseMs=0L
    private var learningLastAccelPhaseMs=0L
    private var learningLastSteadyWritten=0L
    private var learningLastAccelWritten=0L
    private var learningLastSteadyCell:BaselineLearning.Cell?=null
    private var learningLastAccelRpm:Double?=null
    private var guidedDatasetId=0L
    private var guidedCoverage=GuidedFuelTest.Coverage()

    fun record(
        now:Long,learningTripId:Long,fuelSessionId:Int,
        autoMode:Boolean,fuelQuarantined:Boolean,warm:Boolean,
        trimComparable:Boolean,speed:Double?,knockMean:Double?,
        ignSpread:Double?,knockStatus:Int?,superKnock:Int?,
        allFastValid:Boolean,r:Double?,l:Double?,m:Double?,
        i:Double?,coolant:Double?,stft1:Double?,ltft1:Double?,
        steadyAuto:Boolean,accelerationAuto:Boolean
    ) {
// Experimental baseline collection uses the same already-read
                    // DME FAST/SLOW values. No new ECU transactions, no mutation
                    // of the frozen Fuel Score calibration, and no RON estimate.
                    val baselineRecording=prefs.getBoolean(BaselineLearning.PREF_ENABLED,false) &&
                        prefs.getInt(BaselineLearning.PREF_FUEL_ID,-1)==fuelSessionId
                    val guidedId=prefs.getLong(GuidedFuelTest.PREF_ID,0L)
                    val guidedRecording=guidedId>0L &&
                        prefs.getBoolean(GuidedFuelTest.PREF_ACTIVE,false) &&
                        prefs.getInt(GuidedFuelTest.PREF_FUEL_SESSION,-1)==fuelSessionId
                    if(autoMode && (baselineRecording || guidedRecording) &&
                        !fuelQuarantined &&
                        warm && trimComparable && speed!=null && speed>=15.0 &&
                        knockMean!=null && knockMean>0.0 && ignSpread!=null &&
                        knockStatus!=1 && (superKnock==null || superKnock==0) &&
                        allFastValid &&
                        r!=null && l!=null && m!=null && i!=null && coolant!=null) {
                        val phase=when {
                            steadyAuto -> BaselineLearning.Phase.STEADY
                            accelerationAuto -> BaselineLearning.Phase.ACCEL
                            else -> null
                        }
                        if(phase!=null) {
                            val chosenCell=BaselineLearning.cell(phase,r,l,m)
                            if(chosenCell!=null) {
                                val interval=if(phase==BaselineLearning.Phase.STEADY)2500L else 1500L
                                val lastWrite=if(phase==BaselineLearning.Phase.STEADY)
                                    learningLastSteadyWritten else learningLastAccelWritten
                                if(phase==BaselineLearning.Phase.STEADY) {
                                    if(learningLastSteadyPhaseMs==0L ||
                                        now-learningLastSteadyPhaseMs>6000L ||
                                        chosenCell!=learningLastSteadyCell) learningSteadySegment++
                                    learningLastSteadyPhaseMs=now
                                    learningLastSteadyCell=chosenCell
                                } else {
                                    if(learningLastAccelPhaseMs==0L ||
                                        now-learningLastAccelPhaseMs>2500L ||
                                        (learningLastAccelRpm!=null && r<learningLastAccelRpm!!-180.0))
                                        learningAccelSegment++
                                    learningLastAccelPhaseMs=now
                                    learningLastAccelRpm=r
                                }
                                if(lastWrite==0L || now-lastWrite>=interval) {
                                    val id=prefs.getLong(BaselineLearning.PREF_DATASET,0L)
                                    if(baselineRecording && id>0) {
                                        val entry=BaselineLearning.Sample(
                                            System.currentTimeMillis(),learningTripId,fuelSessionId,
                                            phase,if(phase==BaselineLearning.Phase.STEADY)
                                                learningSteadySegment else learningAccelSegment,
                                            r,l,m,i,coolant,stft1,ltft1,knockMean,ignSpread)
                                        try {
                                            val file=File(dataDir,
                                                BaselineLearning.filename(id))
                                            if(!file.exists()) {
                                                val label=prefs.getString(BaselineLearning.PREF_LABEL,
                                                    "Текущее топливо")?:"Текущее топливо"
                                                val safe=label.replace(",", ";").replace("\n"," ").take(100)
                                                file.writeText("# observational_baseline=true;ron_unverified=true;fuel=$safe;fuel_session_id=$fuelSessionId;calibration=${OctaneCalibration.VERSION}\n"+
                                                    BaselineLearning.HEADER+"\n")
                                            }
                                            file.appendText(BaselineLearning.serialize(entry)+"\n")
                                            if(phase==BaselineLearning.Phase.STEADY)
                                                learningLastSteadyWritten=now
                                            else learningLastAccelWritten=now
                                        } catch(_:Exception) {
                                            // Storage failure never breaks primary DME sampling.
                                        }
                                    }
                                }
                                // The user-selected fuel test is separate from
                                // experimental self-baseline training, and is
                                // never used as its own external reference.
                                if(guidedRecording) {
                                    if(guidedDatasetId!=guidedId) {
                                        guidedDatasetId=guidedId
                                        guidedCoverage=GuidedFuelTest.Coverage()
                                        val old=File(dataDir,
                                            GuidedFuelTest.filename(guidedId))
                                        if(old.isFile)try {
                                            old.useLines { lines ->
                                                lines.forEach { line ->
                                                    BaselineLearning.parse(line)?.let { entry ->
                                                        if(entry.fuelSessionId==fuelSessionId)
                                                            guidedCoverage.accept(entry)
                                                    }
                                                }
                                            }
                                        } catch(_:Exception) {}
                                    }
                                    // Update only once per sample phase cadence.
                                    val didWrite=if(phase==BaselineLearning.Phase.STEADY)
                                        learningLastSteadyWritten==now else learningLastAccelWritten==now
                                    // Baseline may be disabled. If so, lastWritten
                                    // still needs advancing once an accepted entry
                                    // was durably saved to the guided CSV.
                                    val due=lastWrite==0L || now-lastWrite>=interval
                                    if((didWrite || due) &&
                                        !guidedCoverage.captureReady()) {
                                        val entry=BaselineLearning.Sample(
                                            System.currentTimeMillis(),learningTripId,fuelSessionId,
                                            phase,if(phase==BaselineLearning.Phase.STEADY)
                                                learningSteadySegment else learningAccelSegment,
                                            r,l,m,i,coolant,stft1,ltft1,knockMean,ignSpread)
                                        try {
                                            val file=File(dataDir,
                                                GuidedFuelTest.filename(guidedId))
                                            if(!file.exists()) {
                                                val grade=prefs.getString(GuidedFuelTest.PREF_GRADE,"Другое")?:"Другое"
                                                val station=prefs.getString(GuidedFuelTest.PREF_STATION,"")?:""
                                                val safe=station.replace(",", ";").replace("\n"," ").take(100)
                                                file.writeText("# fuel_test=true;grade=$grade;station=$safe;"+
                                                    "fuel_session=$fuelSessionId;self_calibration=false;"+
                                                    "ron_lab_verified=false;model=${OctaneCalibration.VERSION}\n"+
                                                    BaselineLearning.HEADER+"\n")
                                            }
                                            file.appendText(BaselineLearning.serialize(entry)+"\n")
                                            guidedCoverage.accept(entry)
                                            if(phase==BaselineLearning.Phase.STEADY)
                                                learningLastSteadyWritten=now
                                            else learningLastAccelWritten=now
                                            if(guidedCoverage.captureReady()) {
                                                prefs.edit().putBoolean(GuidedFuelTest.PREF_ACTIVE,false)
                                                    .putBoolean(GuidedFuelTest.PREF_DONE,true).apply()
                                                emit("Сбор теста топлива завершён: можно просмотреть предварительный результат. Сравнение не определяет лабораторный RON.")
                                            }
                                        } catch(_:Exception) { /* Logger must continue safely. */ }
                                    }
                                }
                            }
                        }
                    }

    }
}
