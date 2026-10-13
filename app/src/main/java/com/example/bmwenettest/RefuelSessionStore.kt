package com.example.bmwenettest

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Locale

/**
 * One authority for auto-detected and manual refuels. The detector never
 * increments fuel_session_id. The single atomic preference commit is the
 * session boundary seen by the LOGGER without a second DME connection.
 *
 * Files written by older releases remain untouched. Event CSV is audit only;
 * preferences are the durable source for pending events and active session.
 */
internal class RefuelSessionStore private constructor(
    private val prefs:SharedPreferences,private val eventFile:File,
    @Suppress("UNUSED_PARAMETER") unused:Boolean
) {
    companion object {
        private val lock=Any()
        const val MIXING_DISTANCE_KM=5f // heuristic quarantine, NOT proof of blending
        const val PREF_PENDING_ID="octane2_pending_refuel_id"
        const val PREF_PENDING_TIME="octane2_pending_refuel_time"
        const val PREF_PENDING_FROM="octane2_pending_refuel_from"
        const val PREF_PENDING_TO="octane2_pending_refuel_to"
        const val PREF_PENDING_SESSION="octane2_pending_old_session"
        const val PREF_ACTIVE_STATE="octane2_refuel_state"
        const val PREF_LAST_EVENT_ID="octane2_last_refuel_event_id"
        const val PREF_LAST_CONFIRMED_AT="octane2_last_refuel_confirmed_at"
        const val PREF_LAST_LEVEL="octane2_last_manual_level"
        const val PREF_LAST_MANUAL_AT="octane2_last_manual_at"
        const val PREF_LAST_SOURCE="octane2_last_refuel_source"
        const val PREF_WAS_ACTIVE="octane2_pending_was_active"
        const val PREF_WAS_BASELINE="octane2_pending_was_baseline"
        const val PREF_RATING_PREFIX="octane2_refuel_rating_"
        const val PREF_META_GRADE_PREFIX="octane2_refuel_grade_"
        const val PREF_META_STATION_PREFIX="octane2_refuel_station_"
        const val PREF_META_SESSION_PREFIX="octane2_refuel_session_"
        const val PREF_META_TIME_PREFIX="octane2_refuel_date_"
    }
    data class Pending(val id:Long,val detectedAt:Long,val from:Double?,
                       val to:Double?,val oldSession:Int)
    data class Confirmed(val id:Long,val sessionId:Int,val wasPending:Boolean)
    internal constructor(prefs:SharedPreferences,eventFile:File):this(prefs,eventFile,true)
    constructor(context:Context):this(context.getSharedPreferences("bmw_native",Context.MODE_PRIVATE),
        File(context.getExternalFilesDir(null)?:context.filesDir,
            "bmw_refuel_decisions_v2.csv"),true)

    fun pending():Pending?=synchronized(lock) {
        val id=prefs.getLong(PREF_PENDING_ID,0L)
        if(id<=0L)return@synchronized null
        Pending(id,prefs.getLong(PREF_PENDING_TIME,id),
            prefs.getFloat(PREF_PENDING_FROM,Float.NaN).toDouble().takeIf{it.isFinite()},
            prefs.getFloat(PREF_PENDING_TO,Float.NaN).toDouble().takeIf{it.isFinite()},
            prefs.getInt(PREF_PENDING_SESSION,prefs.getInt("fuel_session_id",1)))
    }

    private fun eventId(wall:Long):Long=
        maxOf(wall.coerceAtLeast(1L),prefs.getLong(PREF_LAST_EVENT_ID,0L)+1L)
    private fun audit(id:Long,time:Long,source:String,status:String,session:Int,
                      from:Double?,to:Double?) {
        try {
            if(!eventFile.exists())eventFile.writeText(
                "event_id,time_ms,source,status,fuel_session,from_pct,to_pct\n")
            eventFile.appendText(listOf(id,time,source,status,session,
                from?.let{String.format(Locale.US,"%.1f",it)}?:"",
                to?.let{String.format(Locale.US,"%.1f",it)}?:"").joinToString(",")+"\n")
        } catch(_:Exception) { /* audit errors never mutate diagnostic outcome */ }
    }

    /** Stable detector event; guard against duplicate announcements / manual refuel. */
    fun detected(from:Double,to:Double,wall:Long):Pending?=synchronized(lock) {
        pending()?.let{return@synchronized it}
        if(!from.isFinite() || !to.isFinite() || to-from<12.0 ||
            from !in 0.0..100.0 || to !in 0.0..100.0)return@synchronized null
        val lastManual=prefs.getLong(PREF_LAST_MANUAL_AT,0L)
        val manualLevel=prefs.getFloat(PREF_LAST_LEVEL,Float.NaN).toDouble()
        if(lastManual>0L && wall>=lastManual && wall-lastManual<3*60*60*1000L &&
            manualLevel.isFinite() && kotlin.math.abs(from-manualLevel)<5.0)
            return@synchronized null
        val id=eventId(wall)
        val current=prefs.getInt("fuel_session_id",1)
        val ok=prefs.edit()
            .putLong(PREF_PENDING_ID,id).putLong(PREF_PENDING_TIME,wall)
            .putFloat(PREF_PENDING_FROM,from.toFloat())
            .putFloat(PREF_PENDING_TO,to.toFloat())
            .putInt(PREF_PENDING_SESSION,current)
            .putLong(PREF_LAST_EVENT_ID,id)
            .putString(PREF_ACTIVE_STATE,"REFUEL_PENDING")
            .putBoolean(PREF_WAS_ACTIVE,prefs.getBoolean(GuidedFuelTest.PREF_ACTIVE,false))
            .putBoolean(PREF_WAS_BASELINE,prefs.getBoolean(BaselineLearning.PREF_ENABLED,false))
            .putBoolean(GuidedFuelTest.PREF_ACTIVE,false)
            .putBoolean(BaselineLearning.PREF_ENABLED,false)
            .commit()
        if(!ok)return@synchronized null
        audit(id,wall,"AUTO","PENDING",current,from,to)
        Pending(id,wall,from,to,current)
    }

    /**
     * Same operation for manual and automatic confirmation. Existing pending
     * event is consumed by its stable ID, with no duplicate session creation.
     * No previous CSV or fuel-test files are rewritten.
     */
    fun confirm(grade:String,station:String,rating:String,
                wall:Long=System.currentTimeMillis(),manual:Boolean=false):Confirmed?=
        synchronized(lock) {
            if(grade !in GuidedFuelTest.grades)return@synchronized null
            val pending=pending()
            if(!manual && pending==null)return@synchronized null
            val oldSession=prefs.getInt("fuel_session_id",1)
            if(pending!=null && pending.oldSession!=oldSession)return@synchronized null
            val id=pending?.id?:eventId(wall)
            // Session number and test ID are independent of the fuel grade.
            val newSession=oldSession+1
            val edit=prefs.edit()
                .putInt("fuel_session_id",newSession)
                .putLong(GuidedFuelTest.PREF_ID,id)
                .putInt(GuidedFuelTest.PREF_FUEL_SESSION,newSession)
                .putString(GuidedFuelTest.PREF_GRADE,grade)
                .putString(GuidedFuelTest.PREF_STATION,station.take(100))
                .putBoolean(GuidedFuelTest.PREF_ACTIVE,true)
                .putBoolean(GuidedFuelTest.PREF_DONE,false)
                .putString(PREF_RATING_PREFIX+id,rating)
                .putString(PREF_META_GRADE_PREFIX+id,grade)
                .putString(PREF_META_STATION_PREFIX+id,station.take(100))
                .putInt(PREF_META_SESSION_PREFIX+id,newSession)
                .putLong(PREF_META_TIME_PREFIX+id,wall)
                .putString("measurement_mode","AUTO")
                .putBoolean(BaselineLearning.PREF_ENABLED,false)
                .putFloat("mixing_remaining_km",MIXING_DISTANCE_KM)
                .putString(PREF_ACTIVE_STATE,"MIXING")
                .putLong(PREF_LAST_EVENT_ID,id)
                .putLong(PREF_LAST_CONFIRMED_AT,wall)
                .putString(PREF_LAST_SOURCE,if(pending!=null)"AUTO" else "MANUAL")
                .remove(PREF_PENDING_ID).remove(PREF_PENDING_TIME)
                .remove(PREF_PENDING_FROM).remove(PREF_PENDING_TO)
                .remove(PREF_PENDING_SESSION).remove(PREF_WAS_ACTIVE)
                .remove(PREF_WAS_BASELINE)
            if(pending==null) {
                edit.putLong(PREF_LAST_MANUAL_AT,wall)
                val fuel=prefs.getFloat("last_fuel_pct",Float.NaN)
                if(fuel.isFinite())edit.putFloat(PREF_LAST_LEVEL,fuel)
            }
            if(!edit.commit())return@synchronized null
            audit(id,wall,if(pending!=null)"AUTO" else "MANUAL",
                "CONFIRMED",newSession,pending?.from,pending?.to)
            Confirmed(id,newSession,pending!=null)
        }

    /** Reject a false event: unchanged fuel_session_id and unchanged CSVs. */
    fun reject(id:Long,wall:Long=System.currentTimeMillis()):Boolean=synchronized(lock) {
        val candidate=pending()?:return@synchronized false
        if(candidate.id!=id)return@synchronized false
        val oldActive=prefs.getBoolean(PREF_WAS_ACTIVE,false)
        val oldBaseline=prefs.getBoolean(PREF_WAS_BASELINE,false)
        val ok=prefs.edit().remove(PREF_PENDING_ID).remove(PREF_PENDING_TIME)
            .remove(PREF_PENDING_FROM).remove(PREF_PENDING_TO)
            .remove(PREF_PENDING_SESSION).remove(PREF_WAS_ACTIVE)
            .remove(PREF_WAS_BASELINE)
            .putBoolean(GuidedFuelTest.PREF_ACTIVE,oldActive)
            .putBoolean(BaselineLearning.PREF_ENABLED,oldBaseline)
            .putString(PREF_ACTIVE_STATE,"ACTIVE")
            .commit()
        if(ok)audit(id,wall,"AUTO","REJECTED",candidate.oldSession,
            candidate.from,candidate.to)
        ok
    }

    /** Pure metadata edit: no new logical session, detector reset or score reset. */
    fun editFuel(id:Long,grade:String,station:String,rating:String):Boolean=synchronized(lock) {
        if(grade !in GuidedFuelTest.grades || id<=0L)return@synchronized false
        val session=prefs.getInt(PREF_META_SESSION_PREFIX+id,-1)
        // Preserve ability to correct a historical refill created by 1.7.
        // Its older CSV may be the only identity record in the app.
        if(session<1 && id!=prefs.getLong(GuidedFuelTest.PREF_ID,0L) &&
            !File(eventFile.parentFile,GuidedFuelTest.filename(id)).isFile)
            return@synchronized false
        val editor=prefs.edit()
            .putString(PREF_META_GRADE_PREFIX+id,grade)
            .putString(PREF_META_STATION_PREFIX+id,station.take(100))
            .putString(PREF_RATING_PREFIX+id,rating)
        if(id==prefs.getLong(GuidedFuelTest.PREF_ID,0L)){
            editor.putString(GuidedFuelTest.PREF_GRADE,grade)
                .putString(GuidedFuelTest.PREF_STATION,station.take(100))
        }
        val ok=editor.commit()
        if(ok)audit(id,System.currentTimeMillis(),"USER","METADATA_EDIT",
            session.takeIf{it>0}?:prefs.getInt("fuel_session_id",1),null,null)
        ok
    }

    fun hasPending()=pending()!=null
    fun state():String=if(hasPending())"REFUEL_PENDING" else
        prefs.getString(PREF_ACTIVE_STATE,"ACTIVE")?:"ACTIVE"
}
