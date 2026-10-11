package com.example.bmwenettest

/**
 * Fast-path policy: B+ by default, B if 8-DID batch is not accepted,
 * A for transport/batch fallback. Every mode uses one TCP socket.
 *
 * The old AUTO *poll-mode comparator* is removed. This does not affect the
 * unrelated AUTO driving/measurement mode.
 */
internal object PollModePolicy {
    const val DEFAULT = "B+"
    // Same migration key as v1.7.28 to preserve explicit B/A selections.
    const val MIGRATED_KEY = "poll_v1728_bplus_migrated"
    val manualModes = listOf("B+","B","A")

    fun resolve(stored:String?,alreadyMigrated:Boolean):String = when {
        stored !in manualModes -> DEFAULT
        stored=="B" && !alreadyMigrated -> DEFAULT
        else -> stored!!
    }
}
