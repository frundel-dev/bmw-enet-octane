# BMW OCTANE 2.0 LAB — VXSCAN local-only Wi-Fi experiment (build 212)

Scope: distinct, opt-in UI/network connectivity experiment based on exact PR #34 build 211, **no HSFZ/UDS/Fuel Score/CSV decoder changes**. Branch deliberately separated from unfinished PR #29–34 chain. Does not add or modify FAST requests.

## Goal / limitation
On Galaxy S25, a conventional saved Wi-Fi VXSCAN AP with no Internet is sometimes selected by Samsung One UI as the default network, breaking LTE/5G access in other apps. Android `WifiNetworkSpecifier` requests a *local-only* Wi-Fi connection, not an Internet network. The intended behavior is VXSCAN TCP 6801 and UDP discovery 6811 via reserved local-only `Network`, general app Internet through the system cellular default. **Not guaranteed until tested on the user's One UI firmware**; the app cannot change default routes for other applications. Some OEMs may not support secondary STA concurrency.

## Implementation
- Settings: “VXSCAN + 4G/5G • LOCAL-ONLY WI-FI”. Input actual SSID (not hard-coded to VXSCAN; any SSID), WPA2 password or empty for open AP; optional exact BSSID.
- Explicit opt-in “Автоподключение при запуске BMW OCTANE” (default checked). Store SSID and BSSID in preferences, WPA2 password **only AES-256?** Android Keystore AES/GCM encrypted blob (the concrete key size is Android-generated), not plaintext. Do not claim passphrase recovery after reinstall/resetting Keystore.
- On opening app again, if user previously opted in and Android permission persists, recreate the *exact* local-only NetworkRequest. Android may remember user approval for matching specific AP; exact SSID+BSSID improves this but no absolute OS guarantee. BSSID can be auto-captured from `WifiInfo` if Android exposes it; redacted values are rejected. If it is hidden, user can supply it manually, or allow system selection when needed.
- On Android 13+ request NEARBY_WIFI_DEVICES; on older supported versions request ACCESS_FINE_LOCATION. No scanning of nearby SSIDs by the app; no root/VPN; permission request originates in user UI.
- `VxscanLocalOnlyWifi` holds one `NetworkCallback` for the user-approved network. READY requires a Wi-Fi Network and valid IPv4 from `LinkProperties`. No process-global `bindProcessToNetwork`.
- `connectPrimaryNetwork()` in the existing single-owner `EnetLoggerService` uses the reserved Network as the **only** connection candidate when local-only mode is enabled; no fallback to unapproved Wi-Fi. TCP `network.socketFactory` and UDP `network.bindSocket` already existed. If not READY or lost, fail closed/reconnect, rather than addressing a random AP.
- Settings show phase and current Android default transport to assist field validation; do not claim Internet works based solely on LTE icon/default transport. LOGGER start is blocked while reservation not READY, including a direct Service ACTION_START.
- STOP and Service destruction release Android callback. Leaving foreground without an active LOGGER releases an idle READY network; next app launch automatically requests again with the securely saved profile. Disabling via Settings **forgets** encrypted profile and clears reservation.

## Limitations, acceptance, rollback
1. On a stationary car, enable Wi-Fi and cellular data. Turn off Android's normal Wi-Fi auto-join to the saved VXSCAN AP if it competes with the local-only request. This is a possible one-time platform constraint, not a per-drive step.
2. Enter *actual* SSID (may vary by adapter) and password once; optional BSSID. Accept Android network approval. Wait for READY + system default preferably CELLULAR.
3. Open Telegram/browser using cellular while OCTANE LOGGER collects via VXSCAN. Check DME connection and CSV completeness. Observe whether Android requests approval again on *second launch*. Compare with build 211.
4. Test STOP/START, disconnect of scanner, restart app, switch to messenger during active logging, verify no accidental HSFZ connection to normal Wi-Fi. Check reboot and airplane/permission changes when parked.
5. If One UI still makes regular saved VXSCAN Wi-Fi default or does not support dual interface, document failure and disable mode; no VPN fallback is implemented. On-car internet behavior, cellular routing, exact BSSID approval, native crash stability remain **UNVERIFIED** until user tests.

JVM tests: arbitrary SSID (not prefix), passphrase bounds, BSSID validation, auto-request opt-in, fail-closed route choice. Android Keystore and Wi-Fi callback integration require real Android device, not proven by JVM unit tests. Roll back to build 211 or turn off the feature without affecting CSV/fuel sessions.

Important: this is an opt-in *network route* experiment, not a fuel or engine behavior experiment. No new ECU data requested.