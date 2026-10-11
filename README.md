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
## v1.7.28 — Single TCP B+ + background DME Monitor

B+ is now the factory/default polling mode (strict eight-DID mixed batch). Modes C/D and the secondary TCP worker are deleted. The only manual modes are **B+, B, A and AUTO** (AUTO still benchmarks A/B only). On upgrade, an old stored B default is migrated once to B+; a user can manually reselect B afterward. If B+ rejects a combined reply, the logger falls back safely to B, and then A on rejected standard batches; repeated transport failures still force safe A.

While B+/B polling runs, low-priority DME Monitor reads **0x554A every ~15 seconds**, **0x4A85/0x4A9D every ~45 seconds**, only if FAST completed successfully, the cycle is under 650 ms and load/MAP are moderate. The planner staggers DIDs, enforces at least 8 seconds between extra requests and issues a maximum of one read after a complete FAST cycle. The **0x19 0x02 0x08** confirmed-DTC status list is eligible no more than every 8 minutes and only while stationary/idle, never a detailed DTC snapshot. These are eligibility intervals; filtering and ECU delays can make the actual sampling slower.

Monitor requests use the **same socket and same reader** as FAST, serially, with a 750ms receive timeout. A missing, truncated, pending or mismatched reply causes socket closure/reconnect and disables monitoring for the remaining LOGGER session (avoids consuming late ECU replies as FAST). Unsupported NRCs are logged without retry storms. Data are saved to `bmw_dme_monitor_v1728_*.csv`, viewable under **Дополнительно → Фоновый DME Monitor • журнал**. Existing parked-only DME history scan remains available when LOGGER is stopped. The frozen Fuel Score model and calibration are unchanged; adaptation interpretation is experimental.
