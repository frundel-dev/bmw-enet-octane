# BMW OCTANE 2.0 build 221 — retiring legacy laboratory UI

**Base:** build 220 PR #43, SHA `2de5d486226d76f22c9212b757a65f0834a01bce`.

## Why
A completed one-off exploratory workflow should not remain next to START/STOP, refill selection, health, or CSV export. Conversely, some tools are still needed to validate FAST timing, knock/ignition semantics, reconnect and fallback. We must **not** call an experiment scientifically validated simply because its code or CI passed.

## Production UI changes
- Removed the manually imported PRG lab entry, all PRG sample dialogs and the obsolete SAF PRG importer (request 901). Existing imported PRG files and stored PRG metadata are **not deleted**, and existing CSV header fields remain intact.
- Removed old `Offline replay` dialog. The pure `DriveObservationReplay` parser and tests were moved from `src/main` to `src/test` — still available to offline regression tests, no longer in APK.
- Moved `DmePrgEvidenceLab` pure parser from `src/main` to `src/test`, keeping its unit tests / candidate specifications available for offline research without shipping manual ECU profile interpretation. **Production still retains `DmePrgFamilyWidths` (20 known family widths, unknown/structured fields remain null)** because the build 218 `SignalEvidenceGate` needs the read-only RAW length checks. Its correspondence with the offline catalog is regression-tested.
- Removed obsolete manual `BaselineLearning` UI and AUTO/TEST selector. **Kept** production `BaselineLearning` structure and historical datasets for compatibility with the ongoing analysis and existing Service; *did not remove or change the model or any CSV parsing/writing*. On first launch after upgrade, disabled only the hidden manual capture boolean and selected `AUTO` so users previously left on TEST are not locked in obsolete logging behavior. CSV / session metadata are preserved. Migration is one-time, recorded in preferences; behavior applies on next logger start.
- Removed old, immediately discarded New Refuel/overflow controls and redundant STEADY hint from the layout. The prominent original LOGGER start/stop callback remains.
- Operational diagnostics: connection/network history, TCP reconnects, DME Monitor history, read-only DME history and snapshots, polling performance, measurement/fuel history, export. A *single explicit developer entry point* contains the still-unvalidated FAST DID candidate audit, passive RAW offline evidence and RAM/SWFL configuration.
- Still in production: read-only single socket owner, HSFZ/UDS, existing grouped B+/B/A fallback, High-Speed profiles / benchmarks for pending build 219 timing validation, async CSV writer, fuel score, guided collection, fuel session state and all persisted CSV schemas. None are changed by this PR.

## Non-goals / evidence limits
- RAM/SWFL compatibility, knock/ignition meanings, 20–50 Hz and DME ECU acquisition timestamps are **not verified by this UI cleanup**.
- The RAM opt-in remains guarded behind an explicit experimental developer action, with the original STOP/vehicle-SWFL warnings. It is not silently enabled.
- `BaselineLearning` internal capture gate still exists for backward compatibility, but retired manual recording is disabled once at upgrade; no historical dataset or previously accepted sample is erased.
- Old helper methods `runLogger` / `runTest` in `MainActivity` are deliberately not touched here pending independent protocol dependency audit; they are not shown in UI.
- Offline research source modules are in tests, not removed from the GitHub history.

## Checks
- `DiagnosticMenuTest`: operational vs research tool whitelists; disjointness; migration TEST/AUTO + manual recording true/false to AUTO + disabled recording.
- Existing tests for `DmePrgEvidenceLab`, `DriveObservationReplay`, `BaselineLearning`, `GuidedFuelTest`, `FastDidValidation`, HSFZ and timing must still pass unchanged.
- CI must pass `testDebugUnitTest`, `lintDebug`, `assembleDebug` and APK upload.
- Device: tabs, START/STOP, first fuel choice, refill detection only while stationary, CSV export/old data, DME connectivity, background restore, safety of B+ fallback, developer-only diagnostic audit menu; upgrade from TEST mode then verify AUTO. The last set requires a car and VXSCAN/ENET.

## Rollback
Return to build 220/PR #43 without uninstalling or clearing app data. One-time migration disables retired mode prefs on 221; reverting APK does not automatically restore prior TEST/manual baseline flags (intentionally to avoid hidden capture), but **existing CSV, fuel metadata and historical datasets remain**. If exact legacy settings are required, they must be restored separately from an authorized backup.
