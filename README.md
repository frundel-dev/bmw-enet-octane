# BMW OCTANE v1.7.27

Read-only Android/Kotlin transport and DME test for BMW ENET/HSFZ.

- Finds Android Ethernet network.
- BMW HSFZ discovery on UDP 6811.
- Connects TCP 6801.
- Sends UDS ReadDataByIdentifier `22 F1 90` to DME target `0x12` and tries to decode VIN.
- Sends experimental read-only OBD `01 0C` RPM request to DME and displays raw response even if unsupported.
- Does not send coding, flashing, reset, routine-control, security-access, write-data or IO-control services.

Build with Android Studio (compileSdk 35, minSdk 26).

## GitHub Actions APK build

The repository includes `.github/workflows/build-apk.yml`.

It automatically builds the debug APK on pushes to `main`/`master`, pull requests, and manual `workflow_dispatch` runs.

To download the APK on GitHub:
1. Open **Actions** → **Build Android APK**.
2. Open the latest successful run.
3. In **Artifacts**, download **BMW-OCTANE-v1.7.27-debug-apk**.
4. Unzip it to get `app-debug.apk`.

The CI build uses JDK 17, Gradle 8.9, Android SDK 35 and Android Build Tools 35.0.0. No signing secrets are required for this debug APK.

## Automated quality gates (CI)

Every push or pull request now runs the following **before** the debug APK artifact is published:

1. `gradle :app:testDebugUnitTest` — JVM tests, no car/Android emulator required.
2. `gradle :app:lintDebug` — Android Lint (fatal and error findings fail CI).
3. `gradle :app:assembleDebug` — build the APK only after the gates pass.

Tests cover HSFZ stream framing, UDS multi-DID response validation, AUTO A/B mode selection and fallback, read-only DME probe parsing, fuel-refill detection, frozen octane reference boundaries, and STEADY robust statistics.

CI attaches a `bmw-octane-quality-reports` artifact (JUnit XML, HTML tests and Lint reports), even when tests or Lint fail. The APK artifact is not published on a failing run.

Run the same checks locally with JDK 17, Gradle 8.9 and Android SDK 35:

