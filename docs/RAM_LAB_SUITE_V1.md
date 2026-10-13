# BMW OCTANE — RAM LAB Suite 1.0

## Purpose

Six reproducible, sequential RAM experiments in ONE separately installed APK.
This is diagnostic instrumentation research to decide which *validated*
signals can improve a **relative BMW OCTANE fuel quality** model. Neither
experimental RPM nor ignition-retard readings measure fuel RON in a laboratory.

Base: `experiment/ram-lab6-direct-ram-benchmark` (PR #20). Original
BMW OCTANE `main` untouched. Separate Android app ID:
`com.example.bmwenettest.ramsuite`.

## Source and source limitations

The only **already measured and validated** RAM signal is RPM:
`Nkw`, `0x60003B64`, SWORD, little-endian, 1 rpm/count.
On-car LAB 4 and LAB 6 have real positive UDS `0x63` replies matching OBD.

**Candidate new signal** (not yet validated on ECU):
`Dzw_tot_kr`, "Total retard angle from best ignition angle",
address `0x60003618`, SWORD little-endian, scale 0.1°/count
(A2L `BMW_q0p1_deg`, `COEFFS 0 10 0 0 0 1`).
This is NOT per-cylinder knock retard, NOT measured fuel quality, and
its contents must be verified against independent data.

MG Flasher source for this exact vehicle:
`mgflasher-team/mgflasher-map-packs`,
`A2Ls/B48 B58 Gen 2/Generic/R4C9J797B_5d55-070_060_007.a2l`;
source Git blob `d04645de63325a9db7ea1856088d657fb798551f`.
Car screenshots: A2L `R4C9J797B`, SWFL `00005D55-070.060.007`,
DME Bosch MG1CS201. **Before any test manually reconfirm those IDs
have not changed**, because F18x firmware identification failed on
this target DME. The A2L internal project name is `R4C2J797B` —
a known naming mismatch; successful RAM LAB 4 validates Nkw, NOT the
new Dzw address.

## Tests and dependencies

| No. | Test | Bounded scope | Pass meaning |
|---|---|---|---|
| 7 | Ignition/knock scout | Four single `0x23` reads of Dzw, each paired with standard UDS `22 4A 49` raw response | Positive 2-byte Dzw and conservative decoded range, reference DID present; NOT Dzw signal-semantic validation |
| 8 | RPM responsiveness | Eight alternating-order RAM RPM and OBD RPM pairs at parked idle | Values agree within 100 RPM; no proof of transient/high-load responsiveness |
| 9 | Sequential signals | Six sequential pairs Nkw then Dzw via `0x23` | Both signals technically readable, not time-synchronized |
| 10 | Reconnection | Two safe TCP connect-read-close cycles, 800 ms gap | Graceful reconnection works, not sudden cable-loss recovery |
| 11 | Repeatability | Two passes, six Nkw+Dzw pairs each, speed/RPM gates between passes | Readouts repeat without protocol errors; check actual variability using CSV |
| 12 | Soak | 90 Nkw+Dzw pairs, approximately one per second, recheck speed/OBD every 10 | Short stationary endurance; NOT indefinite high-frequency logging |

Stage 7 can start after installation. Every subsequent stage is
**disabled until its immediate predecessor has technically PASSED**.
Persistent gate keys include an exact suite version, not arbitrary
manual overrides. Each stage is launched manually; there is no auto-next
and no automatic continuous background testing.

**Important:** an internal technical PASS does not imply scientific
validation of Dzw or approval for the fuel-quality model. Export and
review each CSV before starting the next stage. If new signal data are
implausible, stop further tests pending a fix.

## Rules and safety

- Park safely, selector **P**, engine steady at idle. Do not run any test
  while driving or require revving engine to pass stage 8.
- Close MG Flasher and all other diagnostics, and stop the stable
  BMW OCTANE LOGGER. The app cannot detect a separate app's socket.
- All exchanges use the existing `DmeHistoryReader` connection and
  framed ENET/HSFZ transport with **one socket reader**.
- OBD `01 0D` speed must be exactly 0 km/h; OBD `01 0C`
  engine RPM 450–1800 and stay within ±150 of start.
- No UDS `0x2C`, session control `0x10`, SecurityAccess `0x27`,
  ECU reset, patching, flashing, arbitrary address input, or destructive
  RAM operations. Never retry after an unexpected/malformed response.
- New Dzw probe is **one fixed two-byte address per call**;
  no memory sweeps or unbounded data reads. Existing Nkw also 2 bytes.
- Max time per stage: 20/20/20/35/35/130 seconds plus connection setup.
  RAM LAB 12 has at most 90 cycles; delay to target 1-second intervals.
- On error, cancellation or timeout: close the socket, write a partial
  CSV and leave the dependent stage locked.
- App state persists only *technical completion*. No readiness claim
  or accuracy percentage is derived from merely completing a test.

## Data and analysis

Each stage saves `bmw_ram_suite_<number>_<timestamp>.csv` under the
app's external-file directory. Each row includes monotonic elapsed
time, stage, sample index, request bytes, response bytes, latency,
decoded RPM/retard when valid, connection, and A2L/SWFL tag.
Summary rows carry counts and median/p95 across completed transaction
latencies. **These medians mix OBD/DID/RAM transaction classes:**
analyse subsets by row `stage` before comparing performance.
Raw UDS/HSFZ journal is mandatory for independently confirming success.

For fuel-quality research, compare each Dzw signal with existing
knock/ignition DID behaviour across matched RPM/MAP/IAT and repeated
fuel samples only after establishing its physical semantics.
The Suite alone cannot validate accuracy or causality.

## Testing and rollback

CI runs Gradle JVM unit tests, Android Lint, debug APK build. Unit
tests cover exact RAM allowlist, signed/little-endian A2L decode,
frame rejection, stage dependencies, request caps, and stationary RPM
guards. No CI test touches the vehicle.

Install APK `BMW OCTANE RAM SUITE` alongside stable app.
Menu: **⋮ → RAM LAB Suite 1.0 • тесты 7–12**.
Start stage 7 only, export CSV, review, then manually run 8 and onward
as they unlock. The stable app is unaffected. Uninstall this isolated
package to roll back.
