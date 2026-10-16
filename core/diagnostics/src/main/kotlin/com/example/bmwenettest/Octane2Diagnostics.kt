package com.example.bmwenettest

import kotlin.math.abs

/**
 * BMW OCTANE 2.0: RAM 0x23 only after manual SWFL match and a stationary
 * cross-check against OBD. No arbitrary addresses or scan, no UDS 0x2C.
 */
class Octane2RamPolicy(private val manuallyConfirmed:Boolean) {
    enum class State { LOCKED, NEEDS_PARKED_CHECK, ACTIVE, DISABLED_UNTIL_RESTART }
    var state=if(manuallyConfirmed)State.NEEDS_PARKED_CHECK else State.LOCKED
        private set
    var reason=if(manuallyConfirmed)"Waiting for parked OBD validation" else "SWFL not confirmed"
        private set

    fun canRead(obdRpm:Double?,speed:Double?,speedFresh:Boolean):Boolean=when(state) {
        State.LOCKED,State.DISABLED_UNTIL_RESTART -> false
        State.NEEDS_PARKED_CHECK -> speedFresh && speed==0.0 &&
            obdRpm!=null && RamMemoryProtocol.parkedIdleRpm(obdRpm)
        State.ACTIVE -> true
    }

    /** A positive response with plausible RPM never proves dynamic timing accuracy. */
    fun accept(ramRpm:Double?,obdRpm:Double?):Boolean {
        if(state==State.DISABLED_UNTIL_RESTART || state==State.LOCKED)return false
        if(ramRpm==null || !ramRpm.isFinite() || obdRpm==null || !obdRpm.isFinite()) {
            disable("missing/invalid RAM or OBD RPM")
            return false
        }
        if(state==State.NEEDS_PARKED_CHECK) {
            if(!RamMemoryProtocol.matchesObdRpm(obdRpm,obdRpm,ramRpm.toInt())) {
                disable("initial stationary RAM/OBD mismatch")
                return false
            }
            state=State.ACTIVE
            reason="Parked check passed; dynamic comparison still experimental"
        }
        // These are sequential, not synchronized, reads; a large mismatch
        // invalidates RAM as the primary source for this LOGGER run.
        if(abs(ramRpm-obdRpm)>350.0) {
            disable("RAM/OBD deviation >350 rpm")
            return false
        }
        return true
    }

    fun onReconnect() {
        if(state==State.ACTIVE) {
            state=State.NEEDS_PARKED_CHECK
            reason="Reconnect: parked OBD check required"
        }
    }

    fun disable(why:String) {
        state=State.DISABLED_UNTIL_RESTART
        reason=why
    }
}

/** Standard OBD, NOT the A2L address or the target fuel quality model. */
object Octane2Lambda {
    data class Sample(val lambda:Double,val auxiliary:Double,val sourcePid:Int)
    // PID 20 is an exact 4-byte bitmask for PIDs 21..40 inclusive.
    fun supported(mask:ByteArray?,pid:Int):Boolean {
        if(pid !in 0x21..0x40 || mask?.size!=4)return false
        val offset=pid-0x21
        return (mask[offset/8].toInt() and (0x80 ushr (offset%8)))!=0
    }
    // Mode 01 PID 00 is an exact 4-byte support bitmap for PIDs 01..20.
    fun supportedBase(mask:ByteArray?,pid:Int):Boolean {
        if(pid !in 0x01..0x20 || mask?.size!=4)return false
        val offset=pid-1
        return (mask[offset/8].toInt() and (0x80 ushr (offset%8)))!=0
    }
    // Wideband bank 1 sensor 1: equivalence-ratio output interpreted
    // as lambda per standard OBD PID descriptor; not commanded PID 0x44.
    fun decode(pid:Int,data:ByteArray?):Sample? {
        if(pid!=0x24 && pid!=0x34 || data?.size!=4)return null
        val x=((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
        val y=((data[2].toInt() and 255) shl 8) or (data[3].toInt() and 255)
        val lambda=x/32768.0
        val aux=if(pid==0x24)y/8192.0 else y/256.0-128.0
        if(lambda<=0.0 || lambda>=2.0)return null
        return Sample(lambda,aux,pid)
    }
    fun commandedEquivalence(data:ByteArray?):Double? {
        if(data?.size!=2)return null
        val value=(((data[0].toInt() and 255) shl 8) or
            (data[1].toInt() and 255))/32768.0
        return value.takeIf { it>0.0 && it<2.0 }
    }
    fun controlStatus(data:ByteArray?):String? {
        if(data?.size!=2)return null
        return when(data[0].toInt() and 255) {
            0 -> "ENGINE_OFF"
            1 -> "OPEN_INSUFFICIENT_TEMP"
            2 -> "CLOSED_LOOP"
            4 -> "OPEN_LOAD_OR_DECEL"
            8 -> "OPEN_SYSTEM_FAILURE"
            16 -> "CLOSED_LOOP_O2_FAULT"
            else -> null
        }
    }
}
