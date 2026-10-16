# BMW OCTANE 2.0 — single engine panel on home (build 222)

Parent: build 221 PR #44, SHA `02e73efde71a603a0b6027c1750b1d444f98c86d`. This is an isolated presentation change: no transport, ECU traffic, polling cadence, fuel model, storage, CSV schema, ECU write or adaptation behavior is modified.

## Presentation
- The **original** `OctaneDashboard` card `ПОКАЗАНИЯ ДВИГАТЕЛЯ` is created detached, then attached **only once** to the **Сбор** screen. Three FAST tiles: RPM, LOAD, MAP (kPa); three SLOW tiles: coolant, oil and intake air temperatures.
- The previously duplicated `LIVE • свежие RPM / MAP` block is removed. Its STEADY cue and dwell bar are appended beneath the same original engine card, preserving safe coaching without another set of readings.
- **Ещё → Диагностика DME → подробные показания** retains the rest of `OctaneDashboard` (fuel/session research, observation mode, knock/trims, probe, polling) but not the engine tiles.
- `ACTION_FAST_TELEMETRY` alone updates RPM/LOAD/MAP; slower status packets **must not overwrite or resurrect** fast values. Status packets update temperatures when individual extras are present, no default-zero interpretation.
- Monotonic UI freshness: FAST 2,500 ms; each SLOW temperature 8,000 ms, additionally guarded by Service's `hasFreshDme`. Expired/invalid readings are **—** (no trailing temperature unit). Stop/resume without logger resets tiles. User's header DME stale indicator stays authoritative.
- `EngineReadingsFreshnessTest` covers time boundaries, independent FAST/SLOW expiry, absent data and clock rollback. This policy affects only presentation and does not change CSV validity or synchronize diagnostic samples.

## Regression acceptance
1. Install over build 221: existing refuel sessions, history and CSV remain; START/STOP still controls one LOGGER.
2. On **Сбор**, verify one engine panel with 6 tiles + STEADY cue/hold; no separate LIVE RPM/MAP card.
3. On **Ещё**, expand DME details: engine panel is **not duplicated**; knock, trims, modes and history remain accessible.
4. FAST RPM/MAP/LOAD update at the existing rate; temperatures change when delivered by status. Never assume simultaneous sampling. Missing fields and stale values become —.
5. Pause/app-switch/restore, lose/reconnect VXSCAN, stop LOGGER. No stale values masquerade as live; pending refill confirmation still requires a safe stationary state.
6. Verify layout on compact screen, large font and landscape. **Only road hardware can validate actual DME source freshness and screen behavior.**

Rollback: reinstall build 221 or revert this one UI-only PR. No migrations or changes to stored measurements.
