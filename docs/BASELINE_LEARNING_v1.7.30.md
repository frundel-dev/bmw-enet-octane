# Experimental baseline collection v1.7.30

## Definition and limitations

BMW OCTANE is **not** measuring RON. There is no justified way to infer an
absolute octane baseline from an arbitrary unknown sample of gasoline. The
new calibration dashboard **only acquires observational data** for a
particular vehicle + DME flash + fuel mixture and provides coverage metrics.
No live calibration, ECU adaptation, learning of OctaneCalibration.kt, or
promotion of a collected dataset to confirmed AI-95 takes place.

The user reports BMW G20 xDrive B48 MG Flasher Stage 1 set to AI-95, but the
actual fuel quality is not laboratory-certified. The dataset label is
explicitly user-entered and unverified.

## Usage

1. Select measurement mode **AUTO** with logger STOPPED.
2. Open Дополнительно → Обучение эталона • RPM × нагрузка.
3. Create a new dataset with an unambiguous fuel/flash label (logger must be stopped).
   Old datasets remain under their own unique CSV filenames.
4. Start LOGGER, B+ polling (default). Sampling uses existing FAST/SLOW UDS
   values, no additional DME requests or TCP connections.
5. Inspect STEADY / ACCEL matrices; refresh automatically every 4.5 s.
   Tap any cell to inspect count, independent segments, logger starts, IAT
   buckets and the minimum readiness target.
6. Choose the next feasible RPM / load region based on short dynamic advice.
   Suggested operating conditions are not a request to exceed speed limits or
   perform hard acceleration on a public road.
7. Pause collection in the menu without stopping the normal logger. The
   active training dataset pauses after suspected refuelling or a fuel-session
   mismatch, and a new dataset must be created with logger stopped.

## Explicit RPM/LOAD maps

| STEADY RPM bins | LOAD bins | MAP condition |
| --- | --- | --- |
| 1300–1999, 2000–2499, 2500–2999, 3000–3499, 3500–4499 | 15–34%, 35–49%, 50–68% | 88–150 kPa absolute |

| ACCEL RPM bins | LOAD bins | MAP condition |
| --- | --- | --- |
| 2500–2999, 3000–3499, 3500–3999, 4000–4499 | 65–79%, 80–89%, 90–100% | ≥200 kPa absolute |

The model does not presume all 27 cells are physically reachable on a cruise
control route. Never fabricate observations for unreachable regions. The
progress percentage is **within one cell only**, computed as the smallest of
sample, distinct segment and distinct logger-start progress.

A green cell needs ≥24 observations, ≥4 distinct segments and ≥2 distinct
LOGGER starts, and **means ready to be independently evaluated**; it does NOT
mean calibrated or validated. Starting and stopping LOGGER on the same route
does not produce independent weather, fuel or vehicle states. IAT diversity is
shown but not used as a proxy for fuel authenticity. A future candidate model
requires held-out **entire trips/fuel sessions** and a comparison of
temperature/trim-conditioned residuals.

## Capture gating

AUTO STEADY: 1300–4499 RPM, LOAD 15–68%, MAP 88–150 kPa, |dRPM/dt|≤250,
|dMAP/dt|≤16 kPa/s, speed≥15 km/h.
AUTO ACCEL: 2500–4499 RPM, LOAD≥65%, MAP≥200 kPa,
130≤dRPM/dt≤1800, dMAP/dt≥-65 kPa/s, throttle≥25% if available.
All records: coolant≥65 °C, 10≤IAT≤65 °C, valid four knock and four ignition
values, positive knock mean, no active knock or superknock indication,
trim comparable (|STFT|,|LTFT|≤15% if known), no pending refill and
mixingKm=0. SLOW values must pass existing freshness checks.

STEADY sampling at most one point per 2.5 s, ACCEL at most one per 1.5 s.
A new steady segment starts after >6s qualified gap or cell change; a new
accel segment after >2.5s gap or significant RPM decrease. Distinct segments
within one LOGGER start are **not equivalent to different trips**.

## Storage

`bmw_baseline_learning_v1730_<dataset-id>.csv`
- First line observational status and human fuel label; no certified octane
- Header: `wall_time_ms,trip_id,fuel_session_id,phase,segment_id,rpm,load_pct,map_kpa_abs,iat_c,coolant_c,stft1_pct,ltft1_pct,knock_mean_vms,ign_spread_deg`
- CSV is append-only, numeric fields use language-independent formatting.
- Each UI refresh computes stats asynchronously, independent of the ECU
  polling thread, ignores incomplete/corrupt lines and rejects rows from
  another fuel session.

## Acceptance (real vehicle)

1. Create dataset stopped; choose AUTO measurement mode; B+ polling.
2. Warm engine, no mixing, check STEADY count increases during suitable cruise;
   under 2500 ms between accepted points must not be counted.
3. ACCEL matrix gains points only under specified load/phase/MAP and safe
   operating conditions; never on parked engine.
4. Confirm disabled dataset gives no new rows, and resume works while logger
   is active. CSV persists through logger STOP/START.
5. Verify trip counts increase across new logger starts, not FAST cycles.
6. Trigger or simulate a confirmed refill: recording suspends; prior CSV
   remains unchanged; no silent mixing.
7. No Fuel Score changes: SHA-1 (Git blob) of `OctaneCalibration.kt`
   remains `d1e00e8e96b22b64e4284451d81a2587f26a61d7`.
8. JUnit, Android Lint and debug APK build must pass.
