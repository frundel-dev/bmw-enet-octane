# Build 214 — Compact permanent Wi-Fi readiness header

Based on build 213 / PR #36, without altering Service, ENET/HSFZ, WLAN reservation, FAST polling, Fuel Score, or CSV.

## Agreed layout
Row 1: BMW OCTANE 2.0 and v2.0.1 LAB.
Row 2: left `B48 · USB ENET/VXSCAN` (G20 omitted), right **concise** `Wi-Fi: ...`.
Row 3: existing independent DME status on left and LOGGER status on right.

All rows are outside scrollable tabs and visible across the application. Wi-Fi label is clickable and navigates to existing Settings tab. The detailed local-only status and network transport continue to be available there.

## Evidence and semantics
The actual `VxscanLocalOnlyWifi.Phase` from the existing Android NetworkCallback drives the status in the same receiver used by Settings, with an immediate snapshot on view creation/onResume. OFF = switched off, REQUESTING = connecting, WAITING_IP = awaiting IPv4, READY = Wi-Fi local-only reservation available, LOST/UNAVAILABLE/ERROR = unavailable/error. Distinct colors and textual labels; accessibility description points to settings.

READY is **not** a claim of confirmed DME replies, active LOGGER, fresh RPM/MAP, or mobile Internet. These statuses remain independent.

## Regression and validation
Added `VxscanHeaderStatusTest`: phase-by-phase labels, semantics and independence from DME/LOGGER. Existing `Octane2HeaderStatusTest` unchanged.

CI JVM unit tests, lint and `assembleDebug` must pass on the final SHA; do not claim hardware validation before real Galaxy S25 screenshot/test. Manually check narrow screen truncation, tap-to-Settings, real READY and LOST/RECONNECT, DME/LOGGER independence. No background ECU read or new network request is issued by the header.

Rollback: return to build 213 or revert PR #37 independently; user's CSV remains unchanged.
