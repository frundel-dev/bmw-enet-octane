# BMW OCTANE RAM LAB 2 — bounded UDS 0x22 batch benchmark

## Goal

Compare the elapsed duration of **two sequential known UDS ReadDataByIdentifier** transactions (DID 4A37 knock + DID 4A49 ignition) with **one combined UDS 0x22** request containing the same two DIDs. The experiment tests whether this DME accepts this specific combined request and whether it reduces round-trip overhead.

**NOT RAM logging**: no memory addresses, 0x23 ReadMemoryByAddress, 0x2C dynamically defined DID, diagnostic session change, security access, patch, flashing, routine control, or coding.

## Architecture and operating limits

- Derived from independently verified RAM LAB 1 branch `experiment/ram-lab-readonly-v1`.
- Separate Android package `com.example.bmwenettest.ramlab2`, version `1.7.32-RAM-LAB2`; main and RAM LAB 1 are not replaced.
- Shared `DmeHistoryReader` parked-only ENET/VXSCAN transport: one owned TCP socket, existing HSFZ stream frame validation and response-to-request matching. No parallel socket reads.
- Entire run bounded to **24 pairs**, **30 seconds overall deadline** (checked at request boundaries), and the transport's existing request read timeout. Fails closed on mismatched, short, extra, reordered, truncated or rejected responses.
- Each pair contains 4A37 and 4A49 single-DID requests plus one 4A37+4A49 combined read; execution order alternates to reduce order bias.
- Strict positive response layouts: `62 4A37 [4 bytes]`, `62 4A49 [2 bytes]`, or `62 4A37 [4 bytes] 4A49 [2 bytes]`. Other responses do not count as measurements.
- On a batch rejection, stop instead of repeatedly probing. On any I/O exception, stop, close connection and preserve a partial CSV.
- No automated retries beyond existing bounded HSFZ/UDS handling. No dynamic parameter discovery.
- Do not run concurrently with **any** BMW OCTANE instance, MG Flasher, RAM LAB 1 or other diagnostic application; cross-application exclusivity cannot be enforced by the Android process-local guard.
- Read-only does not mean zero ECU load. Only test parked, never while driving.

## Conduct the experiment

1. Install the RAM LAB 2 APK separately.
2. Close/stop **all** other diagnostic tools and every LOGGER. Keep vehicle stationary, ignition available; engine may idle consistently if required for live values.
3. Connect to ENET/VXSCAN, open **⋮ → RAM LAB 2 • тест пакетного опроса → Начать**.
4. The experiment saves `bmw_ram_batch_v2_<timestamp>.csv` with wall-clock time, iteration, request order, requested DIDs, elapsed transaction milliseconds, status, exact request/response bytes, transport and gateway IP.
5. Tap **Сохранить CSV** at completion (or a partial/error result), save to a user-accessible folder and upload for analysis.
6. Inspect raw replies before any claim about batch availability or high-speed logging.

## Interpreting results

- Each successful iteration yields one *paired* `single_ms = knock_ms + ignition_ms` and `batch_ms`.
- Output summarizes medians and nearest-rank p95 for successful complete pairs; with only 24 pairs these statistics are preliminary and are not a statement of sustained continuous acquisition rate.
- Processing, persistence, request scheduling and the actual ECU response times are not isolated in these wall-clock request-to-response durations; order alternates but temporal drift remains possible.
- Rejections and interrupted experiments produce **partial** CSV and no speedup claim. Do not silently count rejected cycles.
- A successful 0x22 multi-DID read **does not prove support for UDS 0x2C, direct RAM access, or Custom Patch**.

## Checks and rollback

CI: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`. Unit tests cover allowlisted request bytes, batch response ordering and lengths, positive/negative SID, sample order and median/p95 arithmetic.

To revert: uninstall RAM LAB 2; main and RAM LAB 1 are unchanged. Do not merge this experiment into main without vehicle validation and independent review.
