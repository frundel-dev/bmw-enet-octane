package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DiagnosticBuildStampTest {
    @Test fun rawMetadataReflectsTheActualCompiledApkVariant() {
        assertEquals(227,BuildConfig.VERSION_CODE)
        assertEquals("2.0.227",BuildConfig.VERSION_NAME.removeSuffix("-lab"))
        assertEquals(
            BuildConfig.VERSION_NAME+"/"+BuildConfig.VERSION_CODE,
            DiagnosticBuildStamp.current)
        assertFalse(DiagnosticBuildStamp.current.contains("/205"))
        if(BuildConfig.FLAVOR=="lab")
            assertEquals("2.0.227-lab/227",DiagnosticBuildStamp.current)
        else
            assertEquals("2.0.227/227",DiagnosticBuildStamp.current)
    }

    @Test fun csvVersionStampNeverInjectsExtraFieldsOrLineBreaks() {
        assertEquals("2.0.227-lab/227",
            DiagnosticBuildStamp.format("2.0.227-lab",227))
        assertEquals("2.0;227-lab/227",
            DiagnosticBuildStamp.format("2.0,227-lab",227))
        assertEquals("2.0 227/227",
            DiagnosticBuildStamp.format("2.0\n227",227))
    }
}
