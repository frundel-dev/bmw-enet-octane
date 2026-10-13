# BMW OCTANE — RAM LAB 6: direct RAM vs OBD throughput

## Goal

RAM LAB 4 and RAM LAB 5 verified direct UDS ReadMemoryByAddress `0x23` at the same known RPM location. RAM LAB 5's dynamic DID creation via `0x2C` was rejected with NRC `0x31` and caused no acknowledged DDDI. This experiment therefore measures **bounded direct 0x23 performance only**.

The experiment is intentionally based on `experiment/ram-lab4-one-shot-live-rpm`, **not** on RAM LAB 5. The UI and benchmark never send a `0x2C` command.

## Verified source and vehicle scope

The known live-tested reading is `Nkw`, `SWORD`, little-endian, 1 RPM per count at ECU RAM address `0x60003B64`, width 2 bytes. For the exact MG Flasher A2L `R4C9J797B`, SWFL `00005D55-070.060.007`, published A2L `R4C9J797B_5d55-070_060_007.a2l`, blob `d04645de63325a9db7ea1856088d657fb798551f`.

- Exact RAM request: `23 14 60 00 3B 64 02`; valid response `63 <two bytes>`.
- OBD RPM request: `01 0C`, positive data `41 0C <two bytes>` with BMW-supported gateway prefix allowance.
- Vehicle speed safety gate: `01 0D` positive `41 0D 00`.
- **No raw/user-supplied address. No memory range scanning.**

The app cannot automatically verify the DME's firmware using the previously rejected F18x DIDs. Before each test, the user must manually verify the same A2L/SWFL remains installed. Do not run on other vehicles or after a flash.

## Procedure and caps

The test uses the existing `DmeHistoryReader` one-owner ENET/VXSCAN socket with HSFZ framing, read timeouts, request-response matching and cancellation. A separate TCP reader is forbidden while a logger runs. Stop all other apps using this gateway.

1. Park in **P**, engine running at steady idle. Do not use this while driving.
2. Confirm speed **0 km/h** via OBD and initial RPM **450–1800**.
3. Perform **four rounds**, each containing a **four-sample direct RAM 0x23 block** and a **four-sample OBD 01 0C block**.
4. Alternate block order: RAM→OBD, OBD→RAM, RAM→OBD, OBD→RAM.
5. Check the vehicle speed before **every round** and at the end, requiring exactly **0 km/h**.
6. Leave **at least 150 ms** between reads **within each block**, even if DME responds faster. This pacing is deliberately conservative to avoid flooding ECU.
7. Reject missing/malformed/negative responses and implausible idle RPM or drift beyond **±150 RPM** from the initial baseline; abort at the first failure. No retries or session/security changes.
8. Read final RPM and cross-check against baseline. Total benchmark window **35 seconds** after connecting (checked before requests; a final in-flight read can exceed it by its transport timeout).
9. Save each request/reply as CSV with elapsed request-to-response milliseconds and decoded RPM. Save each block's **observed Hz including pacing** and summary medians and nearest-rank p95. On failure/cancel, save a partial CSV.

`SAMPLES_PER_BLOCK=4`, `ROUNDS=4`, thus up to **16 RAM requests** and **16 OBD RPM requests** plus bounded speed/baseline checks. Do not claim a 10–20 Hz logging rate from this test.

## What measurements mean

- Latency is measured around `DmeHistoryReader.transact` excluding CSV write time, not as pure ECU processing time.
- Block observed Hz is `4 / (last response time - first request time)`, including the **three intentional 150 ms gaps**; it is the **actual deliberately-paced rate**, not the absolute maximum possible ECU rate.
- Report median/p95 of 16 individual RAM/OBD transactions and average of the observed four-block rates, with all raw rows available.
- The test lasts seconds; it **cannot validate sustained hours-long RAM logging, signal freshness, consistency for dynamic loads, synchronization between multiple variables, or fuel quality**.

## Installation and export

1. Install the isolated `com.example.bmwenettest.ramlab6` APK, leave stable BMW OCTANE installed.
2. Close the regular logger and MG Flasher. Keep the vehicle safely parked and idling.
3. Open menu **⋮ → RAM LAB 6 • скорость прямого RAM 0x23 → ПО совпадает • Начать**.
4. Wait for completion and **Сохранить CSV**; upload `bmw_ram_direct_v6_<timestamp>.csv`.
5. If the experiment stops or shows an unexpected NRC, **do not repeat until the partial CSV has been reviewed**.

## Safety, QA and rollback

No `0x2C`, `0x10`, `0x27`, write, routine, flashing, coding or reset. Single owned socket, no simultaneous reads. Both RAM and OBD are read-only. APK is separate from main and RAM LAB 4. No main branch edits.

CI: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. Protocol JVM tests check fixed requests, response lengths, alternating order, caps, idle guard and throughput formula. Live on-car behavior is not asserted before a real CSV.

Rollback: uninstall the independent RAM LAB 6 package.
