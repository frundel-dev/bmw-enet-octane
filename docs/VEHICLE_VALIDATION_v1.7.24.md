# BMW OCTANE v1.7.24 — ENET/VXSCAN vehicle validation

This is a **test build**, not a validated octane-number meter. Calibration and the historical Fuel Score formula remain unchanged. All diagnostic polling is read-only. Do not interact with the app while driving; arrange a passenger or a stationary test operator. Do not deliberately induce knock or unsafe loads.

## What changed
- Incomplete HSFZ headers/bodies (timeout or EOF) invalidate stream synchronization and trigger connection recovery rather than silently continuing with a partial frame.
- FAST and secondary TCP readings are matched to requested OBD PID / UDS DID. OBD PID widths are checked.
- SLOW measurements have individual monotonic timestamps. Values older than 10 seconds are not reused for current engine measurements.
- Mode changes, disconnects and fallback clear cached SLOW sensor values. AUTO benchmark CSV adds two columns at the **end**, leaving previous columns in place: `slow_fresh_pids`, `slow_oldest_age_ms`.
- Existing legacy Fuel Score and calibration references remain frozen.

## Required tests, in this order

1. **Stationary connectivity:** with ignition on, verify ENET and VXSCAN separately if both are available. Check reconnect after unplug/replug and Wi-Fi drop. Never treat a successful CI build as on-car validation.
2. **Stationary response matching:** in mode A check RPM, load, MAP, IAT, coolant, cylinders 1–4 data, and null handling. Compare physically plausible values with a known-good diagnostic tool where possible.
3. **Warm engine log:** log at idle, then varied normal driving at safe legal speeds. Capture the same set of conditions with each polling mode A, B, C and D, and repeat AUTO. Record whether any requested mode reverted to A and why.
4. **Data aging:** check `bmw_poll_benchmark_*.csv`: `fast_knock_valid`, `fast_ign_valid` should both reach 4 in accepted full cycles. For secondary modes, check `slow_fresh_pids` and `slow_oldest_age_ms` to spot old data despite an apparently active socket.
5. **Error recovery:** simulate only safe link disconnect/reconnect, not ECU fault injection. Ensure event CSV contains `DISCONNECTED`, `RETRY`, `POLL_MODE_CHANGE` and appropriate `SECOND_TCP_FALLBACK` / `EXPERIMENT_ROLLBACK` when conditions occur.
6. **Fuel-score stability:** compare v1.7.23 and v1.7.24 only in matching operating conditions and same fuel session; investigate any differences as telemetry/selection effects. Do **not** retrain calibration in this release.

## Files to collect
Export every `bmw_enet_v1724_*.csv`, `bmw_steady_v1724_*.csv`,
`bmw_poll_benchmark_v1724_*.csv`, `bmw_poll_auto_v1724_*.csv`,
`bmw_dme_probe_v1724_*.csv`, `bmw_telemetry_v1724_*.csv`,
`bmw_connection_events.csv`, and `bmw_fuel_events.csv`.
Retain older version CSV for matched-session comparisons; do not mix files from
different trips under one independent test fold. Missing fields mean unknown,
not zero.

## Known limitations
- HSFZ has no unique per-request sequence ID; a delayed response with the *same* PID/DID cannot be perfectly distinguished from a current response. This build rejects wrong IDs and invalid lengths, not all possible late same-ID messages.
- One secondary worker timestamps sequentially received PIDs; its final snapshot is **not** a simultaneous measurement of all parameters.
- DME candidate IDs, knock-retard and injection pulse width remain unconfirmed and unused in Fuel Score.
- AUTO currently selects by complete FAST data sets per second with a conservative 12% improvement threshold. Secondary freshness is now logged for *manual validation*, not yet part of the AUTO selection objective.
