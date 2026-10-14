package com.example.bmwenettest

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import java.net.Socket
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class FastDidPassiveRecorderTest {
    private fun memoryPrefs(fuelSession:Int):SharedPreferences {
        val clazz=SharedPreferences::class.java
        return Proxy.newProxyInstance(clazz.classLoader,arrayOf(clazz)) {
            _, method, _ ->
            when(method.name) {
                "getInt" -> fuelSession
                "getBoolean" -> false
                "toString" -> "FakePrefs"
                else -> throw UnsupportedOperationException(method.name)
            }
        } as SharedPreferences
    }

    @Test fun passiveObserverDoesNotQuerySocketAndPreservesRealRawAndEpochs() = runBlocking {
        val dir=Files.createTempDirectory("octane-passive").toFile()
        val file=java.io.File(dir,"bmw_fast_did_validation_v1_777.csv")
        val errors=mutableListOf<String>()
        val writer=AsyncCsvWriter({_,e->errors.add(e.toString())})
        val observer=FastDidPassiveRecorder(memoryPrefs(7),writer,file,777L)
        val sock1=Socket()
        val sock2=Socket()
        try {
            val base=100_000_000_000L
            observer.onDecoded("OBD_0C",
                byteArrayOf(0x41,0x0C,0x27,0x10),base,base+10_000_000,sock1)
            observer.onDecoded("OBD_04",
                byteArrayOf(0x41,0x04,0x80.toByte()),base+11_000_000,base+12_000_000,sock1)
            observer.onDecoded("OBD_0B",
                byteArrayOf(0x41,0x0B,105),base+13_000_000,base+14_000_000,sock1)
            val knock=byteArrayOf(0x00,0x01,0x00,0x00)
            observer.onDecoded("DID_BATCH_8",mapOf(0x4A37 to knock),
                base+15_000_000,base+16_000_000,sock1)
            // The same DID cannot create duplicate research rows each FAST tick.
            observer.onDecoded("DID_BATCH_8",mapOf(0x4A37 to knock),
                base+20_000_000,base+21_000_000,sock1)
            // A reconnect resets epoch and keeps the new RAW result, even if
            // it occurs before the normal five-second sampling interval.
            observer.onDecoded("DID_4A37",byteArrayOf(0x00,0x02,0x00,0x00),
                base+22_000_000,base+23_000_000,sock2)
            writer.closeAndJoin()
            assertTrue(errors.toString(),errors.isEmpty())
            val data=file.readLines()
            assertTrue(data.first().startsWith("# passive_only=true;"))
            val rows=data.drop(2).filter{it.isNotBlank()}
            assertTrue(rows.all(FastDidAuditCsv::validateLine))
            val knockRows=rows.map{it.split(",")}.filter{it[10]=="4A37"}
            assertEquals(2,knockRows.size)
            assertEquals("00010000",knockRows[0][13])
            assertEquals("00020000",knockRows[1][13])
            assertEquals("0",knockRows[0][3])
            assertEquals("1",knockRows[1][3])
            assertEquals("7",knockRows[0][2])
            assertEquals("2500.000000",knockRows[0][17])
            assertEquals("105.000000",knockRows[0][19])
            assertEquals("SEMANTICS_UNVERIFIED",knockRows[0][12])
            val unknown=rows.map{it.split(",")}.single{it[10]=="5819"}
            assertEquals("NO_ADDITIONAL_QUERY",unknown[9])
            assertEquals("",unknown[13])
            assertEquals("NOT_VERIFIED",unknown[11])
        } finally {
            sock1.close()
            sock2.close()
            dir.deleteRecursively()
        }
        Unit
    }
}
