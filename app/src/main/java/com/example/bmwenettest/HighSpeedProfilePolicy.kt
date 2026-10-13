package com.example.bmwenettest

/**
 * Experimental single-TCP request budgeting. Does not alter FAST DID lists,
 * transmit parallel requests, or claim ECU sample freshness.
 */
internal object HighSpeedProfilePolicy {
    const val PREF_PROFILE="octane2_highspeed_profile"
    enum class Profile { LEGACY, HIGH_SPEED_CORE, HIGH_SPEED_EXTENDED }
    fun resolve(raw:String?):Profile=Profile.entries.firstOrNull{it.name==raw}
        ?:Profile.LEGACY

    /**
     * Applies only to optional SLOW requests in existing B/B+ polling.
     * The caller skips due() entirely on zero budget, preserving deadlines.
     * Defaults to the established two-request behavior.
     */
    fun optionalBudget(profile:Profile,cycleNumber:Long,lastCycleMs:Long,
                       transportUnstable:Boolean):Int=when {
        profile==Profile.LEGACY -> 2
        transportUnstable -> 0
        profile==Profile.HIGH_SPEED_EXTENDED -> 2
        // When the FAST path is already slow, throttle optional reads on
        // alternate cycles. This is only a heuristic until A/B car testing.
        lastCycleMs>120L && cycleNumber%2L==1L -> 0
        else -> 1
    }
}
