package com.example.bmwenettest

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RefuelSessionStoreTest {
    @get:Rule val temp=TemporaryFolder()

    private class MemoryPrefs:SharedPreferences {
        val values=mutableMapOf<String,Any>()
        override fun getAll():MutableMap<String,*> = values
        override fun getString(key:String?,defValue:String?):String?=
            values[key] as? String ?: defValue
        override fun getStringSet(key:String?,defValues:MutableSet<String>?):MutableSet<String>?=
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()?:defValues
        override fun getInt(key:String?,defValue:Int):Int=values[key] as? Int ?: defValue
        override fun getLong(key:String?,defValue:Long):Long=values[key] as? Long ?: defValue
        override fun getFloat(key:String?,defValue:Float):Float=values[key] as? Float ?: defValue
        override fun getBoolean(key:String?,defValue:Boolean):Boolean=
            values[key] as? Boolean ?: defValue
        override fun contains(key:String?):Boolean=values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(
            listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(
            listener:SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun edit():SharedPreferences.Editor=Edit(this)
        class Edit(private val p:MemoryPrefs):SharedPreferences.Editor {
            private val added=mutableMapOf<String,Any?>()
            private val deleted=mutableSetOf<String>()
            private var clear=false
            override fun putString(key:String?,value:String?)=apply{added[key!!]=value}
            override fun putStringSet(key:String?,values:MutableSet<String>?)=
                apply{added[key!!]=values?.toSet()}
            override fun putInt(key:String?,value:Int)=apply{added[key!!]=value}
            override fun putLong(key:String?,value:Long)=apply{added[key!!]=value}
            override fun putFloat(key:String?,value:Float)=apply{added[key!!]=value}
            override fun putBoolean(key:String?,value:Boolean)=apply{added[key!!]=value}
            override fun remove(key:String?)=apply{deleted.add(key!!)}
            override fun clear()=apply{clear=true}
            override fun commit():Boolean{
                if(clear)p.values.clear()
                deleted.forEach(p.values::remove)
                added.forEach{(key,value)->if(value==null)p.values.remove(key)
                    else p.values[key]=value}
                return true
            }
            override fun apply(){commit()}
        }
    }
    private fun store(p:MemoryPrefs):RefuelSessionStore =
        RefuelSessionStore(p,File(temp.root,"events_v2.csv"))
    private fun prefs()=MemoryPrefs().apply {
        edit().putInt("fuel_session_id",3)
            .putLong(GuidedFuelTest.PREF_ID,100L)
            .putInt(GuidedFuelTest.PREF_FUEL_SESSION,3)
            .putString(GuidedFuelTest.PREF_GRADE,"АИ-95")
            .putBoolean(GuidedFuelTest.PREF_ACTIVE,true)
            .putBoolean(BaselineLearning.PREF_ENABLED,true)
            .commit()
    }

    @Test fun pendingPersistsAcrossProcessRestartAndKeepsOldFuelSession() {
        val p=prefs(); val store=store(p)
        val pending=store.detected(25.0,70.0,1000L)!!
        assertEquals(3,p.getInt("fuel_session_id",0))
        assertFalse(p.getBoolean(GuidedFuelTest.PREF_ACTIVE,true))
        assertTrue(store.hasPending())
        assertEquals("REFUEL_PENDING",store.state())
        val reconstructed=store(p).pending()!!
        assertEquals(pending,reconstructed)
        assertEquals(3,reconstructed.oldSession)
        assertEquals(pending.id,store(p).detected(25.0,70.0,2000L)!!.id)
    }

    @Test fun rejectionRestoresOriginalAnalysisWithoutChangingSessionOrCsv() {
        val p=prefs()
        val event=store(p).detected(22.0,60.0,1000L)!!
        assertTrue(store(p).reject(event.id,2400L))
        assertFalse(store(p).hasPending())
        assertFalse(store(p).reject(event.id,2600L))
        assertEquals(3,p.getInt("fuel_session_id",0))
        assertEquals(100L,p.getLong(GuidedFuelTest.PREF_ID,0L))
        assertTrue(p.getBoolean(GuidedFuelTest.PREF_ACTIVE,false))
        assertTrue(p.getBoolean(BaselineLearning.PREF_ENABLED,false))
        assertEquals("АИ-95",p.getString(GuidedFuelTest.PREF_GRADE,""))
        assertTrue(File(temp.root,"events_v2.csv").readText().contains("REJECTED"))
    }

    @Test fun confirmedAutoCreatesExactlyOneIndependentSession() {
        val p=prefs()
        val event=store(p).detected(20.0,68.0,1100L)!!
        val result=store(p).confirm("АИ-100","АЗС Север","Нейтрально",2200L)!!
        assertTrue(result.wasPending)
        assertEquals(event.id,result.id)
        assertEquals(4,result.sessionId)
        assertEquals(4,p.getInt("fuel_session_id",0))
        assertEquals(event.id,p.getLong(GuidedFuelTest.PREF_ID,0L))
        assertEquals("АИ-100",p.getString(GuidedFuelTest.PREF_GRADE,""))
        assertEquals(RefuelSessionStore.MIXING_DISTANCE_KM,
            p.getFloat("mixing_remaining_km",0f))
        assertNull(store(p).pending())
        assertNull(store(p).confirm("АИ-95","", "Без оценки",3000L))
    }

    @Test fun manualConsumesAutoPendingAndAvoidsSecondSession() {
        val p=prefs();val event=store(p).detected(20.0,70.0,1100L)!!
        val result=store(p).confirm("АИ-98","", "Без оценки",1500L,manual=true)!!
        assertEquals(event.id,result.id)
        assertEquals(4,p.getInt("fuel_session_id",0))
        assertNull(store(p).pending())
    }

    @Test fun manualSessionWithoutPid2fAndCorrectionPreservesMeasurements() {
        val p=prefs()
        p.edit().putFloat("last_fuel_pct",30f).commit()
        val s=store(p)
        val r=s.confirm("АИ-95","АЗС 1","Без оценки",2000L,manual=true)!!
        val diagnosticSentinel="unchanged diagnostic csv"
        File(temp.root,GuidedFuelTest.filename(r.id)).writeText(diagnosticSentinel)
        assertTrue(s.editFuel(r.id,"АИ-100","АЗС 2","Понравилось"))
        assertEquals(4,p.getInt("fuel_session_id",0))
        assertEquals(r.id,p.getLong(GuidedFuelTest.PREF_ID,0L))
        assertEquals("АИ-100",p.getString(GuidedFuelTest.PREF_GRADE,""))
        assertEquals(diagnosticSentinel,
            File(temp.root,GuidedFuelTest.filename(r.id)).readText())
        assertEquals(RefuelSessionStore.MIXING_DISTANCE_KM,
            p.getFloat("mixing_remaining_km",0f))
        assertNull(s.detected(30.0,70.0,3000L)) // same manual refuel
    }

    @Test fun historicalFuelCorrectionDoesNotActivatePreviousTest() {
        val p=prefs()
        val oldId=73L
        File(temp.root,GuidedFuelTest.filename(oldId)).writeText("historical")
        val oldCurrent=p.getLong(GuidedFuelTest.PREF_ID,0L)
        assertTrue(store(p).editFuel(oldId,"АИ-92","АЗС","Нейтрально"))
        assertEquals(oldCurrent,p.getLong(GuidedFuelTest.PREF_ID,0L))
        assertEquals(3,p.getInt("fuel_session_id",0))
        assertEquals("historical",File(temp.root,GuidedFuelTest.filename(oldId)).readText())
    }

    @Test fun transitionPeriodNeverQualifiesAsIndependentFuelEvidence() {
        assertFalse(RefuelEligibility.mayAssessFuel(true,0.0))
        assertFalse(RefuelEligibility.mayAssessFuel(false,5.0))
        assertFalse(RefuelEligibility.mayAssessFuel(false,Double.NaN))
        assertTrue(RefuelEligibility.mayAssessFuel(false,0.0))
    }
}
