# BMW OCTANE RAM LAB 4 — single live UDS 0x23 RPM probe

## Scope and source
This is a **single on-car RAM read**, unlike RAM LAB 3's offline-only fixture.

MG Flasher vehicle screenshots taken on 2026-10-13: ECU Bosch MG1CS201 B48 Gen 2, **A2L `R4C9J797B`**, **SWFL `00005D55-070.060.007`**, BTLD `00004295-001.020.002`, custom code `v0.5`, FEMTO Wave 3. No VIN or serial numbers are retained in this repository.

MG Flasher Team published source:
`A2Ls/B48 B58 Gen 2/Generic/R4C9J797B_5d55-070_060_007.a2l`
in `mgflasher-team/mgflasher-map-packs`, git blob `d04645de63325a9db7ea1856088d657fb798551f`.

The A2L's *external filename* agrees with both vehicle A2L and SWFL. Its internal module/EPK is `MG1CS201_BX8TUE_R4C2J797B`, not literally `R4C9J797B`. This R4C9/R4C2 pair is independently seen in MG1CS201 software catalogues. It remains a source-mapping inference, **not an on-car validation of the RAM address**.

**One approved measurement only:**
- `MEASUREMENT Nkw` — Engine speed.
- `ECU_ADDRESS 0x60003B64`.
- `SWORD` (two bytes), `BYTE_ORDER MSB_LAST` (little-endian).
- `COMPU_METHOD caoddzd_oAjpfzb_cAcEEha_1nR_oa0`: `RAT_FUNC` coefficients `0 1 0 0 0 1`, rpm identity.
- UDS request: `23 14 60 00 3B 64 02` (one-byte memory size, four-byte big-endian address).

## On-vehicle constraints
- **Only while parked**, gear selector P, engine steady idle.
- Stop *every other* diagnostic app (BMW OCTANE, RAM LAB 1/2/3, MG Flasher) and their loggers before starting. Separate Android package IDs mean the guard cannot detect other applications.
- The UI explicitly requires the operator to reconfirm both A2L and SWFL **have not changed** since the MG Flasher screenshots. Because the car rejected `F180/F181/F187/F189`, no automatic on-car software-ID check is available; **a wrong manual confirmation could send the request to another firmware**.
- One owned ENET/VXSCAN TCP socket using the existing HSFZ framing and serialized `transact()` engine. No concurrent reading of a socket.
- OBD `01 0D` speed **must be 0 km/h**; otherwise no memory request.
- OBD `01 0C` initial RPM must lie within 450–1800.
- Exactly **one** UDS `0x23` request, 2 bytes only; no loops or retries for NRC/failure.
- Expect only `63 <2 bytes>` or explicitly record matching `7F 23 NRC` and stop. Truncated, unexpected or unrelated responses abort and close socket.
- Read OBD `01 0C` afterwards and correlate ±100 RPM against both readings, requiring OBD drift ≤120 RPM; a 0x63 reply alone is not sufficient for success.
- CSV stores raw request/response, status, elapsed time, transport and fixed profile for independent analysis. Partial journal flushed on failure/cancel.
- No `0x10`, `0x27`, `0x2C`, `0x2E`, flashing, patching, resets or calibration changes. No session/auth retry if access refused.
- **Stop immediately** on any DME malfunction or diagnostic interruption. There is always residual risk with unverified proprietary ECU memory access.

## Instructions
1. Install independent `com.example.bmwenettest.ramlab4` APK and verify exact ECU software in MG Flasher has not changed. Do not leave MG Flasher connected.
2. Park safely; selector P and engine at steady idle, LOGGER STOP in all apps.
3. Open `⋮ → RAM LAB 4 • одно чтение RAM RPM`.
4. Read the confirmation and tap `ПО совпадает • Начать` **only if both IDs truly match**.
5. Save `bmw_ram_live_v4_<timestamp>.csv` and send it for analysis, including if test terminates early.
6. No further RAM requests until this single response is validated. If negative, do not bypass ECU security.

## Interpretation
- `0x63 + 2 bytes` with decoded Nkw plausibly between initial and final OBD RPM is **initial evidence** of live RAM access. It does not yet validate timing, scaling of other variables, 0x2C, or high-rate RAM logging.
- A negative NRC means this specific access was refused in current diagnostic context; it does not mean all RAM methods are impossible.
- A plausible number with mismatched A2L/SWFL is not valid evidence.

## Tests and rollback
Run CI `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. Code tests verify allowlist, exact request, length and byte-order handling, stationary/idle eligibility and OBD correlation. No real car accessed by CI.

Base: `experiment/ram-lab3-memory-protocol` commit `acbac8f2`. Main remains untouched; uninstall isolated RAM LAB 4 app for rollback.
