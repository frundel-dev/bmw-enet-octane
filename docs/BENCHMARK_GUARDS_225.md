# BMW OCTANE 2.0 LAB — stationary guards and benchmark exit fixes (build 225)

Base: modular build 224 PR #48, SHA `059e4e9bdffac1c07cd4232e06e01c21ad9b3256`. This PR is an isolated reliability patch; it does not merge PR #48 or rewrite ENET/HSFZ logic.

## Changes
1. **LAB benchmark guard SLOW polling:** `SlowPollScheduler.dueForBenchmark()` reserves speed OBD PID 0x0D every ~1.5 seconds and ECT PID 0x05 every ~6 seconds when due, even on a CORE cycle whose optional budget is zero. Max **two** SLOW requests per FAST cycle. Both execute through the same existing serialized `obd()` / single socket owner. Unrelated regular LOGGER SLOW policies are unchanged. If the socket stalls or a reply is rejected, the bench **fails closed** on missing/stale evidence; this is not a real-time OS guarantee.
2. **Detailed stop evidence:** independent, monotonic `lastValidAgeMs` survives a failed sensor reply, while `fresh()` is immediately false. Every aborted suite receives a named code (SPEED_STALE, SPEED_MISSING, MOVING, COOLANT_STALE, COOLANT_NOT_WARM, RPM_NOT_IDLE, PREFLIGHT_TIMEOUT, FAST_INCOMPLETE, etc.), last guard ages in milliseconds, actual sensor values and stage ID. For transport errors or manual STOP, ages are advanced from the last completed sample to the observed stop timestamp. Detail is included in existing benchmark CSV `notes` and the text report; no erroneous substitution of missing data with zero.
3. **Terminal classification:** `DmeBenchmarkRuntime.Termination` distinguishes COMPLETED and ABORTED. `PollingExitEvidence.StopOrigin` now classifies intentional `BENCHMARK_ABORT` and `BENCHMARK_COMPLETE` as explicit outcomes instead of `UNEXPECTED_NORMAL_RETURN`. USER_STOP and SERVICE_DESTROY precedence remains. `BENCHMARK_ABORT` events contain the recorded cause.
4. **Regression coverage:** deterministic virtual-clock test exercises **all six stages** with serialized 65–170ms SLOW replies, CORE zero-budget cycles, independent safety reads, stage-boundary resets and 5-minute background stage. Another test injects failed/stale guard replies to verify exact ages and abort. Existing legacy `due()` logic is unchanged.
5. **RAW metadata:** removed both stale `2.0.1-lab/205` strings from `FastDidPassiveRecorder`. All raw rows use `DiagnosticBuildStamp.current` sourced from per-flavor generated `BuildConfig`: OCTANE `2.0.225/225`, LAB `2.0.225-lab/225`. Updated on-screen app version, foreground notification and GitHub APK artifact names.

No ECU writing, coding, DTC reset or adaptation clearing. No new socket, no concurrent poller, no artificial FAST samples and no new traffic except bounded stationary-safety reads in the six-stage research mode.

## Required verification
- CI: all module and app JVM tests; OCTANE+LAB lint, assembly and dependency exclusion check. Merely passing Gradle is not a vehicle acceptance.
- Phone: old fuel sessions, app upgrade and CSV schemas unaffected. Compare stage CSV cause and event log reason on manual STOP, stale/invalid speed, ECT timeout, reconnect failure and normal completion.
- Vehicle parked in P with brake applied, outdoors and engine warm: full LAB suite; verify `0x0D` and `0x05` actual intervals/age; CORE still gets guard data when optional budget is zero. Confirm one HSFZ socket reader and throughput/freshness. Do not drive or accelerate to trigger safety tests.
- Unknown/unsupported OBD PID and delayed network reads MUST abort safely rather than marking car stationary.

Rollback: install verified build 224 (LAB separately installable); main untouched. This PR remains Draft until VXSCAN and Android lifecycle validation.
