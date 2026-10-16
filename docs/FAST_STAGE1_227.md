# BMW OCTANE — FAST Optimization Stage 1, build 227 (experimental)

**Branch:** `feature/fast-phase-order-227`, parent build 226 PR #49.
**Status:** code and JVM/CI gates are independent of car validation. **Do not merge or claim faster real FAST acquisition before on-car evidence.**

## Exact diagnostic topology

The existing single HSFZ/ENET owner performs the existing read-only
OBD/UDS transactions; there is no new SID, DID, second socket or background reader.

Before:
```
01 0C RPM -> 01 04 LOAD -> 01 0B MAP
-> SLOW B+/B budget / A 2s full list
-> fuel confirmation / refuel detector / optional 01 2F
-> 22 4A36 knock status -> original B+/B/A knock/ignition
-> other optional DME reads / scoring / CSV / UI -> FAST Timing.finish
```
After:
```
cycle-boundary fuel session confirmation
-> mandatory safety SLOW preflight ONLY if benchmark guard is missing/stale
-> FAST BEGIN -> 01 0C -> 01 04 -> 01 0B
-> 22 4A36 -> unchanged knock x4 / ignition x4, existing B+ batch
-> FAST END (record signal presence + per-transaction Android host timing)
-> same bounded SLOW pass if no preflight was needed; optional fuel-level read and analytics
-> reporting -> existing serial DME Monitor -> cycle end
```
`FastCriticalSlice.collect` executes all three OBD reads and DME FAST without
SLOW calls between them. The existing three-consecutive-empty-OBD gate remains
immediately after the OBD trio and before the remaining UDS requests.
Session confirmation occurs at the start of the cycle, so a fuel session cannot
change between the three OBD reads and DME knock/ignition.

### Safety exception and limits

- The **mandatory stationary guard** speed OBD `01 0D` and coolant `01 05`
  retain their original deadlines/TTL and the two-requests-per-cycle
  limit in `SlowPollScheduler.dueForBenchmark`, even if CORE optional budget
  is zero. At benchmark start, stage reset or after guard expiry/failure,
  the entire scheduled SLOW pass runs **before** FAST, then is NOT repeated.
  Otherwise the normal scheduled SLOW pass runs immediately after FAST.
  This explicit exception avoids starting an unguarded stationary benchmark
  on missing/stale signals; the benchmark still fails closed on
  missing/stale/moving/hot readings and must remain parked.
- Safety preflight shares the **same** existing read-only scheduler and socket;
  no duplicate requests, no relaxed TTL and no separate poller.
- B+/B/A, packet format, HSFZ verification and socket ownership are unchanged.
- Historical Fuel Score, fuel session storage, primary/legacy CSV schemas,
  existing FAST Timing v1 and wire_v2 formats are unchanged.
- No inference of ECU-internal acquisition times or laboratory fuel grade.

## Timing and provenance

The existing LAB `bmw_fast_tx_timing_v1_*.csv`,
`bmw_fast_signal_timing_v1_*.csv` and `bmw_fast_cycle_timing_v1_*.csv`
record actual transaction boundaries, 11 expected signals, complete_count,
host-side skew, age and VALID/INVALID/STALE/MISSING. As of build 227
`finish()` is called right at FAST END (previously after analytics/UI),
so FAST age is no longer inflated by unrelated SLOW work.

`FastCycleSnapshot` stores **immutable decoded scalars** with
trip_id, fuel_session_id, connection_epoch, cycle_id and the FAST boundary.
No mutable socket buffers survive; no missing signal becomes 0.
Join the corresponding per-DID host timestamps from FAST Timing (by IDs/epoch/cycle).
The ECU timestamp is unknown.

A separate **LAB-only** `bmw_fast_phase_timing_v1_*.csv` is added. Each
completed owner-thread cycle records monotonic timestamps for FAST begin,
OBD trio complete, FAST END, SLOW begin/end, first UI FAST publication,
optional DME Monitor begin/end, previous/current cycle end and a full-11 flag.
Durations and inter-cycle idle gap are calculated only from nondecreasing
host timestamps; unknown values stay blank. Its bounded asynchronous audit
queue reports rejected rows via the existing CSV-lost counter. The OCTANE
flavor does not include the LAB-only writer.

An exception before full completion is represented by the existing
wire_v2/FAST aborted trace, not by a fabricated completed phase row.
The wire evidence for ACK, missing DATA, phase, PID/DID and IOException
remains unchanged.

## Short parked A/B test (no repeat of six-stage suite)

This release deliberately does **not** add a new user control or automate
a new six-stage benchmark. Use two normal LAB LOGGER runs, avoiding
riskier on-road load maneuvers:

1. A: On build **226 LAB**, stationary warmed engine, B+ / CORE profile,
   stable VXSCAN network. Log two minutes, STOP and export all raw
   FAST timing and wire files.
2. B: Install build **227 LAB** normally without clearing application data.
   Same engine, adapter, selected fuel session, RPM idle range, B+ / CORE,
   two minutes, STOP and export raw files including fast phase timing v1.
3. Optional A repeat requires an approved non-destructive way to run the
   old code version without Android downgrade/data loss. **Do not uninstall
   LAB or delete session data** just to manufacture an A/B/A result.

Do **not** run a six-stage benchmark solely for this comparison. A/B
across sequential installs is **not randomized A/B/A**. Time since startup,
IAT/ECT, DME background behavior and Android scheduling can confound
changes. If a verified repeat A is unavailable, report two-run comparison
with explicit limited confidence.

For each 2-minute window, compare all **complete 11/11 FAST sets**:
sets/s (not PID requests/s), fraction complete, skew p50/p95/p99,
FAST duration p50/p95/p99, full cycle duration p50/p95/p99,
inter-set gap, per-DID age, ACK vs DATA latency, guard signal success/age,
reconnect/fallback and CSV lost. A lower median alone is not sufficient
if tail latency, completeness or guard freshness worsens.

Prior measurements supplied with the task (2.2–2.65 complete sets/s,
host skew median 308ms, p95 513ms, p99 635ms) are **reported baseline**,
not a re-analysis of original CSV in this PR. Expected improvement is unknown.

## Revert

Revert the build-227 branch/PR to parent commit
`396dfd8e797b5f321ee90cc4b992bc4d2796c6f8`; no data migration is
required and existing original application IDs and fuel CSV layouts remain
unchanged. Keep PR as Draft until CI and on-car results justify otherwise.
