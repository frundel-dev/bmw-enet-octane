package com.example.bmwenettest

import java.io.File

/** Research timing CSV is deliberately excluded from ordinary OCTANE. */
internal object FastPhasePortFactory {
    fun create(dir:File,trip:Long,writer:AsyncCsvWriter):FastPhasePort?=null
}
