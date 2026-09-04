# tools/harness/fuzz -- HTTP/2 conformance and protocol fuzz harness (gate G11)

This directory is the entry point for gate **G11** of the interceptor plan
(`vorflux-handoff/plans/v1-interceptor-architecture.md` §9): "H2/HPACK nghttp2
corpus and malformed WS/SSE/gRPC corpora: no crash, no desync". Its outcome
drives decision **D7** (in-house HTTP/2 codec vs. the `h2Netty` fallback flag);
see `vorflux-handoff/plans/decisions/D7-http2-conformance.md`.

## Running

```sh
tools/harness/fuzz/run.sh                          # CI budget (200k H2 + 50k framing iterations)
HTTP2_FUZZ_ITERATIONS=4000000 tools/harness/fuzz/run.sh   # long campaign
```

The script runs the harness test classes through `:app:testDebugUnitTest` and
prints a per-class summary parsed from the JUnit XML. Exit code is non-zero on
any failure. Extra arguments are passed to Gradle.

## What is covered

| Test class (`app/src/test/java/com/example/interception/http/`) | Coverage |
|---|---|
| `HpackConformanceTest` | Decodes every header block of the nghttp2 stories in the `http2jp/hpack-test-case` corpus (profiles `nghttp2`, `nghttp2-16384-4096`, `nghttp2-change-table-size`; 9,918 blocks) with one `HpackDecoder` per story and asserts byte-exact header lists and `H2Headers.error == null`. `header_table_size` cases exercise `HpackDecoder.setMaxHeaderTableSize`. |
| `Http2MalformedVectorsTest` | RFC 7541 Appendix C sanity vectors plus hand-written violations: index 0 / out-of-range index, integer overflow and truncation, string length past block, invalid Huffman padding / EOS, dynamic table size update above `SETTINGS_HEADER_TABLE_SIZE` or after a field, PADDED/PRIORITY faults, reserved stream-id bit, garbage frame types, odd SETTINGS payloads, invalid UTF-8. Each must be reported through `H2Headers.error` (partial entries kept) without an exception and without poisoning the decoder for the next block. |
| `Http2FuzzTest` | Deterministic (`Random(0x5EED_2023)`) mutation fuzzer. Seeds: corpus story_00 blocks, Appendix C vectors, 18 well-formed frames of every frame type. Ten mutators (bit flips, boundary bytes, truncation, insertion, duplication, length/flags/stream-id rewrites, continuation-byte storms). Every input runs through `Http2Parser.parseFrames`, `HpackDecoder.decodeHeaders`, `stripDataPadding`, `decodeSettings`, name helpers and a long-lived `Http2Session`; every 500 iterations a clean probe transaction must still produce exactly one `Http2Transaction` (desync check). A second test feeds random byte streams. |
| `ProtocolFramingFuzzTest` | Same approach for `WebSocketParser` + `WsMessageAssembler`, `GrpcDetector.parseMessages` + `ProtobufDecoder`, and `SseParser`, with a recovery assertion afterwards. |

## Corpus provenance

`app/src/test/resources/hpack-test-case/` contains the `nghttp2*` story
directories of https://github.com/http2jp/hpack-test-case (MIT licence, copy in
`app/src/test/resources/hpack-test-case/LICENSE`), gzipped per story. Only the
nghttp2 encoder profiles are vendored; they cover indexed/literal/Huffman
encodings, dynamic-table eviction, and table-size updates. Other encoder profiles
from the same repository can be dropped in alongside and picked up by adding a
row to `HpackConformanceTest`.

## Extending

* New malformed vector: add a test to `Http2MalformedVectorsTest` using the
  `hex()` helper; assert on `H2Headers.error` text and the surviving entries.
* New seed for the fuzzer: append to `Http2FuzzTest.seeds` (frames) or
  `ProtocolFramingFuzzTest.seeds`.
* Reproducing a fuzz failure: the assertion message prints the iteration index,
  the exception, the hex input and a stack excerpt; both fuzzers use fixed seeds,
  so re-running with the same `HTTP2_FUZZ_ITERATIONS` reproduces it.
* WS/SSE/gRPC *conformance* corpora (autobahn-style) are task 55 in the plan and
  are not part of this harness yet.
