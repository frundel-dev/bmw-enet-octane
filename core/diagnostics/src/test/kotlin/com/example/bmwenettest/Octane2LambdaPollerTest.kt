package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class Octane2LambdaPollerTest {
    private val wideband=byteArrayOf(0x80.toByte(),0,0x80.toByte(),0)
    private val sensorMask=byteArrayOf(0x10,0,0x10,0)
    private val controlMask=byteArrayOf(0x20,0,0,0)

    @Test fun capabilityProbeIsOnceAndReadsStaySerializedWithOriginalOrder() {
        val poller=Octane2LambdaPoller()
        var now=1_000_000L
        val calls=mutableListOf<Int>()
        val reader:(Int)->ByteArray?={ id->
            calls.add(id)
            when(id) {
                0x20 -> sensorMask
                0x00 -> controlMask
                0x34 -> wideband
                0x03 -> byteArrayOf(2,0)
                else -> throw AssertionError("unexpected PID")
            }
        }
        val first=poller.poll({now},reader)
        assertEquals(listOf(0x20,0x00,0x34,0x03),calls)
        assertEquals(1.0,first.actual!!.lambda,0.00001)
        assertEquals("CLOSED_LOOP",first.fuelControlState)
        assertNotNull(first.freshActual(now))
        assertNull(first.freshActual(now+8001L))
        now+=3000L
        poller.poll({now},reader)
        assertEquals(4,calls.size)
        now+=1000L
        poller.poll({now},reader)
        assertEquals(listOf(0x20,0x00,0x34,0x03,0x34),calls)
        now+=1000L
        poller.poll({now},reader)
        assertEquals(listOf(0x20,0x00,0x34,0x03,0x34,0x03),calls)
    }

    @Test fun failedPreferredSensorFallsBackOnlyNextDueCycle() {
        val poller=Octane2LambdaPoller()
        var now=1_000_000L
        val calls=mutableListOf<Int>()
        val reader:(Int)->ByteArray?={id->
            calls.add(id)
            when(id) {
                0x20 -> sensorMask
                0x00 -> byteArrayOf(0,0,0,0)
                0x34 -> null
                0x24 -> wideband
                else -> throw AssertionError("unexpected PID")
            }
        }
        assertNull(poller.poll({now},reader).actual)
        assertEquals(listOf(0x20,0x00,0x34),calls)
        now+=3999L
        assertNull(poller.poll({now},reader).actual)
        assertEquals(3,calls.size)
        now+=1L
        val selected=poller.poll({now},reader)
        assertEquals(0x24,selected.actual!!.sourcePid)
        assertNotNull(selected.freshActual(now))
        assertEquals(listOf(0x20,0x00,0x34,0x24),calls)
        now+=4000L
        poller.poll({now},reader)
        assertEquals(0x24,calls.last())
    }

    @Test fun unsupportedMaskNeverInventsSensorOrFuelControl() {
        val poller=Octane2LambdaPoller()
        val calls=mutableListOf<Int>()
        val reader:(Int)->ByteArray?={id->
            calls.add(id)
            if(id==0x20 || id==0x00)byteArrayOf(0,0,0,0)
            else throw AssertionError("unsupported PID read")
        }
        assertNull(poller.poll({1000L},reader).actual)
        assertNull(poller.poll({9000L},reader).fuelControlState)
        assertEquals(listOf(0x20,0x00),calls)
    }

    @Test fun reconnectClearsCachedDataAndRequiresFreshCapabilities() {
        val poller=Octane2LambdaPoller()
        val reader:(Int)->ByteArray?={id->
            when(id) {
                0x20 -> sensorMask
                0x00 -> controlMask
                0x34 -> wideband
                0x03 -> byteArrayOf(2,0)
                else -> null
            }
        }
        assertNotNull(poller.poll({1000L},reader).freshActual(1000L))
        poller.reset()
        var masks=0
        val unsupported:(Int)->ByteArray?={id->
            if(id==0x20 || id==0x00) {
                masks++
                byteArrayOf(0,0,0,0)
            } else throw AssertionError("stale sensor queried after reconnect")
        }
        val next=poller.poll({2000L},unsupported)
        assertEquals(2,masks)
        assertNull(next.actual)
        assertEquals(0L,next.actualReceivedAtMs)
        assertNull(next.fuelControlState)
        assertNull(next.freshActual(2000L))
    }

    @Test fun transportFailurePropagatesToExistingSocketRecovery() {
        val poller=Octane2LambdaPoller()
        try {
            poller.poll({1000L}){id->
                if(id==0x20)throw IOException("lost HSFZ DATA after ACK")
                null
            }
            fail("IOException must not be suppressed")
        } catch(e:IOException) {
            assertEquals("lost HSFZ DATA after ACK",e.message)
        }
    }
}
