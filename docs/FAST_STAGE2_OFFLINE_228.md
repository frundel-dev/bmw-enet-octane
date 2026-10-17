# BMW OCTANE 2.0 — FAST Stage 2 / Iteration 1 (build 228)

## Decision and status

**Offline-only preparation; no extra traffic to DME.** Parent: build 227,
PR #50 (`3d34632544acd041464613816fa005532bd74657`).
This PR does **not** activate a new individual DID or new multi-DID 0x22
request. The existing B+ request and legacy FAST/Fuel Score remain intact.

No raw original DME8FF_R.prg executable was present in the accessible GitHub
tree or current Project file search during this iteration. Earlier offline
PRG verification is documented in `docs/DME8FF_PRG_OFFLINE_LAB_211.md`,
the `DmePrgEvidenceLab` JVM source and the production PRG-family-width
registry. Its family-level provenance (SHA-256
`f227991a7a5359751011660d3136de295c2511245ed9490bca4715fef07b0f20`)
does **not** prove SWFL-specific support. The actual ECU software identity,
DID definitions and units of three proposed IDs have not been confirmed.
We do not substitute bootmod3 demonstration catalog entries.

## Evidence matrix for the proposed 12-DID grouped FAST

| DID | Proposed interpretation | Local byte width | Evidence | Permission |
| --- | --- | --- | --- | --- |
| 4807 | RPM candidate | **unknown** | assignment text/catalog claim only | NOT_VERIFIED |
| 5813 | LOAD candidate | **unknown** | assignment text/catalog claim only | NOT_VERIFIED |
| 580B | MAP candidate | **unknown** | assignment text/catalog claim only | NOT_VERIFIED |
| 4A36 | knock status code | 1 | existing DME read-only path / profile | existing only |
| 4A37–4A3A | four knock reference signals | 4 each | existing B+ and PRG family profile | existing only |
| 4A49, 4A4A, 4A4C, 4A4D | four ignition angles | 2 each | existing B+ and PRG family profile | existing only |

**Existing B+ eight-DID read has already been used on the car** according
to the reported build-227 evidence. This does not authorize combining
`4A36` with B+, or incorporating RPM/LOAD/MAP in one 12-DID request.

The local PRG-family catalog includes *other* candidates:
`5819` (mean RPM over a cylinder segment, preliminary signed 16-bit ×0.5),
`5804` (relative air mass, preliminary uint8 ×100/256),
`4A2D` (MAP, preliminary uint16 ×20/256/10 kPa). Their semantics
are not automatically equal to OBD `01 0C`, `01 04`, `01 0B`.
These **are not replacements** for `4807`, `5813`, `580B` and are
not enabled for new live requests. Units/scales are offline PRG family
previews, not independently verified current-SWFL readings.

Exact length of the proposed *12*-DID positive response is therefore
**unknown**. Only the nine existing DME widths yield 25 payload data
bytes (1 knock-status + 16 knock bytes + 8 ignition bytes).
A full response also includes SID `62` and two identifier bytes per
field; the three candidate data widths MUST be confirmed before a
production parser or live request can be approved.

## Code and architecture

- `GroupedUds22Research` in `:core:diagnostics` holds an immutable,
  non-authorizing 12-DID registry, explicit unknown widths, a synthetic
  test-vector encoder and an offline parser using established
  `FastDidValidation.parseExact`.
- Offline parser requires exact 0x62 SID, exact ordered unique DID sequence,
  exact per-DID size and exact end-of-payload. It classifies an exact
  `7F 22 NRC` reply as negative and rejects wrong SID, NRC service,
  duplicates, extra bytes, swapped, truncated and incomplete data.
- `LabGroupedUds22Evidence` in **LAB-only `:feature:benchmark`**
  classifies *already captured* UDS payloads as
  `POSITIVE_RAW`, `NEGATIVE_NRC`, `INVALID_REPLY`,
  `NO_RESPONSE`, or `AMBIGUOUS`. It includes a separate CSV-v1
  serializer with trip/fuel/epoch/cycle, monotonic host timestamps,
  requested IDs, original raw UDS hex and failure reason. It contains
  no transport API and does not write into any existing user CSV.
- Both new vehicle permission checks are hard-false even for parked
  vehicle, SWFL confirmation or explicit consent. This is deliberate:
  absence of validated ECU-specific definitions means **zero permitted
  new DID probes**. The ordinary read-only B+ and Benchmark are untouched.
  No settings or UI entry point invokes the research component.

A synthetic success means only that a byte sequence satisfies a specified
*test* schema. It does not validate widths, signal semantic equivalence,
ECU sample synchrony, returned ECU values, Fuel Score or RON.

## Verification plan and limitations

JVM tests exercise 12-DID encoding for offline fixtures, fixed/variable
width parsing, order/repetition/corruption, exact SID/NRC, no stale-cycle
reuse, missing/ambiguous replies, invalid timestamps and the hard-locked
vehicle gate. Existing HSFZ ACK/DATA, fragmentation, reconnect, scheduling,
CSV, scoring and guard tests continue unchanged in CI. They are **not**
evidence that new grouped read/fallback or one-shot probe behavior has been
validated on the vehicle; no such live feature is deployed in build 228.

Build 227 on-car metrics reported in the assignment (not independently
reanalyzed here): 2.69 completed FAST sets/s, 342/342 complete,
host skew p50=218.5 ms, p95 approximately 401 ms,
FAST p50=304 ms, p95=466 ms, CSV loss=0. No controlled 226/227
A/B data is available in this change.

## Gate for any future active DID probe / iteration 2

1. Obtain the actual DME software identity / SWFL and primary reference
   for each proposed DID. Confirm length, byte order, signedness, scaling,
   units and semantic relationship to OBD. Review source authenticity.
2. In a **separate PR**, whitelist exactly one documented DID; LAB only,
   default OFF. Require a parked vehicle with *fresh* speed/ECT, confirmed
   ECU profile/SWFL, explicit operator consent, and a single-owned socket
   with no competing LOGGER/Benchmark. One serial 0x22 read, no retries
   on ambiguity, preserve bounded complete wire evidence, PID/DID/NRC,
   ACK/DATA and timestamps. Do not unlock new commands by this offline
   module's test manifests.
3. Reconnect after ambiguous TCP framing, abort without continuing on the
   old socket. Compare one-shot raw values with close-in-time OBD without
   assuming the physical variables coincide.
4. After independent vehicle confirmation of *all* individual DIDs,
   separately validate the 12-DID combination and its exact reply width
   on the vehicle. A valid single DID does not prove group support.
5. Only after those gates, implement isolated LAB grouped FAST, explicit
   fallback to verified B+ and parked A/B/A comparison. Maintain the exact
   single owner, existing CSV formats, read-only SID and no new RAM or 0x2C.

Rollback: close this draft PR and use unchanged build 227. For an
already-installed build 228, distribute the reverted code with a **higher
versionCode**, never uninstall user data for a downgrade.
