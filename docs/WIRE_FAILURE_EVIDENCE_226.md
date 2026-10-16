# BMW OCTANE 2.0 — wire evidence on diagnostic response loss (build 226)

Base: build 225 PR #49, verified SHA `3b665d764d12f5184626e9ef18347fa0c38b4e00`. Research-only LAB observability enhancement with no added ECU commands or TCP readers.

## Failure and transaction evidence

The old logger `EnetLoggerService.measuredDiagnostic()` recorded a failed transaction in memory but discarded all wire evidence on IOException. Build 226 records and **enqueues the full interrupted wire trace BEFORE aborting/reconnecting**. Both the regular FAST DME requests and the low-priority post-FAST DME Monitor read use the sole serialized polling socket.

* The same HSFZ framing/strict ACK and DATA correlation applies. No speculative late response may be attributed to a later request; IO failure stops the benchmark and closes/invalidates the existing socket.
* `HsfzCodec.Phase` explicitly distinguishes `WAIT_ACK_OR_DATA`, `ACK_BODY`, `ACK_VERIFIED`, `WAIT_DATA`, `DATA_HEADER`, `DATA_BODY`, `DATA_COMPLETE` and decode states. Observed phases/timestamps belong to the **Android host**, not ECU execution.
* Loss after ACK but before DATA leaves `ack_verified_ns` populated and DATA timestamps blank. Partial DATA produces `DATA_BODY`, the received DATA-header first-byte time, and blank `data_complete_ns`.
* For every failed operation the wire row retains channel, full requested PID/DID list (group reads separated by `|`), request service SID, receive phase and its monotonic timestamp, actual request send start/end where known, ACK/DATA timestamps if observed, failure monotonic timestamp, exception class, reversibly CSV-escaped exception message and `unfinished=true` if the diagnostic transaction did not successfully decode (including missing DATA or a decode error). Unknown values are blank rather than zeros.
* Failed records take priority over old valid ones if the fixed 128-entry buffer overflows. Failure trace enqueuing uses `AsyncCsvWriter.append()` (backpressure and eventual drain) rather than best-effort `offerAudit()`; asynchronous storage failure is reported, not falsely claimed as durable success.
* To preserve the meaning of archived wire v1 files, new output is `bmw_dme_benchmark_suite_v1_<timestamp>_wire_v2.csv` (schema `wire_schema=2`), with all 19 original columns retained in order and nine new fields at the end.

## Regression tests

`DiagnosticWireTraceTest` simulates:
1. Verified ACK and complete loss of the subsequent diagnostic DATA response (EOF) for OBD PID `0x0D`. Asserts exact `SID=0x01`, PID, `WAIT_DATA`, message, incomplete=true, ACK exists and DATA is blank.
2. Truncated UDS DATA frame for DID `0x4A37` (`SID=0x22`). Asserts `DATA_BODY`, DATA header exists, DATA complete is blank.
3. `SocketTimeoutException` after ACK and the IOException wrapper/cause.
4. Correct CSV escaping of exact exception messages (commas/quotes/newlines) and failed-record retention when trace memory reaches capacity.

Existing protocol, fuel-scoring and other CSV formats remain unchanged. No new requests to fill gaps, no ECU writes, no coding/reset/adaptation changes.

## Acceptance

CI: `:core:data:test`, `:core:diagnostics:test`, `:feature:analysis:test`, `:feature:benchmark:test`, both app variant JVM tests, lint, both debug APKs, LAB-only dependency check. Physical VXSCAN packet-loss or network outage is **not verified** by JVM tests. Safely simulate response loss without driving: disconnect the ENET link or power down VXSCAN while parked, then STOP and inspect the new `_wire_v2.csv` and `BENCHMARK_ABORT`. Do not deliberately trigger malfunction by driving or commanding unsafe ECU modes.

Rollback: build 225; no CSV migration or change to existing app IDs. PR remains Draft until hardware validation.
