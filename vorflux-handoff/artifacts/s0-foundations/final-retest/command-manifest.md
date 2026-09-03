# S0 final retest command manifest — DO NOT RUN UNTIL EXECUTE AUTHORIZATION

All resource-intensive Gradle commands run sequentially and write their complete output to this directory.

## Host build and JVM proof
1. Clean stale outputs as needed and compile fresh: `:app:clean :app:compileDebugKotlin`.
2. Targeted JVM: bridge/event/config, `interception.net`, database builder/backup.
3. All v8→v18 migration classes (seven files currently present; verify whether owners add the three required by the plan before run).
4. Full JVM suites: `:app:testDebugUnitTest`, `:cli:test`, `:sdk:testDebugUnitTest`, `:sdk-noop:testDebugUnitTest`.
5. `:app:compileDebugAndroidTestKotlin` confirms instrumentation sources compile but does not prove device runtime.
6. `:app:lintDebug`, then `:app:assembleDebug`, then `:app:assembleRelease`.

## Native proof
1. `$HOME/.cargo/bin/cargo fmt --all -- --check` (maintained bridge scope as CI defines if vendor baseline is intentionally excluded).
2. `cargo check --lib --tests`; `cargo test --lib`.
3. Android aarch64 target `cargo check` using NDK 27 LLVM linker/AR and target `aarch64-linux-android`.

## Source/integrity proof
1. `bash tools/ci/check-schema-drift.sh` after KSP-capable compilation.
2. `bash tools/ci/check-vendor-integrity.sh`.
3. `git diff --check`.
4. Production-wiring audit: active VPN → canonical config → `Tun2ProxyBridge.start` → JNI event sink/event supervisor; SocketProtector lifecycle/egress paths; DB driver flag → driver-specific callback, WAL, backup/restore paths.

## Android device runtime proof — separate from host compilation
If Redroid is provisioned, run connected instrumentation tests (including S0 FTS5/JSON1/callback/WAL/migration tests once present and VpnService socket protection). This demonstrates Android-native bundled SQLite and framework `VpnService.protect`; it is not inferred from JVM tests or `compileDebugAndroidTestKotlin`.
