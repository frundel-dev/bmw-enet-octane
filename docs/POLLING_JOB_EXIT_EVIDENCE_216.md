# BMW OCTANE 2.0 — build 216: exact pollingJob exit evidence

Parent: build 215 / PR #38 at `a53d0f8cadcead148d7f96702b756927da12459d`. This PR is **diagnostics only**; it must not automatically restart a dead Service, alter HSFZ request order, or change existing CSV schemas or fuel model.

## Evidence and reason for change
In the 15 October Core test (archive 007 + full Android Bug Report), Samsung Freecess froze the OCTANE process while backgrounded and disabled its WakeLock. After thawing, LOGGER's `pollingJob` was already inactive and Service destroyed, but the existing connection-events log contained no STOP request or exception reason. Outer `catch(Exception)` in `EnetLoggerService.startLogger()` contained exceptions, so `Job.invokeOnCompletion` alone could report a **null cause** even for an error.

## What this build records
All fields are appended to the **existing `bmw_connection_events.csv`** using the existing synchronized `connectionEvent` writer. No CSV header or columns change, and previous CSV are not migrated.

- `POLL_JOB_LAUNCH`: session run timestamp and current high-speed profile (Core/Extended/etc).
- `POLL_JOB_CAUGHT_EXCEPTION`: original exception class, message, top stack frame, immediate cause, and profile, logged **before** the existing fault-containment UI call; full stack trace also to Android Logcat under `BMW_OCTANE_POLL`.
- `POLL_JOB_CANCELLED`: cancellation exception, class and explicit STOP/SERVICE origin; cancellation continues to propagate.
- `POLL_JOB_FATAL`: caught Throwable that is not an Exception; recorded and rethrown rather than masked.
- `POLL_JOB_EXIT`: outcome before coroutine stops, whether normal/exception/cancellation/fatal, current profile, watchdog phase and age since fresh data, with one run ID.
- `POLL_JOB_COMPLETION`: actual Job cancellation/completion and its `cause`, plus the separately captured outcome. An externally cancelled Job before the coroutine body runs is labelled `COMPLETION_WITHOUT_FINALLY`; this explicitly differs from ordinary successful exit.
- `LOGGER_STOP_REQUEST`: explicit STOP request annotated with run ID and Job state.
- `LOGGER_SERVICE_DESTROY`: service teardown, recorded before any cancellation, including origin, run ID and Job state.

Classification: `CANCELLED_BY_STOP`, `CANCELLED_BY_SERVICE_DESTROY`, `UNEXPECTED_CANCELLATION`, `CAUGHT_EXCEPTION`, `UNHANDLED_THROWABLE`, `UNEXPECTED_NORMAL_RETURN`, `NORMAL_AFTER_STOP`, and `COMPLETION_WITHOUT_FINALLY`. A swallowed exception is never treated as clean completion merely because `Job.invokeOnCompletion` has a null Throwable.

## Constraints
- Exact exception information is in Logcat; CSV records a bounded summary. No BSSID, passwords, VIN, or credentials added to telemetry.
- `System.currentTimeMillis` run ID links nearby events; Android system Bug Report can explain a process freeze or kill. If the whole process is killed without calling app code, **no coroutine callback can execute** and the log will simply end. Do not misreport this as normal completion.
- Preserve the existing single-thread socket owner, local-only routing, watchdog thresholds, timeout and shutdown behavior, and read-only DME commands.
- Run JVM unit/regression tests including cancellation/stop/handled errors and CSV-safe exception text, Android lint, assembleDebug; CI doesn't prove Galaxy S25 background behavior.

## On-car acceptance
With Android background settings documented, on a stationary Galaxy S25 reproduce Core and Extended separately, keep LOGGER active and use other apps for 2–5 min. Export connection-events and FAST comparison CSV; capture Android Bug Report immediately if the Service stops. Verify that `POLL_JOB_CAUGHT_EXCEPTION` or `POLL_JOB_EXIT` now explains unexpected stop. Compare Service `STOP_FOREGROUND`, Freecess freeze/thaw and run IDs. If `POLL_JOB_COMPLETION` appears with no `POLL_JOB_EXIT`, investigate cancellation before dispatch; if no completion events exist, investigate process kill/forced stop or storage faults.

Rollback: build 215 (PR #38), reverting this PR; existing CSV remain compatible. No automatic restart in 216.
