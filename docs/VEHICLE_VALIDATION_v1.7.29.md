# BMW OCTANE v1.7.29 — lean acquisition validation

## Removed (intentional simplification)

- Periodic `DmeReadOnlyProbe` dispatch **every 12 seconds** in the FAST logger (including its per-refuel reset and `bmw_dme_probe_v1729_*.csv` generation).
- AUTO **polling strategy comparator** (A/B trials, dynamic tuner, `bmw_poll_auto_*.csv` and UI report). This does **NOT** disable the unrelated driving mode called AUTO that segments STEADY/ACCEL, computes Fuel Score and tracks high-load measurements.
- Unused two-TCP and AUTO metrics from the **new** benchmark CSV schema.
- Menu entries for repeating probe history and A/B comparator.

## Retained (must not be removed for performance)

- B+ is selected by default (one TCP/one owner). User-selectable B and A only for troubleshooting. Strict parse and fallback B+→B→A remain, and after repeated TCP failures use safe A.
- Read-only DME Monitor for **0x554A/0x4A85/0x4A9D**, with per-request timestamps, ≥8s spacing and load/FAST gates. It is the sole periodic experimental DID monitor.
- Parked-only **DME history** scan (0x19 and 0x22) from the manual menu. No automatic DTC snapshot expansion during driving.
- Standard OBD RPM/LOAD/MAP, knock status, four knock amplitudes, four ignition values, staged SLOW measurements, superknock, fuel level and optional INFOFOCTAN.
- Strict HSFZ framing, socket timeouts/reconnects, negative-response validation, freshness TTL, null-on-loss and capture quality checks.
- Connection events, measurement/steady/telemetry CSVs, background monitor CSV, compact performance benchmark CSV.
- Frozen Fuel Score calibration; `OctaneCalibration.kt` must have the historical SHA.

## Compact `bmw_poll_benchmark_v1729_*.csv`

`wall_time_ms,elapsed_ms,requested_mode,effective_mode,fast_hz,cycle_ms,fast_dme_ms,obd_ms,slow_ms,slow_requests,knock_status_ms,knock_phase_ms,ignition_phase_ms,foctan_ms,post_fast_ms,batch_ok,batch_errors,bplus_rejections,fast_knock_valid,fast_ign_valid,slow_local_fresh,monitor_ok,monitor_errors,monitor_last_ms,monitor_state,monitor_last_id,transport,notes`

Older benchmark CSVs remain on disk but use an earlier (larger) schema.

## Acceptance

1. Confirm version 1.7.29 / code 47, default mode B+, and poll menu shows only **B+, B, A**.
2. Normal driving mode **AUTO** still detects ACCEL/STEADY samples and writes eligible Fuel Score observations exactly as v1.7.28.
3. Log 10–15 minutes B+ and compare complete FAST sets/second and latency with v1.7.27/1.7.28 (no guarantee of faster speed: removing one occasional read mostly reduces tail jitter).
4. Monitor CSV still contains periodic adaptation DIDs during moderate-load driving, no concurrent TCP, no sampling during strong accelerations, and can be inspected in the UI.
5. Confirm there is **no newly generated** `bmw_poll_auto_v1729_...` or `bmw_dme_probe_v1729_...` file. Historical files from older versions are not deleted.
6. Verify a B+ rejection falls back to B and a B rejection falls back to A. Never accept partial data.
7. Confirm manual stopped-LOGGER DME history scan still works. On 0x19 detail timeouts it must remain safely bounded.
8. Save `bmw_poll_benchmark_v1729_*.csv`, `bmw_dme_monitor_v1729_*.csv`, `bmw_connection_events.csv` and STEADY/ENET logs.

`./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` must pass CI. No ECU write/reset/programming commands are introduced.
