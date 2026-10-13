package com.example.bmwenettest

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
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
    private data class Entry(val file:File,val text:String)
    private val queue=Channel<Entry>(capacity)
    private val failed=ConcurrentHashMap.newKeySet<String>()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val consumer=scope.launch {
        val writers=linkedMapOf<String,BufferedWriter>()
        try {
            for(entry in queue) {
                val key=entry.file.absolutePath
                if(key in failed)continue
                try {
                    val writer=writers.getOrPut(key) {
                        FileOutputStream(entry.file,true).bufferedWriter()
                    }
                    // Buffered writer stays open, but flush each row to avoid
                    // increasing the loss window compared with v1.7.
                    writer.append(entry.text).append('\n')
                    writer.flush()
                } catch(e:Exception) {
                    failed.add(key)
                    try { writers.remove(key)?.close() } catch(_:Exception) {}
                    try { onFailure(entry.file,e) } catch(_:Exception) {}
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
    suspend fun append(file:File,line:String):Boolean {
        if(file.absolutePath in failed)return false
        queue.send(Entry(file,line))
        return file.absolutePath !in failed
    }

    /** Complete all queued writes and close descriptors before shutting down. */
    suspend fun closeAndJoin() {
        queue.close()
        consumer.join()
    }
}
