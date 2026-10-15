# BMW OCTANE 2.0 — PRG Signal Lab, build 211

## Purpose and provenance
This standalone experiment starts from the original DME8FF_R.prg (7,458,486 bytes; SHA-256 f227991a7a5359751011660d3136de295c2511245ed9490bca4715fef07b0f20), offline audit 2026-10-14, offline verification 2026-10-15, and the 22-signal registry. PRG contains family DME8xx definitions, **not verified for current target SWFL 00005D55-070.060.007**.

The user reports no further app process crashes while testing the preceding build 210, but lack of crashes does not prove device/ECU SWFL identity or diagnostic command applicability. This PR implements **offline software preparation only**. No live authorization to request new DIDs has been issued.

## Implementation (independent of FAST)
- `DmePrgEvidenceLab.kt`: immutable 22-signal PRG catalog and limited single-DID RAW UDS preview.
- Uses existing `FastDidValidation.parseExact` rather than a new socket or decoder. Validates positive SID 0x62, exact DID, exact byte count, negative 0x7F/0x22/NRC; rejects malformed/ambiguous frames. Input is manually supplied payload **without HSFZ framing**, not proof of provenance.
- `DmePrgEvidenceLab.canIssueNewDidRequest` is hard-locked **false** even with parked=true and an SWFL checkbox. The existing separate `FastDidValidation` request allowlist remains empty. No new read-only DME command is sent, **ever**, in build 211.
- `0x4035` (KQE) and `0x407F` (superknock history) have unspecified structured lengths, so offline decoder remains LOCKED until complete validated schema. In particular no assumption that `22 40 35` is safe or supported.
- New Settings item "DME PRG • RAW OFFLINE (БЕЗ ECU)" displays catalog and lets a user paste previous UDS payloads. It never invokes the ECU Service and never writes calibration/CSV.
- Established `DmeReadOnlyProbe`, `DmeMonitorPolicy`, `MixedDidBatch`, `HsfzCodec`, `EnetLoggerService` and original knock decoder are **untouched**.
- Output is always labeled OFFLINE PREVIEW and SWFL/RAW/source/semantics unverified, not fuel quality nor measured RON.

## Scientific distinction
`0x4A37–0x4A3A`: normalized knock **reference** level, UInt32 BE × 0.05/65536 V·ms. DO NOT reinterpret as knock events or change historic knock_z CSV columns. `0x5883/5885/5886/5888`: different knock-value group (candidate), same preliminary PRG scale, **not** independent proof. `0x554C`: mean spark retard under KR, signed8 ×0.375 °KW (candidate). `0x554A`: adaptation from KR, signed16 ×0.1, unit remains disputed in PRG. `0x5776`: internal factor /256, not a laboratory octane measurement. `0x4035`: internal history categories, not lab fuel data.

The prior offline verification found APP_REPORTED_OK with four 0x554A raw `0000` rows and three 0x4A36 raw `00` rows; these are **not full wire frames**. Other new candidates still lack vehicle raw positive responses and SWFL-specific proof. Full scientific assessment requires vehicle captures, repeatability across independent trips and fuel sessions, and independent fuel references.

## Required gates before any active, isolated one-DID 0x22 experiment
1. Confirm exact ECU software/version and target SWFL from the vehicle with a trustworthy primary source; prove relation to PRG and command.
2. Confirm target operation uses only safe UDS 0x22 `ReadDataByIdentifier`, expected returned SID/DID/width, diagnostic session, gateway addresses, and handling of NRC; no hidden preconditions changing ECU.
3. Only after review, create **another distinct PR** allowing **one immutable DID (0x554C)**. Must run only after LOGGER STOP (no second reader), parked, explicit UI approval, one-shot with strict timeout, no retries following ambiguity, no 0x2C / dynamic DID / write / coding / adaptation/reset/security access. Default OFF.
4. Capture independent `wall_time_ms`, `elapsedRealtime`, HSFZ transaction, `SID/DID`, `raw_hex`, width, NRC, timeout and session. No fake zeros. Keep FAST quality/frequency/CSV untouched.
5. Begin on standing car (no RPM excursions or induced knock). Repeat safe time-spaced readings, independently correlate RPM/MAP/IAT/ECT and actual SWFL. Only after scientifically sound review consider fuel-score research, without training unknown fuel as reference.

## Regression and rollback
`DmePrgEvidenceLabTest` covers registry uniqueness, locked vehicle requests (including existing FastDidValidation guard), signed negative/positive retard, fuel factor, 32-bit reference/knock-value, 0x554A ambiguous units, exact SID/DID/length, NRC, structured job lock and basic MAP/ignition conversion. 
Hardware test not performed in CI; this build sends no new DME requests. CI workflow result must be recorded separately. Rollback to build 210 via previous PR, keeping all user CSV unchanged.
