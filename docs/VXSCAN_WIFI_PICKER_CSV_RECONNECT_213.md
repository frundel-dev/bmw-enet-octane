# BMW OCTANE 2.0 — Wi-Fi search, local-only reconnect and monitor CSV fixes (build 213)

## Evidence and scope
The 15 October 2026 CSV audit of experimental build 212 found:
- Wi-Fi HSFZ frames are routed to the local-only network while Android default network is cellular; user independently confirmed Telegram/browser Internet.
- DME Monitor rows were joined using **literal backslash-n** characters, breaking normal CSV parsing.
- Multiple starts occurred when Android had not yet supplied a valid local-only Wi-Fi Network; STOP/START released the app's WifiNetworkSpecifier callback, and network-unavailable retries were classified as polling interruptions, sometimes forcing fallback A.

This PR is based on exact build 212 / PR #35 SHA f46a1c6d0968e4232d4e89bd4e6a7f07133974ac, **not merged to main**. No ECU command, SID/DID, FAST decode, fuel calibration, fuel score, historical CSV, refuel logic, or HSFZ byte-level framing changed.

## 1. Optional Wi-Fi list in VXSCAN settings
- "Найти Wi-Fi рядом" starts a single, on-demand Wi-Fi scan through WifiManager.startScan/getScanResults; no periodic/background scans.
- Android 10+ targeting API 35 requires separate precise location permission and system location services for Wi-Fi scan results, **even though local-only request needs only NEARBY_WIFI_DEVICES on Android 13+**. The UI explains this and requests permission only after the user taps the button; if denied, manual SSID remains available. The discovery list is never stored nor uploaded.
- Displays at most 30 nearest AP BSSIDs with SSID, BSSID, signal level, frequency and security. No fixed VXSCAN prefix; user chooses exactly one AP; selection populates SSID+BSSID and clears an old password if SSID changed. Selection **does not connect or save** anything by itself. The existing confirmation and AndroidKeystore encrypted credentials are unchanged.
- Android scan throttling may return cached/no results; show this limitation clearly and preserve manual entry.
- WPA3-only, Enterprise etc may be listed but the existing local-only connector supports WPA2/Open; warn for WPA3 and do not silently assume all networks are compatible.

## 2. DME Monitor CSV
- Replace both normal and exception DME Monitor output delimiters with a shared `DmeHistoryProtocol.completeCsvRow` function emitting a physical `\n` byte. Keep all columns, schema, names, and timestamp semantics identical. **Only newly created files** receive the corrected row separation; no rewriting of historic corrupted CSV.
- JVM regressions verify two independent rows are actually two physical records and CSV quoting remains unchanged.

## 3. STOP/START and missing network
- Track whether OCTANE UI is foreground through `VxscanLocalOnlyWifi.setUiVisible`. STOP / Service destruction release reserved network only if UI is not visible. Thus STOP then START in the same app can reuse the approved local-only Network with no extra Android prompt.
- When OCTANE is backgrounded with active LOGGER, keep the network reservation; when the Service stops in background, release it. On reopening the app, the encrypted auto-connect profile recreates a fresh network request.
- When an auto-connect profile is saved but the callback was released or is pending, starting LOGGER is **blocked** until READY. A missing saved request is reissued on explicit START. Never silently fall back to the default normal Wi-Fi network and do not send HSFZ to an unrelated AP.
- Only errors while `connectionStage == POLL` count towards the **FAST polling** rollback threshold. Failure in NETWORK/FAST_TCP/DISCOVERY/TCP no longer forces fallback A merely due to absent Wi-Fi. Polling interruptions continue to trigger existing safe rollback.

## Regression and on-car validation
- JVM: scan list filtering/duplicates/arbitrary SSIDs/BSSID, security, CSV physical newlines and escaping, STOP/START reservation policy, no automatic fallback, rollback classification.
- Build and Lint results require a GitHub Actions run on final commit; mark unverified until the run completes.
- Galaxy S25: from Settings, search for Wi-Fi, grant optional precise location, select **actual** MHD ENET_5143 (or any other personal adapter), confirm Android prompt; wait READY. Confirm system default cellular and real Internet in browser/Telegram. With DME connected: 5–10 minutes running LOGGER, switch to messenger, stop/restart LOGGER without leaving the app, verify no new prompt and no change to DME sampling. Exit app, return, confirm automatic reconnection. Simulate moving out of Wi-Fi range and reconnect; compare timestamps, Hz, CSV rows and connection events. Test denied scan permission/manual SSID.
- No car test was performed by CI. Previous 212 car observations do not count as validating 213. Keep PR draft until verified.

Rollback to build 212 / PR #35; previously stored CSV/fuel sessions remain untouched.