# BMW OCTANE v1.7.27 — vehicle validation for saved DME history

## Scope and safety

The new **История и адаптации DME** tool runs only while parked and with the live LOGGER **stopped**. It refuses to open a competing TCP session while `EnetLoggerService` is active. It opens a single socket using the current Wi-Fi/Ethernet ENET gateway (cached VXSCAN address or UDP 6811 discovery). It does **not** change the Android default network or ECU programming/session state.

Only read-only services are issued: `0x22` for the already-known candidate adaptation DIDs `0x554A`, `0x4A85`, `0x4A9D`; `0x19 0x02 FF` for the stored DTC status list, optionally `0x19 0x04` for snapshots and `0x19 0x06` for extended records of up to 12 stored DTCs, with record-number `0xFF`. Raw hexadecimal replies and negative NRCs are preserved. No `0x14` clear, `0x11` reset, `0x27` security access, `0x2E` write, `0x31` routine control or flash requests.

## First on-car acceptance

1. Park safely, stop the continuous logger via STOP LOGGER and wait for its connection to close.
2. In **Дополнительно**, select **Считать историю и адаптации DME** and confirm. Use only one scan at a time.
3. Verify the screen reports results and saves `bmw_dme_history_v1727_<timestamp>.csv`.
4. Confirm three adaptation requests were attempted. Record raw response hex, NRC and parse status. `0x554A` is an **adaptation** candidate and is NOT instantaneous per-cylinder knock retard; interpretations are provisional.
5. Confirm whether DTC listing `0x19 02 FF` is supported. If yes, inspect status-mask and number of records; `0x19 04/06` can return unsupported NRCs even when listing works. A zero DTC list is not proof no adaptations exist.
6. If more than twelve DTC records are reported, check the CSV notes truncation and only the first 12 are expanded.
7. Start the normal logger **after** history reading ends. Verify default B, B+ manual option, speed/FAST quality and no unexpected TCP reconnects.
8. Scan again after another comparable journey if investigating adaptation *changes*, with time, fuel batch, load and ambient conditions noted independently. No conversion to RON and no changes to Fuel Score.

## Error and cancel tests

- While LOGGER is active, the scan must refuse to start.
- While history scanning, a user attempt to START LOGGER must be blocked in the same UI process.
- Cancellation halts further requests, closes the socket and leaves any partial CSV readable.
- Unmatched UDS responses or incomplete HSFZ frames terminate the scan; never map one DTC record to a different code.
- A final answer to `0x7F 0x19/0x22 0x78` is awaited with a bounded read limit; if absent, close the connection rather than issue the next request.

## Interpretation limits

- The DME is not guaranteed to store chronological per-drive high-frequency engine traces.
- DTC snapshots depend on an actual diagnostic event and the firmware's supported record numbers.
- DTC codes and raw manufacturer snapshot bytes remain uninterpreted unless the ECU's exact diagnostic description confirms their meaning.
- The 3 candidate DIDs may be unavailable in the default diagnostic session. No automatic session escalation or security access is attempted.
- Never infer octane or knock severity from a DTC count or one accumulated adaptation.
- Stored responses can contain vehicle identifiers. Treat exported files as private.

## Regression gates

`gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` on CI; unit tests cover read-only subfunctions, 24-bit DTC decoding, truncation, status masks, NRC classification, source identity checking and safe CSV encoding. Real vehicle verification remains outstanding.
