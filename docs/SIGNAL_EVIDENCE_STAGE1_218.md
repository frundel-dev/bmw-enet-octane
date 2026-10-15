# BMW OCTANE 2.0 — build 218: existing RAW signal evidence gate

Dependency: build 217 / PR #40 at SHA 7876b176b01bdd82f4601116976e0d6650bd7bcb.

## Research objective
Begin the approved sequence **validate signal provenance and semantics → assess fuel relevance → optimize logging speed only if warranted**. This build does stage 1 **offline-only**; it does not authorize unknown DIDs or claim a Fuel Score measures laboratory octane.

### Current actual sources
- `DmePrgEvidenceLab.kt` family DME8FF_R PRG catalogue (~22 signal candidates). Width/unit descriptions are **not SWFL 00005D55-070.060.007 validation**.
- `FastDidValidation.kt` contains candidate and bootmod3 *demo* entries, not a verified BMW ECU DID map. The SWFL allowlist remains empty.
- `FastDidPassiveRecorder.kt` captures existing decoded responses (e.g. 0x4A37–3A, 0x4A36) without new UDS reads and uses `bmw_fast_did_validation_v1_*.csv`. Some new candidates legitimately appear with `NO_ADDITIONAL_QUERY` and **no raw**, never zero.
- `DmeReadOnlyProbe.kt` and `DmePrgEvidenceLab.kt` mark knock-control adaptation and injection *modes* correctly as distinct from instantaneous knock retard and injection pulse duration.

### Implemented
`SignalEvidenceGate.assess` consumes already written FAST DID audit schema v1. It validates each row using the established parser, counts malformed rows, deduplicates captured samples, checks existing PRG-family byte lengths, counts raw/missing, fresh colocated OBD RPM/LOAD/MAP (age <= 2s), unique raw values, RPM/MAP bins, distinct LOGGER starts and fuel session IDs. The gates differentiate:
- `NO_RAW`: not read, unsupported or not captured; never counted as a numeric zero.
- `FAMILY_WIDTH_CONFLICT`: raw length differs from family PRG description. Do not decode as a validated signal.
- `RAW_OBSERVED_WIDTH_UNKNOWN`: raw bytes recorded but family format not specified.
- `FAMILY_WIDTH_MATCH_ONLY`: byte width matches family PRG, **not target ECU/SWFL signal meaning**.
No automatic promotion to `SEMANTICS_VALIDATED` or Fuel Score. Repeated rows and separate trips are not equivalent to independent fuel refills.

The existing `FAST DID • RAW CSV/OFFLINE` screen now presents these provenance and context-coverage counts, without new ECU requests or any edits to existing CSV schemas.

### What is NOT verified
- Specific SWFL compatibility, raw endianness/scaling/physical units, availability of each candidate on the car.
- Correlation with genuine knock-retard, fuel identity, octane quality, or independent refuels.
- Relative fuel informativeness after conditioning on RPM, MAP, IAT/ECT, trim, ignition and DME state; this needs physically valid signals, repeated different refills and **held-out independent trips/refuels**.
- High-speed collection benefit. No logging rate, network stack, DME socket protocol or batching changed.

### Next evidence gates
1. Vehicle parked: export raw audit and compare 0x62 response identity/width against independently vetted PRG/ISTA/A2L for exact SWFL; store source, firmware and physical units. Reject unsupported DID as unsupported.
2. Once meaning and scale are independently established, test sample freshness, repeatability, RPM/MAP-controlled effect, within/between-trip variance, distinct fuel session samples, and false-positive rate on held-out refuels; never train on unverified fuel quality.
3. Only if a useful signal is under-sampled, benchmark per-request latency, actual ECU freshness and knock coverage vs CPU/energy/CSV loss; consider read-only bundled requests only with verified ECU response structure. No unsafe driving regime to fill bins.

### CI and rollback
JVM/regression tests include incomplete raw, width mismatch, context staleness, duplicate rows and trip/fuel distinctions; Android lint; assembleDebug. On-car sampling and informativeness are **NOT** CI validated. Rollback build 217; old CSVs remain readable.
