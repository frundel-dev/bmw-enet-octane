package com.example.bmwenettest

import java.io.File

/** Standard OCTANE is compiled without the DME benchmark implementation. */
internal object DmeBenchmarkRuntimeFactory {
    fun create(enabled:Boolean,dir:File,startedMs:Long,
               emit:(String,Boolean)->Unit):DmeBenchmarkRuntime?=null
}
