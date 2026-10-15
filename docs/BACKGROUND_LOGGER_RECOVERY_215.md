# BMW OCTANE 2.0 — P0 Background Recovery & LOGGER Health (build 215)

**Parent:** experimental build 214 / PR #37, head SHA `03cd4375a8b19f9a6077571cc3561693e992f120`. This is an independent dependent PR; main unchanged.

## Primary evidence
Archives 15.10.003–006 and the 14:37 screenshot show a LOGGER that can remain nominally active while DME comparison rows stop for tens of seconds/minutes. In 006, last useful DME comparison precedes the screenshot by roughly 2.5 minutes with no recorded TCP DISCONNECTED/RECOVERED; network monitor stopped near 14:36:54. A TCP CONNECTED event in 214 could show "DME connected" despite no fresh DME response. The exact Android/transport cause was not established.

## Safe targeted correction
- `LoggerHealthPolicy` tests using monotonic elapsed time: a cycle's POLLING and POST_CYCLE phases get independent timestamps; 8s stall gives passive `LOGGER_HEALTH_STALL`; 18s stall gives one `LOGGER_HEALTH_RECOVERY`. Never fire during networking/discovery/STOP. Boundaries are conservative thresholds for experimental validation, not evidence that the engine responded.
- An independent `Dispatchers.Default` watchdog in the existing foreground Service only watches immutable volatile health snapshots. It never requests UDS, writes ECU, reads the HSFZ socket, creates a second TCP connection or launches another polling loop. On confirmed stall, it may **close only the still-current TCP socket** after identity and Service-generation checks; the sole existing owner subsequently handles SocketException, reconnect and state resets. If the polling thread itself is frozen outside socket I/O, the watchdog **logs** the condition but cannot force a coroutine restart, preserving the single-owner and CSV integrity constraints.
- Keep existing CSV schemas unchanged. Passively write stall/recovery phase diagnostics into the existing connection-event CSV; log explicit `LOGGER_STOP_REQUEST` and `LOGGER_SERVICE_DESTROY` to help distinguish STOP from a frozen loop or abrupt process death. Keep all existing comparison, observations, benchmark, and DME Monitor writers untouched.
- Track `lastFreshDmeAtMs` within the Service only when a completed cycle provides both RPM and MAP. UI checks this cross-thread monotonic timestamp; Wi-Fi READY is explicitly independent. On stale completed data, header shows "DME: нет свежих данных" and "LOGGER: нет данных" instead of "сбор". A TCP CONNECTED event alone now leaves DME in CONNECTING until an actual valid FAST telemetry update.
- When the UI is foregrounded, its small 2s header tick checks freshness without new ECU queries; onPause disables this tick. On resuming, an explicitly saved local-only profile in LOST/UNAVAILABLE/ERROR is re-requested; a currently READY/REQUESTING/WAITING_IP network is never replaced.

## Safety and limitations
1. Only read-only diagnostic requests are made by the unchanged existing poll coroutine. The recovery watchdog never sends a request.
2. No concurrent socket readers. An existing TCP socket may be closed from watchdog, which is a safe interrupt attempt, but actual non-I/O stalls may remain; these will be visible in phase logging.
3. Timeout is **one socket closure per stalled immutable snapshot**. If a new cycle completes, the snapshot changes, and the timeout resets. Stale data must never become zeros or count as a valid fuel sample.
4. Android process kill without callbacks cannot be diagnosed by CSV alone. Existing START_NOT_STICKY and no automatic logger restart policy remain, avoiding surprise ECU polling after app process recreation.
5. Device/One UI and VXSCAN validation is necessary; CI cannot confirm background task scheduling, radio power management, socket-close cancellation, or actual restored measurement rate.

## CI and on-device acceptance
- JVM: phases / 8s and 18s thresholds / reconnect freshness reset, invalid PID response, Wi-Fi phase recovery, stale vs fresh header independence.
- Android Lint and debug APK build on final commit.
- On parked Galaxy S25, record 15 minutes of running LOGGER, repeatedly switch to Telegram/browser and return, including 2+ minutes background. Capture screenshot, CSV and, if possible, Android bugreport.
- Verify independent events `LOGGER_HEALTH_STALL`, `LOGGER_HEALTH_RECOVERY`, `DISCONNECTED`, `RECOVERED`, `LOGGER_STOP_REQUEST`, `LOGGER_SERVICE_DESTROY` where appropriate. Inspect `cycle_gap_ms`, raw DME rows, FAST Hz, no CSV loss, no duplicate sessions, and no new crashes. If Android forcibly kills the process, STOP_NOT_STICKY behavior and user-visible stopping must be reported separately.
- Re-test STOP/START, local-only auto re-request, cellular Internet while using VXSCAN.
- **No claim of fixed background continuity** until these device logs pass.

Rollback to experimental build 214 PR #37; no user files or old CSV migrated.
