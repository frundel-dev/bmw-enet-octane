# BMW OCTANE v1.7.26 — FAST/SLOW performance acceptance

This test version optimizes **single-TCP** collection. Fuel Score calibration and historical baselines remain frozen. All ECU operations remain read-only. Do not operate the app while driving; log using a passenger/test operator.

## Normal operation
- Default remains **B** (one TCP, two known-good four-DID UDS batches).
- B and B+ stagger optional SLOW requests: at most **two** per FAST loop, each PID with its own refresh deadline and validity timestamp.
- A keeps its prior behavior as diagnostic fallback; AUTO still compares **A/B only**.
- Manual **B+ is experimental**. It requests eight DIDs in one 0x22 message (4 knock uint32 + 4 ignition int16). Complete exact-order mixed-width UDS 0x62 required. A rejected/invalid batch causes **BPLUS_FALLBACK → B** for the remaining service session. Ordinary B packet errors still disable batching to effective A.
- C/D remain experimental manual two-TCP modes and are not part of AUTO.

## Benchmark CSV
`bmw_poll_benchmark_v1726_*.csv` preserves all v1.7.25 columns and **appends**:
`obd_ms,slow_ms,slow_requests,knock_status_ms,knock_phase_ms,ignition_phase_ms,probe_ms,foctan_ms,post_fast_ms,bplus_rejections,slow_local_fresh`.
Times are in milliseconds measured on the polling thread. `post_fast_ms` includes computation and some earlier CSV writes; it is **not** a standalone filesystem benchmark. In A/C/D, `slow_requests` and `slow_local_fresh` are blank.

## Minimum acceptance tests
1. Verify app version 1.7.26 and default mode B. Collect B for at least 10 minutes (warm idle, steady, safe transient acceleration). Record complete FAST cycles, Hz, P50/P95 latency, null counts and timestamps.
2. Repeat in fixed A under comparable conditions. Evaluate **complete four-knock + four-ignition sets per second** (not just raw loop Hz).
3. Manually select B+. Watch `requested_mode=B+`, `effective_mode=B+` only while the combined batch succeeds. If `BPLUS_FALLBACK` occurs, `effective_mode=B`; confirm all eight signals remain valid. Never assume the ECU supports eight DID batches without this test.
4. In both B/B+, verify that sparse IAT/coolant/oil/LTFT refresh does not yield stale data. Missing values must remain null, never be guessed or carried across a reconnect/mode switch.
5. Safely disconnect/reconnect once. Confirm correct HSFZ recovery, fresh PID timestamps after reconnection, no reused measurements from the old connection.
6. Collect `bmw_connection_events.csv`, benchmark CSV, `bmw_enet_v1726_fuel*.csv`, `bmw_telemetry_v1726_*.csv`, `bmw_steady_v1726_fuel*.csv`, `bmw_dme_probe_v1726_fuel*.csv`.
7. Recheck scores under matched fuel and operating conditions. **Do not** interpret the Fuel Score as independently validated research octane number.

## Known limitations
- There is no HSFZ transaction ID to reject a late same-DID response.
- B+ compatibility with actual DME/VXSCAN is unverified. Failure to support the mixed packet is not evidence of a damaged ECU.
- No secondary TCP is used in B/B+, but A and manual C/D behavior were left unchanged.
- B/B+ scheduler changes the frequency of environmental measurements. This can change which samples pass quality filtering, without changing the underlying Fuel Score formula.
- Disk I/O still happens on the logger thread; a separate bounded background CSV writer is deferred until measurements isolate an actual I/O bottleneck.
