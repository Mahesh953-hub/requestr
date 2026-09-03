#!/usr/bin/env bash
# tools/ci/check-vendor-integrity.sh
#
# Validates the provenance and integrity of the vendored tun2proxy fork
# under native/tun2proxy-bridge/vendor/tun2proxy.
#
# Checks:
#   1. vendor/README.md exists and records the upstream crate checksum
#   2. A SHA-256 manifest of every vendored file is computed and compared
#      against the committed manifest (tools/ci/vendor-sha256.manifest)
#   3. If no manifest exists yet, it is generated and the script fails
#      with instructions to commit it
#
# This catches accidental or unrecorded edits to vendored upstream code.
# Intentional patches should be documented in vendor/README.md and the
# manifest regenerated with: bash tools/ci/check-vendor-integrity.sh --update

set -euo pipefail

VENDOR_DIR="native/tun2proxy-bridge/vendor/tun2proxy"
README="native/tun2proxy-bridge/vendor/README.md"
MANIFEST="tools/ci/vendor-sha256.manifest"

if [ ! -d "$VENDOR_DIR" ]; then
  echo "SKIP: $VENDOR_DIR does not exist yet (vendor not populated)."
  exit 0
fi

# ---- README presence ----
if [ ! -f "$README" ]; then
  echo "ERROR: $README missing. The vendored fork must document provenance." >&2
  exit 1
fi

echo "Vendor README present: $README"

# ---- Compute current manifest ----
# Deterministic: sorted file list, sha256sum of each file.
CURRENT=$(find "$VENDOR_DIR" -type f | LC_ALL=C sort | xargs sha256sum)

if [ "${1:-}" = "--update" ]; then
  mkdir -p "$(dirname "$MANIFEST")"
  echo "$CURRENT" > "$MANIFEST"
  echo "Manifest updated: $MANIFEST ($(echo "$CURRENT" | wc -l) files)"
  exit 0
fi

# ---- Compare against committed manifest ----
if [ ! -f "$MANIFEST" ]; then
  echo ""
  echo "No vendor manifest found at $MANIFEST."
  echo "Generating initial manifest..."
  mkdir -p "$(dirname "$MANIFEST")"
  echo "$CURRENT" > "$MANIFEST"
  echo ""
  echo "Created $MANIFEST with $(echo "$CURRENT" | wc -l) file hashes."
  echo "Commit this file so future CI runs can detect vendor drift."
  echo ""
  echo "To regenerate after intentional vendor changes:"
  echo "  bash tools/ci/check-vendor-integrity.sh --update"
  exit 1
fi

COMMITTED=$(cat "$MANIFEST")

if [ "$CURRENT" = "$COMMITTED" ]; then
  file_count=$(echo "$CURRENT" | wc -l)
  echo "Vendor integrity OK: $file_count files match committed manifest."
  exit 0
fi

# ---- Drift detected ----
echo ""
echo "VENDOR DRIFT DETECTED — vendored files differ from committed manifest:"
echo ""
diff <(echo "$COMMITTED") <(echo "$CURRENT") || true
echo ""
echo "If the changes are intentional patches:"
echo "  1. Document the patch in $README"
echo "  2. Regenerate the manifest: bash tools/ci/check-vendor-integrity.sh --update"
echo "  3. Commit both files"
exit 1
