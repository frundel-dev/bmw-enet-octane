package com.example.bmwenettest

import java.io.File

/** Research-only phases; the non-blocking queue counts rejected audit rows. */
internal object FastPhasePortFactory {
    fun create(dir:File,trip:Long,writer:AsyncCsvWriter):FastPhasePort?=try {
        val csv=File(dir,"bmw_fast_phase_timing_v1_${trip}.csv")
        csv.writeText("# fast_phase_schema=1;host_monotonic_ns=true;"+
            "ecu_sample_time_known=false;CSV_is_research_only=true\n"+
            FastPhaseTiming.HEADER+"\n")
        FastPhasePort { row -> writer.offerAudit(csv,FastPhaseTiming.encode(row)) }
    } catch(_:Exception) { null }
}
