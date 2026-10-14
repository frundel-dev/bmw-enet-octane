# BMW OCTANE — HSFZ protocol conformance audit (build 208)

## Primary comparison
Reference: uholeschak/ediabaslib, `EdiabasLib/EdiabasLib/EdInterfaceEnet.cs`, especially `TcpDiagEnetReceiver`, `ReceiveEnetAck` and diagnostic send/ACK validation. Source: https://github.com/uholeschak/ediabaslib/blob/master/EdiabasLib/EdiabasLib/EdInterfaceEnet.cs

The upstream receive state machine accumulates the 6-byte HSFZ header and length-defined remainder, dispatches type 0x01 diagnostic, type 0x02 ACK, type 0xFF NACK, and responds to type 0x12 alive-check with an 8-byte type 0x13 reply. Its diagnostic transmit path validates ACK against the sent message and performs conditional retry; that retry is **not** imported into OCTANE because an interrupted response can be late and blindly retrying on the same socket would risk attribution.

## Prior OCTANE behavior (build 207)
- Strict `exact` TCP fragmentation and size bounds: already present; retained.
- Type 0x02 ACK: skipped without validating its relationship to the request.
- Type 0xFF NACK: skipped like an unknown non-diagnostic frame.
- Type 0x12 alive-check: not answered.
- Timeout at empty header: returned empty frames; subsequent socket reuse could read late replies and misattribute their timestamp or request.

## Build 208 changes
- `measuredDiagnostic` supplies exactly the request bytes and an alive-check writer on the same owning socket. No new reader or ECU diagnostic request.
- ACK 0x02 must be bounded to EdiabasLib's 13-byte maximum and its available echoed bytes must agree with the transmitted frame; otherwise fail-closed. Minimal 6-byte ACK has no correlation information; this limitation is explicit.
- NACK 0xFF means transaction failure, **no automatic blind resend**; current logger closes/reconnects via existing exception path.
- Alive-check type 0x12 with exactly two address bytes receives type 0x13 reply `00 00 00 02 00 13 00 F4`.
- No empty/partial response after ACK is reported as success. Missing diagnostic frame is failure; socket must not be reused after timeout.
- Bounded number of control frames; no new DIDs or rate increases.

## RAM 0x23 reusable reader extension\n- Updated the zero-copy `HsfzReusableReader` receive path to classify ACK/NACK, answer gateway alive-check via the same socket, and close/reconnect on uncertain failures. Existing RAM payload buffer is reused.\n- Added RAM-specific synthetic ACK, NACK and alive-check tests; there is no new RAM address or diagnostic request.\n\n## Limits and validation
- This fixes framing/transaction failure classification, **not** proof of source/target address validity for all ECU profiles nor a way to identify stale same-DID replies on an already desynchronized socket. After uncertain outcomes the correct policy remains socket retirement and new connection.
- Other direct transport readers outside the common codec and the RAM reusable reader remain outside this PR; on-device validation is required.
- Upstream supports additional control replies; OCTANE intentionally fails closed on unknown ones instead of inventing behavior.
- JVM synthetic fragmentation, ACK/NACK, keepalive and incomplete-response tests are software evidence only. On-car VXSCAN 30-minute/STOP-reconnect validation remains necessary.
- Beware that EdiabasLib uses independent asynchronous read and queueing. OCTANE intentionally keeps one socket owner, no concurrent input reader.

Rollback: revert this isolated PR to build 207, preserving all CSV and prior experimental commits. Keep PR draft until CI and hardware verification.
