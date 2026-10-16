package com.example.bmwenettest

/** Uses the installed APK's generated variant metadata, never a stale literal. */
internal object DiagnosticBuildStamp {
    fun format(versionName:String,versionCode:Int):String =
        "${versionName.replace(",", ";").replace("\n"," ")}/${versionCode}"
    val current:String get()=format(BuildConfig.VERSION_NAME,BuildConfig.VERSION_CODE)
}
