package com.example.bmwenettest

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Bounded, lossless-in-memory CSV write queue for the active logger.
 *
 * The single consumer preserves append order for every file; send() applies
 * backpressure instead of dropping ECU samples if storage stalls. Durable
 * writes still depend on Android filesystem health, and failures are reported.
 *
 * The caller MUST call closeAndJoin() in NonCancellable before exiting.
 */
internal class AsyncCsvWriter(
    private val onFailure:(File,Throwable)->Unit,
    capacity:Int=512
) {
    data class QueueSnapshot(
        val depth:Int,val offered:Long,val written:Long,val lost:Long,
        val failedFiles:Int,val waitP95Ms:Double?,val writeP95Ms:Double?,
        val maxDepth:Int
    )
    private data class Entry(val file:File,val text:String,val createdAtNs:Long)
    private val queue=Channel<Entry>(capacity)
    private val pending=AtomicInteger(0)
    private val maximum=AtomicInteger(0)
    private val offered=AtomicLong(0)
    private val written=AtomicLong(0)
    private val lost=AtomicLong(0)
    private val statsLock=Any()
    private val waiting=LongArray(128)
    private val writing=LongArray(128)
    private var waitTotal=0L
    private var writeTotal=0L
    private fun recordTiming(waitNs:Long,writeNs:Long) = synchronized(statsLock) {
        waiting[(waitTotal++%waiting.size).toInt()]=waitNs.coerceAtLeast(0L)
        writing[(writeTotal++%writing.size).toInt()]=writeNs.coerceAtLeast(0L)
    }
    private fun percentile(buffer:LongArray,count:Long):Double? {
        if(count==0L)return null
        val n=minOf(count,buffer.size.toLong()).toInt()
        val sorted=buffer.take(n).sorted()
        val ix=(kotlin.math.ceil(.95*n).toInt()-1).coerceIn(0,n-1)
        return sorted[ix]/1_000_000.0
    }
    fun snapshot():QueueSnapshot=synchronized(statsLock) {
        QueueSnapshot(pending.get(),offered.get(),written.get(),lost.get(),
            failed.size,percentile(waiting,waitTotal),
            percentile(writing,writeTotal),maximum.get())
    }
    private val failed=ConcurrentHashMap.newKeySet<String>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val consumer=scope.launch {
        val writers=linkedMapOf<String,BufferedWriter>()
        try {
            for(entry in queue) {
                pending.decrementAndGet()
                val begun=System.nanoTime()
                val key=entry.file.absolutePath
                if(key in failed) {
                    lost.incrementAndGet() // observable failure, never silent
                    continue
                }
                try {
                    val writer=writers.getOrPut(key) {
                        FileOutputStream(entry.file,true).bufferedWriter()
                    }
                    writer.append(entry.text).append('\n')
                    writer.flush()
                    written.incrementAndGet()
                } catch(e:Exception) {
                    lost.incrementAndGet()
                    failed.add(key)
                    try { writers.remove(key)?.close() } catch(_:Exception) {}
                    try { onFailure(entry.file,e) } catch(_:Exception) {}
                } finally {
                    recordTiming(begun-entry.createdAtNs,System.nanoTime()-begun)
                }
            }
        } finally {
            for((path,writer) in writers) {
                try { writer.flush();writer.close() }
                catch(e:Exception) {
                    failed.add(path)
                    try { onFailure(File(path),e) } catch(_:Exception) {}
                }
            }
        }
    }

    /**
     * Suspends on a full queue. Never silently drops a record for throughput.
     * Failure after enqueue is reported through onFailure.
     */
    /**
     * Best-effort, bounded research telemetry. It MUST NOT stall FAST polling.
     * A full/closed queue is explicitly counted and reported, not ignored.
     * Essential primary rows continue to use suspending append(backpressure).
     */
    fun offerAudit(file:File,line:String):Boolean {
        if(file.absolutePath in failed) {
            lost.incrementAndGet()
            return false
        }
        val depth=pending.incrementAndGet()
        val result=queue.trySend(Entry(file,line,System.nanoTime()))
        if(!result.isSuccess) {
            pending.decrementAndGet()
            // Do not invoke the onFailure callback on the DME polling
            // thread: it may synchronously write a log/broadcast. Loss is
            // visible via snapshot().lost and the High-Speed benchmark.
            lost.incrementAndGet()
            return false
        }
        maximum.accumulateAndGet(depth){a,b->maxOf(a,b)}
        offered.incrementAndGet()
        return true
    }

    suspend fun append(file:File,line:String):Boolean {
        if(file.absolutePath in failed) {
            lost.incrementAndGet()
            return false
        }
        val count=pending.incrementAndGet()
        maximum.accumulateAndGet(count){a,b->maxOf(a,b)}
        try {
            queue.send(Entry(file,line,System.nanoTime()))
            offered.incrementAndGet()
        } catch(e:Exception) {
            pending.decrementAndGet()
            throw e
        }
        return file.absolutePath !in failed
    }

    /** Complete all queued writes and close descriptors before shutting down. */
    suspend fun closeAndJoin() {
        queue.close()
        consumer.join()
    }
}
