#!/usr/bin/env bash
# tools/harness/fuzz/run.sh
#
# Gate G11 (plan v1 §9) -- HTTP/2 + HPACK conformance and protocol fuzzing.
#
# Runs the four JVM test classes that make up the conformance harness:
#   HpackConformanceTest      nghttp2 corpus (http2jp/hpack-test-case, 3 profiles,
#                             9,918 header blocks) must decode byte-exactly.
#   Http2MalformedVectorsTest hand-written RFC 7540/7541 violations must be
#                             reported via H2Headers.error without throwing.
#   Http2FuzzTest             mutation fuzzer over frames + header blocks feeding
#                             Http2Parser / HpackDecoder / Http2Session; asserts no
#                             escaped exception and no session desync (probe txn).
#   ProtocolFramingFuzzTest   same for WebSocket, gRPC framing, protobuf and SSE.
#
# Environment:
#   HTTP2_FUZZ_ITERATIONS   mutation budget for the fuzzers (default 200000 for
#                           Http2FuzzTest, one quarter of that for the framing
#                           fuzzer). CI uses the default; raise it locally for a
#                           longer campaign, e.g. HTTP2_FUZZ_ITERATIONS=4000000.
#
# The same classes run as part of the normal `:app:testDebugUnitTest` job in
# .github/workflows/ci.yml, so a regression fails CI even without this script;
# the script exists so the gate can be re-run (or extended) in isolation.

set -euo pipefail

cd "$(dirname "$0")/../../.."

echo "G11 harness: HTTP2_FUZZ_ITERATIONS=${HTTP2_FUZZ_ITERATIONS:-<default>}"

./gradlew --no-daemon --console=plain :app:testDebugUnitTest \
  --tests 'com.example.interception.http.HpackConformanceTest' \
  --tests 'com.example.interception.http.Http2MalformedVectorsTest' \
  --tests 'com.example.interception.http.Http2FuzzTest' \
  --tests 'com.example.interception.http.ProtocolFramingFuzzTest' \
  "$@"

RESULTS=app/build/test-results/testDebugUnitTest
python3 - "$RESULTS" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
total = failed = 0
for p in sorted(glob.glob(f"{sys.argv[1]}/TEST-com.example.interception.http.*.xml")):
    r = ET.parse(p).getroot()
    n = int(r.get("tests")); f = int(r.get("failures")) + int(r.get("errors"))
    total += n; failed += f
    print(f"  {r.get('name').split('.')[-1]:<28} tests={n} failed={f} time={r.get('time')}s")
    so = r.find("system-out")
    if so is not None and so.text:
        for line in so.text.strip().splitlines():
            if "iterations" in line:
                print(f"    {line.strip()}")
print(f"G11 harness: {total} tests, {failed} failed")
sys.exit(1 if failed or total == 0 else 0)
PY
