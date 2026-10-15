# BMW OCTANE 2.0 / build 217 — bounded poll coroutine SocketException recovery

Base: build 216 / PR #39 SHA 6ba28fda89e274dd4609b30eb465e6fabaf6e896.

## Field evidence
The Oct 15 archive 008 found a HIGH_SPEED_CORE session in which the 215 watchdog closed a blocked primary socket. A `SocketException: Software caused connection abort` escaped the inner `loop()` reconnect boundary and was caught by the outer job handler. `POLL_JOB_CAUGHT_EXCEPTION` ended the foreground Service rather than retrying. An Extended session showed normal inner TCP reconnect; user STOP could also be wrongly classified as an error.

## Narrow correction
- Keep the original inner sequential HSFZ/UDS reconnect logic unchanged.
- The outer polling coroutine now includes a bounded **last-resort** catch for `java.net.SocketException` escaping `loop()`. A failed `loop()` runs its original non-cancellable CSV close/flush `finally` before next iteration begins. Only then the sole owner may reconnect. No concurrent socket reading or new ECU commands.
- Explicit user STOP and inactive LOGGER block this retry. Max **2 retries in a five-minute monotonic window**. If exhausted or on a different error, existing handled-failure path stops LOGGER and records the exception. This does not repeatedly restart the Android Service.
- Every last-resort retry is documented as `POLL_LOOP_SOCKET_FALLBACK` in the existing connection-events CSV. Note that each new `loop()` creates a new **trip ID and CSV set**; the original files must be preserved, without silently merging segments.
- `POLL_JOB_EXPECTED_STOP_CLOSE` distinguishes an expected socket-close at explicit STOP or Service destruction from an unexpected `POLL_JOB_CAUGHT_EXCEPTION`. Ensure reason `POLL_JOB_EXIT` is consistent.
- `phase_age_ms=-1` (unavailable) for a reset 0-based monotonic phase timestamp; no large fake ages.
- No changes to polling profiles, ECUs, DIDs, CSV headers, Fuel Score, Android background policy, watchdog thresholds or local-only Wi-Fi.

## Verification
JVM regressions: max two within rolling window, cooldown, explicit STOP prevents reconnect, monotonic reset. Existing HSFZ, CSV and lifecycle regressions plus lint and assembleDebug.
On parked Galaxy S25 validate STOP while backgrounded, Core and Extended recovery after 2–5 minutes in other apps, no duplicate poll owner; inspect last-resort events and sample continuity. Samsung Freecess freeze cannot be defeated by coroutine timeout; expect gaps during freeze. Avoid conclusions about independent fuel-quality samples from a single idle trip.

Rollback: build 216 PR #39, without altering persistent CSV.
