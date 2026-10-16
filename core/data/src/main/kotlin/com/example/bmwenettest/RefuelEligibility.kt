package com.example.bmwenettest

/** Guard for acceptance into any fuel-quality model, not the raw recorder. */
object RefuelEligibility {
    fun mayAssessFuel(pending:Boolean,mixingRemainingKm:Double):Boolean =
        !pending && mixingRemainingKm.isFinite() && mixingRemainingKm<=0.0
}
