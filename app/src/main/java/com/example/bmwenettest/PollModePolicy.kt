package com.example.bmwenettest

/**
 * v1.7.28: priority to single-TCP B+ (8 strictly decoded DIDs).
 *
 * One-time migration upgrades the old B default and rejects obsolete C/D
 * settings. After migration, explicit manual B, A and AUTO stay selected.
 * All accepted strategies use one TCP connection.
 */
internal object PollModePolicy {
    const val DEFAULT = "B+"
    const val MIGRATED_KEY = "poll_v1728_bplus_migrated"
    val manualModes = listOf("B+","B","A","AUTO")
    val automaticModes = listOf("A","B")

    fun resolve(stored:String?,alreadyMigrated:Boolean):String = when {
        stored !in manualModes -> DEFAULT
        stored=="B" && !alreadyMigrated -> DEFAULT
        else -> stored!!
    }
}
