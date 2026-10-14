# P0 ART crash isolation — experimental build 209

## Evidence
Source: Android bugreport `bugreport-pa1qzcx-BP4A.251205.006-2026-10-14-23-01-37.zip` supplied 14 October 2026. Repeated native tombstones 22:58:43–23:01:14 (+03:00) show SIGSEGV in `art::interpreter::ExecuteSwitchImplCpp` with `EnetLoggerService.loop` on the diagnostic pool thread; other tombstones show ART SIGABRT / "Unexpected instruction" and "divide by zero with unexpected pending exception: java.io.IOException: HSFZ ACK not correlated with request". This is evidence of a critical native crash while executing the logger; it does not prove a particular Kotlin optimization is the sole root cause.

## Verified code defect fixed
Build 208 `readDmeMonitor` called HSFZ `readFrames(input)` without passing `expectedRequest` or replying to alive-check. Because the build 208 decoder rejects uncorrelated ACKs, a valid ACK preceding a monitor response could trigger a socket fault. Build 209 reuses the sent packet for correlation and sends 0x13 alive reply on the SAME socket.

## Conservative structural mitigation
The `loop()` coroutine was so large that an earlier in-loop catch triggered a compiler `MethodTooLargeException`. Reorganized two self-contained pieces out of that method: primary ENET network discovery/cached connection selection, and DME Monitor transaction handling and logging. The primary Service remains the only socket reader. Backoff/reconnect, B+/B/A, RAM 0x23, CSV schemas, Fuel Score, refueling and read-only diagnostics are otherwise unchanged.

## Regression coverage
Pure JVM HSFZ monitor sequences: matching ACK -> DID answer, foreign ACK rejected, ACK-only without diagnostic response rejected. Existing JVM tests cover fragmentation, alive, NACK and RAM. GitHub Actions CI must pass on the final SHA before this build is tested.

## Remaining
No assurance of avoiding native ART SIGSEGV solely from Kotlin method extraction, nor proof of behavior on the affected Android build without repeating the same stationary-car test. Before accepting: 30 switches, 30 min recording, controlled disconnect and STOP/START, inspect new bugreport/tombstones, compare CSV and sampling rates with 208. Keep separate from PR #31; do not merge into main while hardware crashes persist.

Rollback to build 208 (or last car-validated version), without deleting generated CSV.
