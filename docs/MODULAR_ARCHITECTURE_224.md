# BMW OCTANE 2.0 — modular migration audit (build 224)

**Source:** PR #47 experimental build 223 (head `6e169e676a09edc6458b3266f5171fe498debe95`), stacked on PRs #46, #44, #43, #42 and 217/218 safety PRs. Main at migration start: `1146d9957fb795a08b9fd941b74a93715ba7f9b6`. Alternative PR #45 removes old UI entrypoints but does not contain the actual PRG/test cleanup of selected PR #44; **not merged**. No branch/PR was merged, rebased, deleted or reset.

## Five real projects

| Project | Code (non-exhaustive) | Responsibility |
|---|---|---|
| `:core:data` | `AsyncCsvWriter`, `DriveObservation/Journal`, `Octane2CycleCsv`, `FuelRefillDetector`, `FuelSessionKeys`, `SampleFreshness` | Loss-aware storage queue, typed observations, persisted identifiers, existing CSV schemas |
| `:core:diagnostics` | `HsfzCodec`, `HsfzReusableReader`, `DiagnosticResponse`, `DmeBatchParser`, `MixedDidBatch`, `RamMemoryProtocol`, `SlowPollScheduler`, `HighSpeedMetrics`, `LoggerHealthPolicy`, `PollingSocketFallbackPolicy`, DME history/FAST validation, High-Speed operational CSV | Framing, SID/PID/DID matching, response validation, freshness, scheduler and read-only recovery policies |
| `:feature:analysis` | `LegacyFuelReference`, `LegacyFuelScoring`, `OctaneCalibration`, `GuidedFuelTest`, `BaselineLearning`, `SteadyTargets/SteadyCellStats/SteadyLiveCue`, comparison | Frozen relative fuel-score math, STEADY/ACCEL, repeated segments, coverage; **no socket/Service dependency** |
| `:feature:benchmark` | `AutomatedDmeBenchmark`, `DiagnosticWireTrace`, `FastTimingTrace` | LAB-only six-stage test, ACK/DATA host timing, passive FAST trace and report |
| `:app` | `MainActivity`, `EnetLoggerService`, platform network/wifi and lifecycle, `RefuelSessionStore`, OCTANE/LAB variant adapters | Android permissions, one single-owner polling coroutine, UI, Android-backed preference adapter and foreground service |

Dependency arrows: `app → {data, diagnostics, analysis}` plus `app(lab) → benchmark`; `diagnostics → data`, `analysis → data`, `benchmark → {data,diagnostics}`. No module depends back on `app` or on the fuel-analysis module from the diagnostic core.

**Android-backed `RefuelSessionStore` deliberately remains in `:app` as a platform storage adapter;** persisted key and file contracts are in `:core:data`. Moving SharedPreferences into JVM-only `:core:data` would require an Android dependency, breaking offline JVM analytics tests. A future purely data-layer storage port is possible only with verified use cases. This slice does **not** claim every last Service responsibility was extracted; Service still orchestrates its existing transaction/poll, data capture and lifecycle state machine. No rewritten read loop or extra socket has been introduced.

## Product variants and data

- `assembleOctaneDebug` uses unchanged application ID **`com.example.bmwenettest.octane2`** and existing app-private preferences/data, preserving in-place upgrade.
- `assembleLabDebug` uses **`com.example.bmwenettest.octane2.lab`** and a distinct app label. Android intentionally isolates its preferences/files; cross-installation data is transferred explicitly using existing SAF exports.
- `labImplementation(project(":feature:benchmark"))`; production has no dependency on the suite library. Common Service and MainActivity use compact ports with separate LAB and OCTANE factories; production has no compiled LAB benchmark screen, `AutomatedDmeBenchmark`, `DiagnosticWireTrace`, or `FastTimingTrace` implementation.
- All primary logger CSV headers, `Octane2CycleCsv` headers and `FuelSessionKeys` identities retain original byte patterns. Lab CSV names from build 219/223 retain their exact prefixes. Existing user files are not deleted/migrated.

## Behavior preservation

- HSFZ/UDS encoder, decoder, request lists, ACK/NACK, recv fragmentation, reconnect and socket health logic moved without changing diagnostic requests. The same one polling coroutine owns all reads; no Benchmark or analysis layer can open a second socket.
- `LegacyFuelReference` values and `LegacyFuelScoring` formula moved without changing thresholds, clamps, legacy sampling rules or scores. JVM fixture tests lock reference cells, score/confidence math and pending/mixing priority; still observational **not RON/MON**.
- Existing `AsyncCsvWriter`, `DriveObservationJournal` and versioned CSV codecs moved as-is. B+/B/A and high-speed profiles retain existing fallback behavior.
- Original PR #47 had `MethodTooLargeException` in Service suspend `loop`. Benchmark lifecycle, passive FAST CSV, frozen Fuel Score and the large status/CSV constructors have been extracted to limit the coroutine state-machine bytecode without altering polling cadence.

## Validation checklist / status gate

GitHub Actions must successfully run all:
```
:core:data:test
:core:diagnostics:test
:feature:analysis:test
:feature:benchmark:test
:app:testOctaneDebugUnitTest
:app:testLabDebugUnitTest
:app:lintOctaneDebug
:app:lintLabDebug
:app:assembleOctaneDebug
:app:assembleLabDebug
```
Then inspect the CI dependency guard: production runtime classpath must have **no** `project :feature:benchmark`, while LAB must include it.

Acceptance is **NOT achieved** merely by successful Gradle configuration or code movement. Review CI compilation and all unit tests, compare stored CSV fixtures, and test install-over-existing OCTANE and independent LAB installation. Physical B48/DME8FF VXSCAN tests (read-only polling, actual 11/11 complete FAST, host p50/p95/p99, knock/ign completeness, measured throughput, reconnect, deep background and 6-stage suite) remain **not verified** without the vehicle. Do not compare differing road/fuel/thermal conditions as a strict performance A/B.

## Rollback

Do not merge an unverified chain. Reinstall the previous **OCTANE build 222** over the unchanged production app ID to retain user data (same signing key required). LAB has a different app ID and can be uninstalled without deleting OCTANE data. Any source regression can be reverted by closing this single Draft PR and returning to PR #47/#46; no schema migration is needed.
