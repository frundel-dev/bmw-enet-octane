# BMW OCTANE 2.0 — Automated Diagnostic Benchmark Suite (build 223)

**Status:** experimental read-only integration; road/bench verification still required. Parent build 222 PR #46 (`d47215ef43f2`). Main remains unchanged.

## Objective
A single button in **Ещё → КОМПЛЕКСНЫЙ ТЕСТ DME** starts one foreground-service session, using exactly the existing one-socket serialized DME polling owner. The six stages automatically run in this fixed order:

| Stage | Mode | High-Speed profile | Duration |
| --- | --- | --- | --- |
| 1 | B+ | LEGACY | 120 s |
| 2 | B+ | HIGH_SPEED_CORE | 120 s |
| 3 | B+ | HIGH_SPEED_EXTENDED | 120 s |
| 4 | B | HIGH_SPEED_CORE | 120 s |
| 5 | B+ | HIGH_SPEED_CORE (repeat) | 120 s |
| 6 | B+ | HIGH_SPEED_CORE (background trial) | 300 s |

Total measurement time **15 minutes**, plus connection, safety verification, and boundary overhead. Stage timing starts only when the intended mode/profile was applied and a completed diagnostic cycle confirms it. Timing uses `elapsedRealtime`; gaps and sample counts are separate. A stage is **not** proof that Android scheduled work continuously. Stage 6 explicitly prompts the operator to put OCTANE in the background; reports count actual observed background cycles, suspend gaps and whether background use was observed at all.

## Safety gates
- Start only with ordinary LOGGER stopped, fuel already selected (to preserve user session invariants), and VXSCAN local-only READY when enabled.
- Operator must independently confirm stationary vehicle, P gear, parking brake, fully ventilated area. **OBD cannot verify selector or parking brake.** Do not perform any acceleration.
- In the single diagnostic coroutine, do not begin until fresh OBD speed = 0 km/h (<= 5 s age), measured ECT 70–115 °C (<=16 s), and RPM between 500 and 1600. Missing or unsupported signals are not zero.
- Lose those conditions, detect nonzero speed, excessive coolant temperature, fallback to another profile, HSFZ error/reconnect, or user STOP → abort test. Save earlier stage measurements and partial report. Each stage has a bounded 90-second wait for valid conditions.
- The app does not send extra requests solely to prove readiness. Existing SLOW PID 0x0D and 0x05 sampling is used; no concurrency, resets, coding, adaptation, or DME write commands.
- Manual polling/high-speed user preferences are untouched; stage overrides exist only in the single polling coroutine for the benchmark. Existing recovery policy and SOCKET close/watchdog stay unchanged.

## Outputs
Existing `bmw_fast_tx_timing_v1_*.csv`, `bmw_fast_signal_timing_v1_*.csv`, `bmw_fast_cycle_timing_v1_*.csv`, `bmw_highspeed_benchmark_v1_*.csv`, `bmw_poll_benchmark_*.csv` and primary LOGGER formats are preserved.

New independent files in app external-files storage:
- `bmw_dme_benchmark_suite_v1_<timestamp>.csv` — WAIT/START/STAGE_END/PROGRESS/COMPLETE/ABORT, stage duration and count, full FAST sets, observed Hz, p95 cycle/host response skew, interruptions, background visibility and queue-loss delta.
- `bmw_dme_benchmark_suite_v1_<timestamp>_wire_v1.csv` — host timestamp per HSFZ request: request start, send start/end, verified ACK if present, DATA header first Android read return, full DATA, decode end, cycle publish. Missing ACK is an **empty** cell. Events are observed on Android; no data asserts ECU acquisition time or pure network vs ECU processing latency. Separate DATA timestamps avoid mistaking an ACK for a diagnostic reply.
- `bmw_dme_benchmark_suite_v1_<timestamp>.txt` — human-readable stage results, gaps, coverage, limitations and filenames. Available in app via **ПОСЛЕДНИЙ ОТЧЁТ DME**, text sharing or CSV export via Android system file picker.

New suite CSV/report is supplementary, not a lab-grade RON/MON determination. Repetition of stages 2 and 5 is exploratory reproducibility testing; there is no statistically established performance effect until multiple independent runs/conditions. No active extra ECU reads or RAM access are introduced.

## Automated checks
`:app:testDebugUnitTest` covers six-stage exact schedule, duplicate CORE, stable phase transitions, unsupported/stale/positive speed and ECT, bounded preflight, fallback abort, cycle gaps, CSV encoding and real host ACK-vs-DATA separation. `:app:lintDebug`, `:app:assembleDebug` must pass and APK artifact must be downloadable.

## On-car acceptance (not verified by CI)
1. Outdoors with parking brake and P, warmed engine and VXSCAN attached: only one service/single TCP, six stages begin without any manually triggered pulls; inspect measured RPM, ECT and vehicle-speed guard.
2. Verify observed effective B+, B and High-Speed profiles, stage boundaries in CSV with no unresolved transaction; compare actual FAST completeness/host skew and profile repetitions.
3. Try missing/stale speed, engine stop, wrong profile, forced VXSCAN loss and STOP: abort, save partial files, no stale values falsely classified as zero, no unintended continuing requests.
4. On stage 6, put app in background/screen off; verify reported cycle-gap distribution, lifecycle transitions, reconnect evidence and whether the full 5-minute window was really observed, not presumed.
5. Export stage CSV/text and accompanying v219 FAST/HSFZ files; validate timestamps/clock monotonicity, epoch IDs, CSV queue losses and last complete DATA timestamps. Only a real vehicle can verify HSFZ ACK frequency and actual end-to-end scheduling behavior.

**Rollback:** install build 222 or revert this single PR. No fuel data migrations or CSV schema alterations.
