# Final S0 retest artifact map

This directory is reserved for the final retest cycle. No final verification command has run yet.

Host compilation/behavior proof:
- fresh Kotlin compile
- focused S0 JVM tests and all app/cli/sdk/sdk-noop JVM suites
- migrations v8→v18
- lint and debug/release assembly
- host Rust fmt/check/test
- Android-target Rust compile
- schema, vendor, diff, and production-wiring source audits

Device-runtime proof (not equivalent to host compilation):
- Android instrumentation compilation
- connected Android instrumentation execution for bundled SQLite FTS5/JSON1, Room callback behavior, WAL, migration behavior, and VpnService protect()

Each command's stdout/stderr and XML summaries will be written here after the explicit execute instruction.
