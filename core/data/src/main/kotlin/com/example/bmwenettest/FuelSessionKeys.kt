package com.example.bmwenettest

/**
 * Version-stable fuel-session persistence contract.
 * Values are copied verbatim from GuidedFuelTest; NO preference migration.
 */
object FuelSessionKeys {
    const val PREF_ID="guided_fuel_test_id"
    const val PREF_ACTIVE="guided_fuel_test_active"
    const val PREF_DONE="guided_fuel_test_done"
    const val PREF_FUEL_SESSION="guided_fuel_test_fuel_session"
    const val PREF_GRADE="guided_fuel_test_grade"
    const val PREF_STATION="guided_fuel_test_station"
    val grades=listOf("АИ-92","АИ-95","АИ-98","АИ-100","Другое")
    val selectableGrades=listOf("АИ-95","АИ-98","АИ-100")
    fun filename(id:Long)="bmw_fuel_test_v1731_$id.csv"
}
