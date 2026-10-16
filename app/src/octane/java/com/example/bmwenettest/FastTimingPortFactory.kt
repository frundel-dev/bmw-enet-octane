package com.example.bmwenettest

import java.io.File

/** No FAST timing laboratory implementation or CSV in ordinary OCTANE. */
internal object FastTimingPortFactory {
    fun create(dir:File,trip:Long,writer:AsyncCsvWriter):FastTimingPort?=null
}
