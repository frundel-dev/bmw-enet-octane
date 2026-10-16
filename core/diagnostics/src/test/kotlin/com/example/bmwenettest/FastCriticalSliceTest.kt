package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FastCriticalSliceTest {
    @Test fun fastOrderIsContiguousAndNoSlowReadCanInterleave() {
        val order=mutableListOf<String>()
        var now=100L
        val c=FastCriticalSlice.collect(
            clockMs={now+=5;now},
            readObd={id->
                order.add("OBD:%02X".format(id))
                when(id) {
                    0x0C -> byteArrayOf(0x0B,0xB8.toByte()) // 750 rpm
                    0x04 -> byteArrayOf(128.toByte())
                    0x0B -> byteArrayOf(101)
                    else -> error("unexpected OBD PID")
                }
            },
            afterObd={obd->
                order.add("VALIDATE")
                assertTrue(obd.complete)
            },
            readFastDme={
                order.add("UDS:4A36")
                order.add("UDS:KNOCK_x4")
                order.add("UDS:IGN_x4")
                "B+"
            }
        )
        assertEquals(listOf("OBD:0C","OBD:04","OBD:0B","VALIDATE",
            "UDS:4A36","UDS:KNOCK_x4","UDS:IGN_x4"),order)
        assertEquals(750.0,c.obd.rpm!!,0.0)
        assertEquals(101.0,c.obd.map!!,0.0)
        assertEquals(128.0*100/255,c.obd.load!!,0.00001)
        assertEquals("B+",c.dme)
        assertTrue(c.obd.rpmLatencyMs>=0)
    }

    @Test fun missingObdValuesNeverInventZeroOrForceDmeFallback() {
        val c=FastCriticalSlice.collect(clockMs={1000L},
            readObd={null}, afterObd={
                assertNull(it.rpm)
                assertNull(it.load)
                assertNull(it.map)
                assertFalse(it.complete)
            },readFastDme={"A"})
        assertNull(c.obd.rpm)
        assertEquals("A",c.dme)
    }

    @Test fun thirdConsecutiveEmptyObdAbortsBeforeKnockTransactions() {
        var fails=2
        val calls=mutableListOf<Int>()
        try {
            FastCriticalSlice.collect(clockMs={1000L},readObd={id->
                calls.add(id);null
            },afterObd={o->
                if(o.rpm==null && o.load==null && o.map==null) {
                    fails++
                    if(fails>=3)throw IOException("No ECU OBD reply for 3 consecutive polls")
                }
            },readFastDme={error("should not request knock on lost OBD")})
            fail("Expected transport failure")
        } catch(e:IOException) {
            assertEquals("No ECU OBD reply for 3 consecutive polls",e.message)
        }
        assertEquals(listOf(0x0C,0x04,0x0B),calls)
    }

    @Test fun diagnosticExceptionPropagatesWithoutPretendingToComplete() {
        val calls=mutableListOf<Int>()
        try {
            FastCriticalSlice.collect(clockMs={1000L},readObd={id->
                calls.add(id)
                if(id==0x04)throw IOException("ACK without DATA")
                byteArrayOf(0)
            },afterObd={error("OBD failed")},readFastDme={error("DME must not run")})
            fail("Expected original exception")
        } catch(e:IOException) { assertEquals("ACK without DATA",e.message) }
        assertEquals(listOf(0x0C,0x04),calls)
    }

    @Test fun allSupportedBPlusBAProfilesUseIdenticalFastObdIds() {
        for(mode in listOf("B+","B","A")) {
            val obd=mutableListOf<Int>()
            var dmeCalls=0
            val c=FastCriticalSlice.collect(clockMs={2000L},readObd={id->
                obd.add(id);null
            },afterObd={},readFastDme={dmeCalls++;mode})
            assertEquals(listOf(0x0C,0x04,0x0B),obd)
            assertEquals(1,dmeCalls)
            assertEquals(mode,c.dme)
        }
    }
}
