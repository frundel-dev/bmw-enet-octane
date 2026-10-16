# BMW OCTANE 2.0 — streamlined UI (build 220)

Base: build 219 PR #42 (`f4ad340fd58b10a06f41f83a2fe5fd884f8b27b9`). UI-only. No changes to `EnetLoggerService`, HSFZ/UDS, timing instrumentation, signal semantics, Fuel Score, fuel store, CSV schemas, or any data.

## Four tabs
- **Сбор**: current fuel as a single navigational card, pending-refuel confirmation when present, existing START/STOP LOGGER button, live RPM/MAP/LOAD and STEADY progress with next safe-mode guidance in the same card.
- **Прогресс**: repeatability/counts, RPM×LOAD coverage map, preliminary fuel analysis, side-by-side 1.7/2.0 comparison and CSV export.
- **Заправки**: create, inspect, edit, confirm detected refill, history. No duplication on collection page.
- **Ещё**: connection/VXSCAN, DME settings, collapsible existing OctaneDashboard, high-speed profile/benchmark, offline DID/PRG lab, replay, additional tool menu and CSV export. The existing 16-item additional menu remains accessible.

User-facing `Octane2Navigation` is a pure Kotlin policy to keep tab ordering and guided-progress refresh indices explicit; tested without Android. Existing `OctaneDashboard` instance retains its Service event updates; hiding it does not disable measurements. Existing STOP and Service control is reused, not recreated. The original refuel safety gate `canConfirmRefuelNow()` and first-fuel-before-start flow remain intact.

## Checks
- JVM: `Octane2NavigationTest` covers stable labels/indices, saved-tab bounds and guided refresh. All existing tests still required.
- CI: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug` (via existing workflow).
- Device/manual: first launch without selected fuel → choose fuel → LOGGER starts; start/stop while connected; switch tabs while logging and while screen off/return; highlight disconnected/stale DME; pending-refuel prompt only at safe stop; older fuel sessions/history/CSV intact; import PRG, High-Speed, RAW audit and exports still accessible under `Ещё`. Confirm layouts on compact Android screen and landscape, including font scaling.
- Not asserted: physical DME frequency, fresh FAST timestamps, hardware stability or any fuel-octane correctness; unchanged by UI-only work.

## Rollback
Select the previous build 219 APK, or close/revert only this branch. No migration, data deletion, recalibration or protocol changes.
