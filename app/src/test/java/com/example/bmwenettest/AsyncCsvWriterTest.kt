package com.example.bmwenettest

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class AsyncCsvWriterTest {
    @Test fun drainsBoundedQueueInOrderOnClose()=runBlocking {
        val dir=Files.createTempDirectory("octane-csv-test").toFile()
        val file=File(dir,"cycles.csv")
        val errors=CopyOnWriteArrayList<String>()
        val writer=AsyncCsvWriter({_,e->errors.add(e.toString())},capacity=2)
        repeat(1000) { i -> assertTrue(writer.append(file,"$i,measurement")) }
        writer.closeAndJoin()
        assertTrue(errors.toString(),errors.isEmpty())
        val lines=file.readLines()
        assertEquals(1000,lines.size)
        lines.forEachIndexed { i,line -> assertEquals("$i,measurement",line) }
        dir.deleteRecursively()
        Unit
    }

    @Test fun separateFilesHaveIndependentOrderedAppendStreams()=runBlocking {
        val dir=Files.createTempDirectory("octane-csv-two").toFile()
        val first=File(dir,"fuel1.csv")
        val second=File(dir,"fuel2.csv")
        val writer=AsyncCsvWriter({_,e->throw AssertionError(e)},capacity=1)
        repeat(75) { i ->
            writer.append(first,"old-$i")
            writer.append(second,"new-$i")
        }
        writer.closeAndJoin()
        assertEquals((0 until 75).map{"old-$it"},first.readLines())
        assertEquals((0 until 75).map{"new-$it"},second.readLines())
        dir.deleteRecursively()
        Unit
    }

    @Test fun writeFailureIsReportedRatherThanSilentlyDropped()=runBlocking {
        val dir=Files.createTempDirectory("octane-csv-fail").toFile()
        val impossible=File(dir,"missing/child/file.csv")
        val errors=CopyOnWriteArrayList<String>()
        val writer=AsyncCsvWriter({file,e->errors.add("${file.name}:${e.javaClass.simpleName}")})
        writer.append(impossible,"row")
        writer.closeAndJoin()
        assertEquals(1,errors.size)
        assertTrue(errors[0].contains("file.csv"))
        dir.deleteRecursively()
        Unit
    }
    @Test fun auditOfferKeepsOrderAndReportsClosedQueue()=runBlocking {
        val dir=Files.createTempDirectory("octane-audit-offer").toFile()
        val file=File(dir,"audit.csv")
        val errors=CopyOnWriteArrayList<String>()
        val writer=AsyncCsvWriter({_,e->errors.add(e.message?:"error")},capacity=4)
        assertTrue(writer.offerAudit(file,"passive-1"))
        assertTrue(writer.append(file,"primary-2"))
        assertTrue(writer.offerAudit(file,"passive-3"))
        writer.closeAndJoin()
        assertEquals(listOf("passive-1","primary-2","passive-3"),file.readLines())
        assertFalse(writer.offerAudit(file,"after-close"))
        assertEquals(1L,writer.snapshot().lost)
        assertTrue(errors.single().contains("queue full or closed"))
        dir.deleteRecursively()
        Unit
    }

}
