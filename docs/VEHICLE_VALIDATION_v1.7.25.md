# BMW OCTANE v1.7.25 — mode B vehicle acceptance test

## Behavior and boundaries
- Default `poll_mode=B` (single TCP + batched read-only UDS for FAST DME DIDs).
- One-time migration of pre-v1.7.25 `AUTO` preference to B. Explicit prior A/C/D choices remain unchanged; AUTO explicitly selected later stays AUTO.
- AUTO now compares only **A and B** (5 s settle, ≥30 s measurement per stage). A > B comparisons do not start a secondary TCP connection.
- Mode A remains the safe fallback after rejected UDS batch or repeated TCP failures. Manual C/D modes remain experimental.
- No changes to historical AI-95 baseline, `OctaneCalibration.kt`, fuel scoring formula, ECU probing identifiers or diagnostic write services.

## Safe test order
1. **Stationary, ignition/engine appropriately on:** verify installed version 1.7.25 and default selected mode B on first launch/upgrade. No driver UI interaction while moving.
2. Connect using VXSCAN; check DME logs and displayed RPM, load, MAP, cylinder knock and ignition measurements. Observe `requested_mode=B` and `effective_mode=B` in `bmw_poll_benchmark_v1725_*.csv`.
3. Compare **B then A** on equivalent safe engine conditions. Repeat twice and collect duration, `sample_hz`, `fast_knock_valid=4`, `fast_ign_valid=4`, P95 latency, `batch_errors`, and reconnects. Expected B should improve complete FAST sets/s, but exact Hz depends on link/ECU.
4. Switch to AUTO. It must produce only A and B trials; `bmw_poll_auto_v1725_*.csv` must never record C or D. AUTO should select B when B is >12% faster with ≥90% full cycles, otherwise select A.
5. On an **ordinary safe** disconnection, verify recovery and no HSFZ data corruption. If UDS batch is rejected, `BATCH_FALLBACK` should appear, `effective_mode` becomes A. Repeated TCP interruptions should invoke `EXPERIMENT_ROLLBACK` to A.
6. Compare old and new scoring only within the same fuel/operating conditions. Expect the calibration formula to remain frozen; do not interpret Fuel Score as laboratory octane number.

## Collect files
`bmw_connection_events.csv`, all
`bmw_poll_benchmark_v1725_*.csv`,
`bmw_poll_auto_v1725_*.csv`,
`bmw_enet_v1725_fuel*.csv`,
`bmw_steady_v1725_fuel*.csv`,
`bmw_telemetry_v1725_*.csv` and `bmw_dme_probe_v1725_fuel*.csv`.

## Acceptance
- CI JVM tests, Android Lint and APK build pass.
- Vehicle: mode B gives consistent nonzero valid complete FAST sets, no repeated reconnects, monotonic plausible values. Automatic mode never invokes C/D.
- If B proves unreliable on a different ECU or gateway, manually select A, archive logs and investigate; do not silently change Fuel Score calibration.
