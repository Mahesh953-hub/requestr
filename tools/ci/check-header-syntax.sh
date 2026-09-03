#!/usr/bin/env bash
# tools/ci/check-header-syntax.sh
#
# Compiles the public C/C++ FFI header for the native tun2proxy bridge with
# a syntax-only pass, in both C and C++ mode. This is the header consumed
# by any native (non-JNI) embedder of the bridge's C ABI; a header that
# fails to parse as either valid C or valid C++ would silently break every
# such consumer, with no other check in this repository catching it (the
# Rust build itself never parses this header -- it is generated/maintained
# by hand alongside the `#[unsafe(no_mangle)]` FFI functions it declares).
#
# Checks:
#   1. `cc -fsyntax-only -x c` on the header (C mode)
#   2. `c++ -fsyntax-only -x c++` on the header (C++ mode)
#
# Neither check produces any object file or binary -- both are parse/type
# checks only (`-fsyntax-only`), so this is fast and has no build side effects.

set -euo pipefail

HEADER="native/tun2proxy-bridge/include/requestr_tun2proxy.h"

if [ ! -f "$HEADER" ]; then
  echo "FAIL: $HEADER not found." >&2
  exit 1
fi

echo "Checking C syntax: $HEADER"
cc -fsyntax-only -x c "$HEADER"

echo "Checking C++ syntax: $HEADER"
c++ -fsyntax-only -x c++ "$HEADER"

echo "Header syntax OK (C and C++): $HEADER"
