# BMW OCTANE 2.0 — FAST Timing & Freshness Measurement, Stage A/B (build 219)

**Base:** build 218 / PR #41 SHA `bc99163e4a88cdb6c4019f9cff39f9e7a9fe0b97`.
**Scope:** measurement-only until transport reliability + ECU signal semantics verified. Does NOT increase polling frequency, change ANY ECU requests, DME protocol, fuel scoring or original CSV schemas. Independent Draft PR.

## Avoid duplicate tools
- Build 205+/Stage A PR #27 `HighSpeedMetrics` already records per-channel setup/send/first-byte/receive/decode p50/p95/p99, requests/success/error & unchanged raw fingerprint counts. Response byte changes **are not ECU sample frequencies**.
- `HighSpeedBenchmark` records transport stages and queue depth/loss/p95, `bmw_poll_benchmark_v1732_*` profiles cycle stages, `Octane2CycleCsv` has per-cycle gap and latency and `DriveObservations` records coarse SLOW freshness.
- Build 217/PR #40 adds bounded last-resort SocketException recovery; archive 010 demonstrates one restored Core TCP session + real FAST measurements after 146.6s gap. It **does not** validate always-on One UI background collection. Do not change reconnect architecture until repeated tests.

## New read-only instrumentation
The sole existing `measuredDiagnostic()` socket owner supplies *already available* monotonic `System.nanoTime()` timestamps, including `request_start_ns`, `send_start_ns` (after request encode), `send_end_ns` (after write+flush), `first_byte_ns` (first successful socket read), `response_complete_ns` (HSFZ receiver return), and `decode_end_ns` (result parse). The cycle boundary gives `include_ns`: time the decoded values were assembled for a finished measurement set. Nonresponses have blank/invalid timestamps, NEVER invented zero.

Additional best-effort research CSVs per `trip_id`:
- `bmw_fast_tx_timing_v1_<trip>.csv` — one row per actual successful/failed diagnostic call, channel and member PID/DIDs, transaction sequence, same-cycle epoch and time bounds. Multi-DID response identity is assigned to a *group*; this does **not** imply that the ECU measured all group elements simultaneously.
- `bmw_fast_signal_timing_v1_<trip>.csv` — 11 mandatory FAST signals: OBD RPM/LOAD/MAP, 4 knock candidates and 4 ignition angles; per signal status VALID (response from this cycle + successful decoded value), INVALID (diagnostic or semantic shape check failed), STALE (only prior response in the same epoch), MISSING (no usable prior/current result); source tx, acquisition window and age at inclusion; `reused_previous_cycle=false` because existing core FAST path does not use prior-cycle values. STALE is **reported** and not substituted into the model. Same-epoch only, old epoch resets on reconnect.
- `bmw_fast_cycle_timing_v1_<trip>.csv` — 11/11 completeness, OBD/knock/ignition group completeness, host request-to-last-response window, oldest value age at inclusion, host response-completion skew, explicit `aborted` rows at failure and after STOP.

The offline instrument uses the existing bounded `AsyncCsvWriter.offerAudit` (not an extra writer or socket reader); a group of rows is offered per cycle, never blocking FAST. If the research queue is full, samples may be lost; its `csv_lost` count remains observable in the preexisting `bmw_highspeed_benchmark_v1` CSV. Existing primary measurement writer remains lossless via backpressure.

### Interpretation limits
- **Clock:** these ns timestamps are monotonic host times, not UTC or ECU internal sensor sampling times. Only `wall_time_ms` can correlate with Android event logs.
- **B+:** 8 DID values share a transport response boundary, **not a proven ECU acquisition epoch**.
- **Age / skew:** host observations constrain receipt and inclusion times, NOT exact ECU event times.
- STEADY `750ms` and DYNAMIC `150ms` thresholds are explicitly **research heuristics**, not validated thresholds for B48. These fields NEVER gate Fuel Score; empirical validation against drive regimes and temperature effects is required.
- Profile and poll mode are both recorded separately; B+ is the batch read strategy, not one of LEGACY/CORE/EXTENDED.
- `trip_id`, fuel session and reconnect epoch are preserved. In build 217 emergency outer retry, a new `loop()` produces a new trip and CSV set, never a falsely continuous sample.
- New RAW DIDs remain locked out; no UDS 0x2C, unknown RAM, ECU writes or concurrency.

## Performance question and known limit
Historical archives 009/010 show only ~2–2.6 completed cycles/s on idle tests with 11/11 recorded channel values, not 20–50 Hz. Without new host-stage data one cannot attribute the limitation to DME, TCP, Kotlin, HSFZ framing, network scheduling, optional slow reads or CSV flush. **No transport or scheduler optimisation is authorized by these numbers alone.** HighSpeedMetrics + new time trace will disaggregate actual latency and coverage from per-ECU-response freshness.

## Regression and CI
New unit tests cover 11/11 vs partial FAST sets, altered PID/DID latency, stale RPM/MAP despite fresh knock, B+ grouping, oversized time skew, aborted cycle, epoch reset, transaction order and research queue failure containment. Existing HSFZ late-frame, timeout, cancel, CSV overflow and profile fallback tests remain unchanged and are run as part of the same JVM suite. Android Lint and debug APK are mandatory.
**Not CI verified:** actual low-level HSFZ time split between network/ECU, Galaxy S25 Freecess, ECU internal timestamps, effects of changing poll schedule, correctness of knock semantics, or any vehicle-specific p95/p99.

## Minimal safe on-car comparison
1. With parked warmed engine, on **VXSCAN Wi-Fi** select one high-speed profile, start LOGGER 2–3 min, STOP; repeat separately for LEGACY, CORE, EXTENDED, same B+ setting and engine conditions. Do not change profile during a session if avoidable.
2. Leave Core running, switch to another app for 2–3 min, return and confirm new measurements; export all CSV once. Do not conflate Freecess interruption with low FAST Hz of active periods.
3. Repeat USB Ethernet separately on a different parked session, identical profile and B+ setting; no concurrent ENET and VXSCAN.
4. After a stable parked test, only if convenient record a safe routine drive (no forced acceleration or knock provocation). Check completeness and host-bound estimates separately for STEADY and dynamic.
5. From CSV calculate per-transport/profile/mode median & p95/p99 transaction stages, rate of *complete* 11-channel sets, host age/skew, queue loss, HSFZ error & reconnect rate. Do not count requests/s as measured signal Hz.
6. Only after ranking bottlenecks and ensuring no new data loss, propose a **separate targeted optimisation PR** (Stage C/D). Compare before vs after under comparable conditions and preserve rollback to stable 218.

**Rollback:** reinstall build 218, or revert only this PR; original CSV, saved refuel sessions and Fuel Score remain unchanged.
