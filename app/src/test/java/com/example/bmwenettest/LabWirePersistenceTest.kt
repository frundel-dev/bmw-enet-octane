package com.example.bmwenettest

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * Includes actual LAB adapter + AsyncCsvWriter queue and close/drain path.
 * Earlier tests cover HSFZ framing and ACK/DATA timing without a real DME.
 */
class LabWirePersistenceTest {
    @Test fun lostDmeResponseIsWrittenBeforeBenchmarkAbort()=runBlocking {
        if(BuildConfig.FLAVOR!="lab")return@runBlocking
        val dir=java.nio.file.Files.createTempDirectory("octane-wire-").toFile()
        val writer=AsyncCsvWriter(onFailure={_,error->throw AssertionError(error)})
        try {
            val runtime=DmeBenchmarkRuntimeFactory.create(true,dir,1_000L,{_,_->})
                ?:error("LAB must provide a benchmark runtime")
            runtime.recordWire(DmeBenchmarkRuntime.Wire(
                wallMs=1_700_000_000_000L,channel="DID_4A37",
                requestStartNs=100L,sendStartNs=110L,sendEndNs=120L,
                ackVerifiedNs=130L,dataHeaderFirstReadNs=null,
                dataCompleteNs=null,decodeEndNs=null,
                valid=false,error="IOException",
                requestedIds=listOf(0x4A37),requestSid=0x22,
                recvPhase="WAIT_DATA",phaseAtNs=131L,
                failedAtNs=220L,exceptionClass="java.io.IOException",
                exceptionMessage="HSFZ diagnostic response timeout; reconnect",
                unfinished=true))
            // In the real Service the catch path calls flushWire before abort.
            runtime.flushWire(1,0,7L,230L,writer)
            runtime.abort(2_000L,"diagnostic/network failure: IOException",writer)
            runtime.finish(2_010L,writer)
            writer.closeAndJoin()
            val files=dir.listFiles().orEmpty()
            val wire=files.single {it.name.endsWith("_wire_v2.csv")}
            val lines=wire.readLines()
            assertEquals(3,lines.size)
            assertTrue(lines[0].contains("wire_schema=2"))
            assertEquals(DiagnosticWireTrace.HEADER,lines[1])
            val data=lines[2].split(",")
            assertEquals(lines[1].split(",").size,data.size)
            assertEquals("DID",data[19])
            assertEquals("0x22",data[20])
            assertEquals("0x4A37",data[21])
            assertEquals("WAIT_DATA",data[22])
            assertEquals("java.io.IOException",data[25])
            assertEquals("HSFZ diagnostic response timeout; reconnect",data[26])
            assertEquals("true",data[27])
            assertEquals("130",data[8])
            assertEquals("",data[9])
            assertEquals("",data[10])
            assertTrue(files.any{it.name.endsWith(".txt") &&
                it.readText().contains("ABORTED")})
        }finally{
            // The temp directory is test-only; never touches app fuel sessions.
            dir.deleteRecursively()
        }
    }
}
