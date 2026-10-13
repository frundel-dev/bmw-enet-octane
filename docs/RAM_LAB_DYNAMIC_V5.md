# RAM LAB 5 — limited dynamic RPM DID (UDS 0x2C)

## Motivation

RAM LAB 4, on the target BMW DME, returned `63 C9 02` to
`23 14 60 00 3B 64 02`, i.e. `Nkw = 713 rpm`, matching OBD RPM
719 before and 713 after. Its single `0x23` read lasted 640 ms.
This is **not** proof of fast RAM logging.

RAM LAB 5 tests a temporary **dynamic DID** defined from precisely that
same Nkw address by UDS service `0x2C`; the objective is to measure
a short sample of `0x22` reads and confirm targeted cleanup.

## Exact format and bounded sequence

Target is a MG1CS201 ECU with:
- MG Flasher **A2L `R4C9J797B`**;
- **SWFL `00005D55-070.060.007`**;
- **Nkw SWORD at `0x60003B64`**, two bytes, little-endian, 1 rpm/LSB.
- Source: MG Flasher team's `R4C9J797B_5d55-070_060_007.a2l`,
  blob `d04645de63325a9db7ea1856088d657fb798551f`.
- This app **does not automatically verify SWFL** (F180/F181/F187/F189
  returned NRC31 in RAM LAB 1); firmware must be manually confirmed.

Single ENET/VXSCAN socket, exclusive while other diagnostic apps are stopped:

1. Verify OBD `01 0D` speed exactly 0 km/h.
2. Verify OBD `01 0C` RPM is idle (450–1800).
3. Preflight `22 F3 EF`; continue **only** if the *exact* response is
   `7F 22 31`. This means not readable in the current ECU conditions,
   **not a guarantee of no existing DDDI**. Do not run with any other
   diagnostic clients.
4. Perform one baseline `0x23` read of the already-confirmed RPM address;
   do not attempt any definition if it fails.
5. Send once `2C 02 F3 EF 14 60 00 3B 64 02` with positive ACK
   `6C 02 F3 EF`. On any explicit negative NRC, **stop**.
6. When accepted, at most six `22 F3 EF` reads, each must return exactly
   `62 F3 EF <2 bytes>`. Compare decoded values to the OBD idle baseline.
7. Read OBD RPM again and verify stable idle.
8. **Always attempt targeted cleanup if define may have reached ECU and TCP
   framing is synchronized**:
   `2C 03 F3 EF`, expecting `6C 03 F3 EF`.
   **Never clear all dynamic DIDs** (`2C 03` without DID).
9. Save complete/partial CSV including raw request/response, time, NRC
   status, decoded RPM and cleanup confirmation.
10. On user cancellation, complete the currently in-flight transaction before
    attempting cleanup. On I/O timeout/truncation/mismatched response, TCP
    stream synchronization cannot be trusted; cleanup can remain unconfirmed.
    Do **not** reuse this socket or repeat test until reviewing the CSV.

Whole test bounded to 30 seconds starting from TCP connection (cleanup
can run after this deadline). No diagnostic session switches (`0x10`),
SecurityAccess (`0x27`), resets, firmware changes or data writes.

## Data analysis

Compare latency of the six reads via dynamic DID with one RAM `0x23`
reference and normal OBD `0x01 0C` in this run; plot elapsed timing,
median, and any negative responses. Do not claim an achieved sustained
frequency on the basis of six sequential reads or a single parked idle test.

A positive `0x2C` ACK without a confirmed targeted `0x2C 03` cleanup
**does not count as a successful experiment**.

## Vehicle operation

1. Install isolated `com.example.bmwenettest.ramlab5` alongside main.
2. Ensure the ECU software is still A2L R4C9J797B / SWFL
   00005D55-070.060.007. No flash or unlock operation required.
3. Park, set selector P, idle steadily. Stop MG Flasher, regular BMW OCTANE
   and *all* other logging applications before launching the test.
4. Open **⋮ → RAM LAB 5 • динамический RAM RPM** and confirm the IDs.
5. After the one run, press **Сохранить CSV**, send it for evaluation.
   If cleanup is `NOT_CONFIRMED`, do not repeat or change session until
   carefully reviewing the failure.

## Testing and rollback

Isolated from main in `experiment/ram-lab5-dddi-rpm`, based on RAM LAB 4.
CI JVM unit tests, lint, and debug APK are necessary, but actual on-car
support for 0x2C and the cleanup behavior are NOT validated.
Uninstall this independently installed test APK to revert.
