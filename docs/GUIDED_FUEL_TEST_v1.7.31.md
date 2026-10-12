# BMW OCTANE v1.7.31 — guided fuel testing

## User goal

On the main screen, select a NEW REFILL (claimed fuel grade and optional gas
station), drive in suitably safe conditions with read-only ENET/VXSCAN polling,
follow brief RPM/LOAD hints, then review the result.

This is intentionally distinct from the hidden developer tool **Обучение
эталона**: *current test fuel is NEVER learned as its own quality reference*.
All data from earlier experimental baseline collections remain separate.

## Main screen

- **НОВАЯ ЗАПРАВКА**: available only with LOGGER stopped, selects AI-92,
  AI-95, AI-98, AI-100, or other and optional gas-station label.
- Launch automatically switches measurement mode to AUTO and starts LOGGER.
  Polling mode is unchanged (B+ default). All communications stay read-only.
- Compact coverage card: the selected fuel, STEADY n/2 ready cells, ACCEL
  n/3 ready RPM bins and overall progress. The guidance asks for another
  attainable cell, never assumes a particular gear or legal speed.
- **ПРОГРЕСС И РЕЗУЛЬТАТ**: shows raw accepted coverage and comparison if
  supported; otherwise reports insufficient data. A user can pause, resume
  or end early (ending early cannot be misrepresented as sufficient).
- Optional history in **Дополнительно → История тестов топлива**. Legacy
  diagnostics and developer calibration map remain accessible in overflow.

## Data collection and completion

Consumes ONLY existing fresh FAST / SLOW values in AUTO mode. No extra UDS
read, ECU write, reset, coding, or parallel TCP connection.

- STEADY: qualifying warmed RPM 1300–4499, load 15–68%, MAP 88–150 kPa,
  |rpm/s|≤250, |MAP kPa/s|≤16.
- ACCEL: qualifying RPM 2500–4499, load 65–100%, MAP≥200 kPa,
  rpm/s 130–1800, map/s≥−65, and throttle≥25% if available.
- Shared acceptance: four knock channels, four ignition channels, positive
  knock amplitude, ignition spread present, vehicle speed≥15 km/h, IAT
  10–65 °C, coolant≥65 °C, nominal trims (if present), no current knock
  status/superknock flags, no suspected refill, no mixing in progress.
- 2.5s STEADY and 1.5s ACCEL min inter-acceptance intervals.
- distinct STEADY segments after >6s gap or RPM×LOAD cell change;
  distinct ACCEL segments after >2.5s gap or RPM falling ≥180.
- Progress uses segment support, not just thousands of autocorrelated samples.
  At least **two distinct STEADY cells** (4+ points and 2+ segments per cell)
  and **three ACCEL RPM groups** (4+ points and 2+ segments per group) produce
  the **preliminary capture complete** condition. These are pragmatic early
  stopping thresholds, not independent validation of fuel quality.
- Data persist across LOGGER restarts. New-fuel sessions never re-use the
  current fuel's samples for reference. Suspected refill suspends the test.

## What the result does and does not mean

The immutable `OctaneCalibration.accelerationReference` from prior declared
AI-95 sessions can furnish **only a provisional relative comparison of knock
sensor signal** for RPM 2500–4500 and MAP≥200 kPa, after the existing data
quality gates. Use a median of segments within each RPM group, then a median
of comparable RPM groups (≥2 supported groups); an isolated short or very
long acceleration should not dominate.

This does NOT measure laboratory RON/MON, prove the grade printed on the
pump, or diagnose non-octane fuel contaminants. The historical 95-RON
baseline is not laboratory certified, has modest source coverage and cannot
fully eliminate temperature/load/ECU adaptive confounders. Therefore the
result deliberately **does not classify gas as "good"/"bad" or give a fake
octane number**. The independent external fuel reference and genuine
cross-fuel validation necessary for that assessment remain future work.

Existing Fuel Score v0.9, DME monitoring, and historic baselines do not
change. Frozen `OctaneCalibration.kt` Git blob SHA:
`d1e00e8e96b22b64e4284451d81a2587f26a61d7`.

## Storage / QA

- `bmw_fuel_test_v1731_<session-id>.csv`, comment header with user fuel
  label and fuel session and the existing BaselineLearning numeric CSV schema.
- No write to existing Fuel Score calibration.
- Unit tests: boundaries, adjacent-sample false independence, ability to
  complete with two STEADY + three ACCEL groups, relative ratio, RON
  disclaimer state, and rejection of malformed samples.
- GitHub Actions: `testDebugUnitTest`, `lintDebug`, `assembleDebug`.
- Road-test: new refill -> warm + mixing complete -> 2 STEADY cells ->
  distinct valid accelerations where permitted -> automatic result; stop/restart
  LOGGER; pause/resume; attempt suspected refill; check history and CSV.
- Do not chase unreachable MAP loads or RPM ranges on public roads. If a
  complete series cannot be collected safely, end it with insufficient data.
