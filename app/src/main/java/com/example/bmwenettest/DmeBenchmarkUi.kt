package com.example.bmwenettest

import android.content.Intent
import android.view.View

/** Variant-owned UI surface; ordinary OCTANE has no research panel. */
internal interface DmeBenchmarkUi {
    fun panel():View?
    fun status(intent:Intent)
    fun visibility(visible:Boolean)
}
