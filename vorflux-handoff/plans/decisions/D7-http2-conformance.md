# D7 -- HTTP/2 codec conformance decision (gate G11, S3 task 23)

**Status:** decided 2026-09-04 on branch `vorflux/interceptor-s3-protocol`.
**Decision:** the in-house `Http2Parser` / `HpackDecoder` / `Http2Session` stay. The `h2Netty`
contingency flag is **not armed**. Task 57 (termination and translation, gate G27) builds on the
in-house codec.

Plan references: `v1-interceptor-architecture.md` D7 (§1 decisions table), §6 flags (`h2Netty`
"armed only if G11 fails"), §7 task 23, §9 gate G11.

## Gate G11 evidence

Harness: `tools/harness/fuzz/run.sh` (see `tools/harness/fuzz/README.md`); all classes also run in
the normal `:app:testDebugUnitTest` CI job.

| Check | Result |
|---|---|
| nghttp2 HPACK corpus (`http2jp/hpack-test-case`, profiles `nghttp2`, `nghttp2-16384-4096`, `nghttp2-change-table-size`; 32 + 31 + 31 stories) | **9,918 / 9,918 header blocks decode byte-exactly** (3,384 + 3,267 + 3,267), one decoder per story, `header_table_size` honoured, no `H2Headers.error` -- `HpackConformanceTest`, 4 tests, 0.34 s |
| Malformed HPACK / frame vectors | **16 / 16 reported through `H2Headers.error` without an exception**; entries decoded before the fault are kept; the decoder is not poisoned for the next block -- `Http2MalformedVectorsTest` |
| Frame + header-block mutation fuzz (`Http2FuzzTest`, seed `0x5EED_2023`, 10 mutators, long-lived `Http2Session`, probe transaction every 500 iterations) | 200k iterations (CI default): 0 escaped exceptions, every probe produced exactly one `Http2Transaction` (no desync). Long campaigns: **3,000,000** and **4,000,000** iterations, 0 failures (4.8 s / 6.4 s) |
| Random byte streams (75 % frame-shaped) | 50k / 1M iterations, 0 escaped exceptions |
| WS / gRPC framing / protobuf / SSE mutation fuzz (`ProtocolFramingFuzzTest`) | 50k (CI) and **1,000,000** iterations, 0 escaped exceptions, well-formed input decodes exactly after the campaign |
| mitmproxy side-by-side comparator | **not run** -- device-lane check (D5); scheduled with the task 57 / G27 verification on the physical device lane |

## Defects found and fixed while closing the gate

The corpus run was clean from the start; every fix below came from the malformed vectors or the
fuzzers.

`interception/http/Http2Parser.kt` (`HpackDecoder`)
- Integers (RFC 7541 §5.1) are accumulated in a `Long`, capped at `2^28 - 1`, and limited to five
  continuation octets; truncated or overflowing integers are reported instead of wrapping.
- Indexed representations are validated: index 0 and indices beyond static + dynamic table size
  are errors (`"invalid index N"`); the old `"(idxN)"` placeholder path is gone.
- String literals: bad length, length past the block end, or a failed strict Huffman decode are
  errors (`"bad string literal"`).
- Dynamic table size update rules: only allowed at the start of a block (RFC 7541 §4.2) and never
  above the advertised `SETTINGS_HEADER_TABLE_SIZE` (`setMaxHeaderTableSize`, shrink evicts
  immediately).
- PADDED / PRIORITY flag handling: empty payload, padding longer than the payload, or fewer than
  5 priority octets are reported.
- `parseFrames` masks the reserved stream-identifier bit (RFC 9113 §4.1).
- `H2Headers` gained `error: String?` -- the first RFC 7541 violation seen in the block. Task 57
  must answer a non-null `error` with `GOAWAY(COMPRESSION_ERROR)`; today `Http2Session` (observe
  only) counts it in `hpackErrorCount` and logs it while keeping the partial entries.

`interception/http/HpackHuffman.kt`
- `decodeStrict` rejects invalid paths, EOS in the stream, padding longer than 7 bits and padding
  that is not all ones (RFC 7541 §5.2). `decode` stays lenient for `quic/QpackDecoder`.
- Decoded octets are accumulated as bytes and converted with strict UTF-8, falling back to
  ISO-8859-1 -- the old per-symbol `toChar()` mangled any non-ASCII header value.

`interception/http/WebSocketParser.kt`
- 64-bit payload lengths with the RFC 6455 §5.2 reserved MSB set (or above `Int.MAX_VALUE`) were
  coerced to a negative `Int` and crashed `copyOfRange`; they are now treated as an incomplete
  frame. Found by `ProtocolFramingFuzzTest` on iteration 1,536.

## Why keep the in-house codec

- Byte-exact on the whole nghttp2 corpus, including table-size-change and 16 KiB / 4 KiB table
  profiles, so encoder interoperability is not a concern for the decode side.
- Every malformed input class we could construct is reported, not thrown, and the session stays in
  sync afterwards -- exactly the behaviour task 57 needs to emit `GOAWAY` / `RST_STREAM` instead
  of dropping the connection.
- Swapping to Netty `codec-http2` would add a ~2 MB dependency to the APK, a second threading
  model inside `MitmProxyEngine`, and a translation layer back into `H2Frame` -- cost without a
  demonstrated defect to pay for.

## Contingency (kept, not armed)

`h2Netty` is **not declared** in `InterceptorFlags` -- §6 says it is "armed only if G11 fails",
and the plan forbids flags without finished work behind them. Arming trigger: a G27 or device-lane
finding that the in-house codec mis-decodes or desyncs against real servers that the harness
cannot reproduce; at that point the flag is added together with the Netty client leg.
Re-running the gate: `HTTP2_FUZZ_ITERATIONS=4000000 tools/harness/fuzz/run.sh`.

## Known limits / follow-ups

- HPACK *encoder* conformance is not tested here; task 57 introduces the encoder for the
  downstream leg and must add the round-trip (`encode -> nghttp2-style decode`) check to the same
  harness.
- WS / SSE / gRPC *conformance* corpora (autobahn-style) are task 55; this gate only covers crash
  and recovery behaviour of those parsers.
- mitmproxy comparator pending the device lane.
