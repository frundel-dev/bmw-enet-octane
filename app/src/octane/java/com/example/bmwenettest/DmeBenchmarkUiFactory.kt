package com.example.bmwenettest

import android.app.Activity
import android.content.Intent
import android.view.View
import java.io.File

/** No Benchmark Suite classes or research UI are linked in OCTANE. */
internal object DmeBenchmarkUiFactory {
    fun create(activity:Activity,dataDir:File,canStart:()->String?,
               start:()->Unit,export:(File)->Unit):DmeBenchmarkUi = object:DmeBenchmarkUi {
        override fun panel():View?=null
        override fun status(intent:Intent)=Unit
        override fun visibility(visible:Boolean)=Unit
    }
}
