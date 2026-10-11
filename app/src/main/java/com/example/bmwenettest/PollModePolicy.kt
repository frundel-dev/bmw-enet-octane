package com.example.bmwenettest

/**
 * v1.7.25: prefer validated single-TCP batch polling on the tested VXSCAN.
 *
 * Legacy v1.7.24 installs defaulted to AUTO (A/B/C/D). Migrate that legacy
 * preference only once; a user selecting AUTO afterwards keeps that choice.
 * Explicit A/C/D selections stay untouched for troubleshooting.
 */
internal object PollModePolicy {
    const val DEFAULT = "B"
    const val MIGRATED_KEY = "poll_v1725_default_migrated"
    val manualModes = listOf("B","A","AUTO","C","D")
    val automaticModes = listOf("A","B")

    fun resolve(stored:String?,alreadyMigrated:Boolean):String = when {
        stored !in manualModes -> DEFAULT
        stored=="AUTO" && !alreadyMigrated -> DEFAULT
        else -> stored!!
    }
}
