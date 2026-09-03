#!/usr/bin/env bash
# tools/ci/check-schema-drift.sh
#
# Room exported-schema drift check for CI.
#
# This script must run AFTER a Gradle task that triggers KSP (e.g.
# testDebugUnitTest or assembleDebug), because KSP regenerates the
# schema JSON files under app/schemas/ as a side effect of compilation.
#
# The check is simple and deterministic: compare the KSP-regenerated
# schemas against the committed versions using git diff. If they
# differ, someone changed a Room entity or DAO without re-exporting
# and committing the updated schema.
#
# This script never intentionally mutates tracked files — it only
# reads the diff state that KSP's own compilation pass left behind.

set -euo pipefail

SCHEMA_DIR="app/schemas"

if [ ! -d "$SCHEMA_DIR" ]; then
  echo "ERROR: $SCHEMA_DIR not found. Run from the repository root." >&2
  exit 1
fi

# ---- Structural validation ----
# Verify every schema JSON is well-formed, has a non-empty identityHash,
# and that the version inside the JSON matches the filename.

echo "Validating schema structure..."
python3 - "$SCHEMA_DIR" <<'PYEOF'
import json, glob, os, sys

schema_dir = sys.argv[1]
pattern = os.path.join(schema_dir, "**", "*.json")
files = sorted(glob.glob(pattern, recursive=True))

if not files:
    print(f"ERROR: no schema JSON files found in {schema_dir}", file=sys.stderr)
    sys.exit(1)

errors = 0
versions_seen = {}

for f in files:
    basename = os.path.basename(f)
    try:
        expected_version = int(basename.replace(".json", ""))
    except ValueError:
        print(f"  WARN: {f} does not match <version>.json naming", file=sys.stderr)
        continue

    try:
        with open(f) as fh:
            data = json.load(fh)
    except json.JSONDecodeError as e:
        print(f"  ERROR: {f} is not valid JSON: {e}", file=sys.stderr)
        errors += 1
        continue

    db = data.get("database", {})
    version = db.get("version")
    identity_hash = db.get("identityHash", "")

    if version != expected_version:
        print(f"  ERROR: {f} declares version {version} but filename says {expected_version}", file=sys.stderr)
        errors += 1

    if not identity_hash:
        print(f"  ERROR: {f} (v{version}) has empty identityHash", file=sys.stderr)
        errors += 1

    if version in versions_seen:
        print(f"  ERROR: duplicate version {version} in {f} and {versions_seen[version]}", file=sys.stderr)
        errors += 1
    versions_seen[version] = f

    print(f"  v{version}: identityHash={identity_hash} OK")

if errors > 0:
    print(f"\n{errors} schema validation error(s).", file=sys.stderr)
    sys.exit(1)

print(f"\nAll {len(files)} schema files structurally valid.")
PYEOF

if [ $? -ne 0 ]; then
  exit 1
fi

# ---- Git-diff drift detection ----
# After KSP ran during compilation, check whether the schema files
# differ from the committed versions. A non-empty diff means the
# committed schemas are stale.

echo ""
echo "Checking for schema drift (git diff)..."

if ! git diff --quiet -- "$SCHEMA_DIR"; then
  echo ""
  echo "SCHEMA DRIFT DETECTED — committed schemas differ from KSP output:"
  echo ""
  git diff --stat -- "$SCHEMA_DIR"
  echo ""
  git diff -- "$SCHEMA_DIR"
  echo ""
  echo "Fix: run './gradlew :app:kspDebugKotlin' locally and commit the"
  echo "regenerated schema files."
  exit 1
fi

# Also check for untracked schema files (new version added but not committed).
untracked=$(git ls-files --others --exclude-standard -- "$SCHEMA_DIR")
if [ -n "$untracked" ]; then
  echo ""
  echo "SCHEMA DRIFT DETECTED — untracked schema files found:"
  echo "$untracked"
  echo ""
  echo "Fix: git add the new schema files and commit them."
  exit 1
fi

echo "No schema drift detected. Committed schemas match KSP output."
exit 0
