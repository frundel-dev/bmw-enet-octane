# BMW OCTANE v1.7.28 — single-TCP B+ DME Monitor acceptance

## Behavior and safe fallback

- Default and preferred mode: **B+**, eight UDS 0x22 DIDs for knock and ignition in one strictly checked HSFZ response.
- Available choices: **B+, B, A, AUTO** only. No C/D, second TCP worker, or implicit 2-TCP fallback.
- Startup one-time migration from v1.7.27 B and obsolete C/D to B+. Manual B/A/AUTO selection remains available afterward.
- If eight-DID B+ is unsupported, **BPLUS_FALLBACK** to the known B pattern; if ordinary B batches fail, safe A.
- DME Monitor is *serially interleaved on the SAME TCP socket and SAME polling thread*. It cannot run concurrently with FAST or the parked-only DME-history reader.
- Adaptation DID probes: **0x554A** (earliest ~12s, then no faster than 15s), **0x4A85** (~25s, then 45s), **0x4A9D** (~35s, then 45s). Reads require 4/4 knock and ignition, FAST cycle at most 650ms, moderate RPM/load/MAP, at least 8s since the last monitor request. B and B+ use the monitor. AUTO trials aren't distorted: monitor suspends until AUTO selection completes.
- **0x19 02 08** (confirmed DTC list) is eligible only after 8 minutes and only at idle/stationary speed (speed known <=3 km/h and rpm <=1100). No detailed Freeze Frame reads are automatically scheduled.
- One extra UDS request per qualified FAST gap, 750ms socket timeout. For negative supported NRCs, preserve raw bytes. Any missing/mismatched/truncated reply or 0x78 pending => close primary TCP and reconnect safely, disable Monitor until LOGGER restart, continue FAST where possible. Do not continue blindly on the same socket after a potentially late response.

## Expected files and checks

- `bmw_dme_monitor_v1728_*.csv`: wall clock, elapsed time, read service/DID, request/response raw bytes, status, provisional interpretation, elapsed read duration, age from FAST cycle start, RPM/load/MAP/speed and fast_hz.
- `bmw_poll_benchmark_v1728_*.csv`: v1.7.27 columns retained, followed by `monitor_ok,monitor_errors,monitor_last_ms,monitor_state,monitor_last_id`. Legacy secondary TCP columns should be **zero or blank** in every row.
- `bmw_connection_events.csv`: no SECOND_TCP_* events. On exceptional Monitor response, a single `DME_MONITOR_SUSPENDED` must explain why monitoring stopped.
- Existing `bmw_enet_v1728_...`, `bmw_steady_v1728_...`, `bmw_dme_probe_v1728_...` remain available. The separate **История и адаптации DME** parked-only scanner remains unchanged (requires STOP LOGGER).

## Test plan

1. Cold start after v1.7.27: default is B+. Validate one-time migration. Switch explicitly to B, A and AUTO; switch back to B+.
2. Collect 10–15 minutes in B+; compare with v1.7.27 B+ baseline (~2.6Hz in one test, **not** a guarantee). Record 4/4 knock and ignition, P50/P95 cycle, fastHz, superknock and absence of reconnects.
3. Drive at steady moderate load. Check monitor CSV for distinct DID samples, 8s+ spacing between records and independent timestamps. For high-load acceleration, Monitor MUST NOT issue extra reads.
4. Stop/idling after 8 minutes, ensure confirmed-DTC status list attempt, if the ECU supports it. It may report zero confirmed codes; status bits must be decoded rather than treating incomplete tests as faults.
5. Simulate no response on a test ECU or playback stream: verify suspended Monitor, socket closure and reconnect. Do not force connection errors on a moving vehicle.
6. Confirm no change to version-0.9 stratified survey calibration, quality filters and saved Fuel Score.
7. Archive **all logs**, including `bmw_dme_monitor_v1728_*.csv` and `bmw_connection_events.csv`; compare full FAST sets per wall-second, not just per-cycle percentages.

## Limitations

- Monitor samples are taken **after** the latest FAST sample and have a distinct timestamp; they are not physically simultaneous.
- `0x554A` and other DID meanings are provisional. The monitor does not derive research octane number or modify Fuel Score.
- One TCP prevents races but not contention: one extra read can delay the next FAST iteration. Monitor skips busy/high-load cycles and suspends after uncertain replies.
- The long parked-only DTC history scanner does not run in the background. The monitor polls only **confirmed-DTC status list**, and it may not be supported by the ECU.
- Vehicle/VXSCAN behavior must be confirmed on-car; CI only establishes structural/software regressions.
