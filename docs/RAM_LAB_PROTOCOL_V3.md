# BMW OCTANE RAM LAB 3 — UDS 0x23, offline protocol verification

## Purpose

Prepare a strictly checked `ReadMemoryByAddress` (UDS `0x23`) implementation for BMW DME RAM Logging experiments without guessing an unknown ECU RAM address.

This iteration is **OFFLINE ONLY**. There is deliberately no call to the DME socket that sends `0x23`. The app UI shows that vehicle RAM access is **BLOCKED**.

## What was implemented

- `RamMemoryProtocol.encode(address, addressBytes, sizeBytes)`: real UDS `0x23` message format. `addressAndLengthFormatIdentifier` uses the high nibble for memory-size parameter length, low nibble for memory-address length. Width is 1–4 bytes for addresses and exactly one byte for size; request is capped at 8 data bytes; zero address and range overflow are rejected.
- `RamMemoryProtocol.decodePositive(response, expectedBytes)`: accepts only `0x63` followed by *exactly* the number of requested bytes.
- `RamMemoryProtocol.negativeCode`: distinguishes `7F 23 NRC` from a valid memory read; negative replies are not measurements.
- `approvedVehicleRequest`: a **compile-time empty allowlist** of firmware-exact, evidence-backed signal locations. No arbitrary user address can unlock it. If no exact profile exists, returns `null`.
- `offlineFixture()`: a **synthetic** address `0x12345678` / four bytes with a synthetic `0x63` reply to exercise encoding, response validation and the locked vehicle gate.
- `RAM LAB 3 • RAM 0x23 (офлайн)` in **⋮ Additional menu**. Runs an offline self-test, generates a small `bmw_ram_protocol_v3_<timestamp>.csv` and exports it via the existing Android document picker.
- Tests for packet shape, SID, exact length, NRC, boundary checks and inability to send an unapproved live request.

## IMPORTANT: this is not an on-vehicle RAM test

The synthetic address shown in the offline test is **not a BMW RAM address** and must never be sent to a DME. A passing offline test establishes only that the encoder/decoder behaves as specified.

RAM LAB 1 was able to read DIDs `4A37` and `4A49`, but the firmware-identification DIDs `F180/F181/F187/F189` were rejected with `7F 22 31`. Those DIDs are not memory addresses. MG Flasher binary type names alone do not provide a firmware-exact address mapping.

**Unlock conditions for the next revision** (all mandatory):
1. Obtain the *exact* software/calibration identification of the car's DME from a reliable supported source.
2. Obtain an independently verified compatible A2L/trace listing a concrete RAM signal address, byte length, endianness and scaling for that software ID.
3. Record the provenance and explicitly code-review a *single immutable allowlisted profile*. Add fixed match of live identity before any transaction. Never allow arbitrary addresses from UI/CSV.
4. Add a parked-only, **single-shot** transaction using a single owned HSFZ socket, strict 0x63 length or 0x7F 23 NRC validation, bounded timeout/cancellation and raw CSV journaling. On any unexpected response, close socket and stop.
5. After a valid read, compare a real signal (preferably RPM) with an independently read known OBD/DID observation taken close in time. Do not infer fuel quality from a raw number without validation.
6. Only then consider a separate study of dynamic DID `0x2C`, with its own state/cleanup safety review.

## Install/test/revert

- Package ID: `com.example.bmwenettest.ramlab3` (does not overwrite BMW OCTANE or RAM LAB 1/2).
- The offline test requires no ENET/VXSCAN connection and does not talk to a car.
- Open **⋮ → RAM LAB 3 • RAM 0x23 (офлайн)**, tap **Проверить офлайн**, then **Сохранить CSV**.
- CI checks: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`; passing CI is not validation of RAM access on a real car.
- Branch: `experiment/ram-lab3-memory-protocol` based on `experiment/ram-lab-batch-v2`.
- Rollback: uninstall RAM LAB 3 separately; do not merge into `main` until vehicle-safe profile is fully validated.