```sh
gradle --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

These are software tests, **not** verification of ENET/VXSCAN compatibility on a real vehicle. v1.7.27 defaults to mode B (single TCP + batched UDS); AUTO only compares A/B and excludes second TCP. Experimental B+ attempts one mixed-width 8-DID batch, reverting to standard B when invalid/unsupported. In B/B+ the single-TCP optional SLOW PID requests are staggered (at most two per FAST loop), with per-PID freshness and detailed benchmark timings. Mode A remains the safe fallback. C/D remain manual experimental modes and are not recommended on VXSCAN. Fuel Score calibration is unchanged. Firmware-specific timing and multi-TCP support still require car-side log validation.
\n## DME history and adaptations (v1.7.27)\n\nFrom **Дополнительно**, choose **Считать историю и адаптации DME** while parked with the live logger stopped. This uses a separate *one-shot* TCP session, never a second simultaneous session. Read-only UDS `0x22` probes adaptation candidates `0x554A`, `0x4A85`, `0x4A9D`; UDS `0x19 0x02` lists supported stored DTCs; up to 12 receive `0x19 0x04/0x06` snapshot/extended record requests with record number `0xFF`. Negative and unsupported responses are kept verbatim; no adaptation resets, clears, writes, security access, or programming. Files are stored as `bmw_dme_history_v1727_*.csv`, viewable from **Сохранённые снимки истории DME**. Adaptation meanings and BMW-specific DTC records are experimental; no history is used in Fuel Score. The ECU may retain no data or refuse optional subfunctions.\n
## v1.7.29 — Single TCP B+ + background DME Monitor

B+ is now the factory/default polling mode (strict eight-DID mixed batch). Modes C/D and the secondary TCP worker are deleted. The only manual modes are **B+, B, A and AUTO** (AUTO still benchmarks A/B only). On upgrade, an old stored B default is migrated once to B+; a user can manually reselect B afterward. If B+ rejects a combined reply, the logger falls back safely to B, and then A on rejected standard batches; repeated transport failures still force safe A.

While B+/B polling runs, low-priority DME Monitor reads **0x554A every ~15 seconds**, **0x4A85/0x4A9D every ~45 seconds**, only if FAST completed successfully, the cycle is under 650 ms and load/MAP are moderate. The planner staggers DIDs, enforces at least 8 seconds between extra requests and issues a maximum of one read after a complete FAST cycle. The **0x19 0x02 0x08** confirmed-DTC status list is eligible no more than every 8 minutes and only while stationary/idle, never a detailed DTC snapshot. These are eligibility intervals; filtering and ECU delays can make the actual sampling slower.

Monitor requests use the **same socket and same reader** as FAST, serially, with a 750ms receive timeout. A missing, truncated, pending or mismatched reply causes socket closure/reconnect and disables monitoring for the remaining LOGGER session (avoids consuming late ECU replies as FAST). Unsupported NRCs are logged without retry storms. Data are saved to `bmw_dme_monitor_v1728_*.csv`, viewable under **Дополнительно → Фоновый DME Monitor • журнал**. Existing parked-only DME history scan remains available when LOGGER is stopped. The frozen Fuel Score model and calibration are unchanged; adaptation interpretation is experimental.

## v1.7.29 — lean production logger

Removes the obsolete repeated DME DID survey (was one extra request every 12s) and A/B timed AUTO poll-mode comparison; the files `bmw_dme_probe_...` and `bmw_poll_auto_...` are no longer generated. **AUTO driving/acceleration capture mode is unchanged.**

B+ (strict one-TCP 8-DID mixed packet) remains default, with B/A as manual fallback options and automatic fallbacks B+→B→A. Periodic extra diagnostic values are exclusively handled by the low-priority same-socket DME Monitor. The manual parked-only DME history reader, dynamic timeout/reconnection, safe parser and data completeness checks stay intact. Monitor, measurement, connection event and a compact benchmark CSV are retained. The new `bmw_poll_benchmark_v1729_*.csv` removes deprecated AUTO and secondary TCP placeholder columns; parse by column names, not old fixed offsets.

The Fuel Score calibration, formulas and high-load qualification are unchanged. Check `docs/VEHICLE_VALIDATION_v1.7.29.md` for acceptance testing.

## v1.7.30 — observational baseline learning (unknown fuel)

The new **Дополнительно → Обучение эталона • RPM × нагрузка** view is a voluntary, separate STEADY/ACCEL data collection workflow. It shows two RPM × engine-load matrices, raw accepted sample counts, within-trip segments, separate logger starts, and context-dependent advice on which safe driving regime adds the most new information. Heatmap colours mean **coverage only**, not fuel quality or RON. Open a new named fuel dataset when the LOGGER is stopped, then start the logger in **AUTO measurement mode**. The map refreshes every 4.5 seconds; tapping a cell shows counts and support.

Raw accepted observations are written to independent `bmw_baseline_learning_v1730_<dataset-id>.csv` files. No additional ECU reads occur. Each row contains trip ID, fuel session ID, phase, segment ID, RPM, LOAD, MAP, IAT, coolant, trims, knock mean and ignition spread. STEADY samples are de-correlated at ≥2.5s and ACCEL at ≥1.5s. Unwarmed, incomplete knock/ign, high trims, knock-active, potentially mixed fuel and unknown speed are excluded. After a suspected refill the active dataset is automatically paused; a new set must be started with LOGGER stopped. Old datasets remain available on disk.

When at least one cell is review-ready, **Экспортировать черновую модель (CSV)** writes a separate `bmw_baseline_candidate_v1730_<id>.csv` with an equal-trip-weighted median-of-segment-medians, between-trip MAD and explicit `ron_verified=false`; it is not loaded by Fuel Score.\n\n24 observations from ≥4 distinct segments across ≥2 logger starts are marked **ready for independent evaluation**, NOT verified as reference gasoline. The user must independently validate fuel and reserve separate runs for hold-out verification; **no self-training of live Fuel Score, no change to OctaneCalibration, and no claims of RON.** See `docs/BASELINE_LEARNING_v1.7.30.md`.

## v1.7.31: guided fuel test on the home screen

**Main flow:** tap **НОВАЯ ЗАПРАВКА**, choose the *claimed* fuel grade
(92/95/98/100/other) and optionally the station, then the app launches its
read-only DME logger in AUTO measurement mode. The main card shows accepted
STEADY and ACCEL coverage, current collection percentage and a safe suggestion
about the next RPM × LOAD / MAP region. The test completes automatically
once two STEADY cells and three ACCEL RPM groups have at least four accepted
observations from at least two separate segments each, or the user may end it
early and see "insufficient measurements". A session can be paused or resumed
via the result dialog and resumes across LOGGER restarts. Confirmed detected
refuelling pauses it to prevent fuel mixing.

**Result:** historical immutable AI-95 acceleration references allow only a
**provisional relative knock-signal delta**, with equal weighting of acceleration
segments and RPM bins where supported. The result is **not** absolute octane,
cannot establish true petrol quality and never learns its own reference from
the current unknown gasoline. Absent coverage yields **insufficient data**.
No user-session data modifies `OctaneCalibration.kt` or live Fuel Score.
The home screen keeps existing low-level diagnostics in the overflow menu.
Per-fill CSVs (`bmw_fuel_test_v1731_<id>.csv`) and a read-only local history
preserve previous tests. See `docs/GUIDED_FUEL_TEST_v1.7.31.md`.

Unit tests, lint and debug APK assembly are required. Real-road DME validation
is still needed to confirm practical coverage and slow-value freshness.

## v1.7.32 — compact real-time STEADY research coverage on home screen

The bulky *new refill / fuel evaluation* panel no longer takes permanent home-screen
space. **НОВАЯ ЗАПРАВКА** opens the isolated refill-test screen (or, on first
use, the fuel selection dialog); after choosing fuel the assessment appears
there, together with its RPM×LOAD map and test result. No result is removed.

A minimal **НЕДОСТАЮЩИЕ STEADY • MAP 105–129 кПа** panel stays visible
beneath the start controls, in the normal AUTO logger with **no new fuel-test
mode** required. It shows independent live counters, distinct segments,
different LOGGER starts, provisional cell-completion percentages and
contextual hints for RPM 2500–2999 and 3000–3499. Counts are persisted in
a separate append-only `bmw_steady_targets_v1732_fuel<id>.csv`, restore when
the app or LOGGER restarts, and reset on a different recorded fuel session.
The display is based on accepted read-only FAST/SLOW values: RPM/MAP gates,
STEADY stability, warmed engine, fresh valid 4-cylinder knock/ignition,
normal trims, no knock event, no mixing/refill; after 5 s stable dwell, at
most 1 accepted row per 2.5 s. 24 points, 4 segments and 2 LOGGER starts
form only **preliminary observational coverage**, NOT model validity or a
measured gasoline RON. Existing raw logs and Fuel Score are unchanged.
Older v1.7.31 logs are not silently mixed into this new counter.
