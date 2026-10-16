package com.example.bmwenettest

import org.junit.Assert.*
import org.junit.Test

class DiagnosticBuildStampTest {
    @Test fun rawMetadataReflectsTheActualCompiledApkVariant() {
        assertEquals(225,BuildConfig.VERSION_CODE)
        assertEquals("2.0.225",BuildConfig.VERSION_NAME.removeSuffix("-lab"))
        assertEquals(
            BuildConfig.VERSION_NAME+"/"+BuildConfig.VERSION_CODE,
            DiagnosticBuildStamp.current)
        assertFalse(DiagnosticBuildStamp.current.contains("/205"))
        if(BuildConfig.FLAVOR=="lab")
            assertEquals("2.0.225-lab/225",DiagnosticBuildStamp.current)
        else
            assertEquals("2.0.225/225",DiagnosticBuildStamp.current)
    }

    @Test fun csvVersionStampNeverInjectsExtraFieldsOrLineBreaks() {
        assertEquals("2.0.225-lab/225",
            DiagnosticBuildStamp.format("2.0.225-lab",225))
        assertEquals("2.0;225-lab/225",
            DiagnosticBuildStamp.format("2.0,225-lab",225))
        assertEquals("2.0 225/225",
            DiagnosticBuildStamp.format("2.0\n225",225))
    }
}
