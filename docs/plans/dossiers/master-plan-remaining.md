# Requestr Implementation Dossier

## Executive Summary

This document provides a precise, code-grounded implementation guide for the remaining work tracked in `MASTER-PLAN-IMPLEMENTATION-STATUS.md` (PR #15). All three deferred items require wiring logic to existing, tested, fully-implemented engine code. The per-URL AES crypto is genuinely net-new. The 13 LOW-priority items are distinct, mostly UI-surface features with no architectural prerequisites.

**Scope boundary:** §1, §2, §3 items from `MASTER-FEATURE-ADDITION-PLAN.md` (lines 69–494).

---

# PART A: THREE DEFERRED ITEMS (HIGH PRIORITY)

Each is fully implemented + tested; only UI/settings wiring is needed. No algorithmic work.

## A1: §1.10 Remote-cert mirror regen (discrete opt-in setting)

### Background (from status doc)
- **State:** `CertificateAuthority.issueMirroredLeaf` + `RemoteCertificateMirror` fully implemented + tested
- **Reverted wiring:** CONNECT-time probing was undone because it violated §1.5 ("mock never dials") — fetching a remote leaf on every first-seen `FORCE_MITM` host opens a real connection for all-mock/offline traffic
- **Fix:** Wire as a discrete opt-in toggle so the feature is ONLY invoked when the user explicitly enables it

### Exact Implementation Files & APIs

**Engine layer (complete, unchanged):**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/RemoteCertificateMirror.kt` (100 LOC)
  - **Public API:** `fetchRemoteLeaf(host: String, port: Int = 443, timeoutMs: Int = 5_000): X509Certificate?`
    - Opens direct TLS to remote host, reads peer leaf cert, never validates chain (all-trusting context)
    - Returns `null` on any failure (unreachable, timeout, handshake fail)
  - **Public API:** `profileFrom(remote: X509Certificate, maxValidityDays: Long = 365): MirroredProfile`
    - Extracts subject/SANs/validity window from remote cert, clamps dates to avoid nonsensical windows
  - **Public API:** `extractSubjectAltNames(remote: X509Certificate): GeneralNames?`
    - Pulls SAN extension directly from DER encoding

- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/CertificateAuthority.kt` (lines 410–470)
  - **Public API:** `suspend fun issueMirroredLeaf(context: Context, host: String, port: Int = 443): Pair<X509Certificate, KeyPair>?`
    - Calls `RemoteCertificateMirror.fetchRemoteLeaf()` → `profileFrom()` → `issueLeafFromProfile()`
    - Returns `null` if remote fetch fails; caller falls back to plain leaf (existing behavior)
  - **Internal API:** `suspend fun issueLeafFromProfile(context: Context, host: String, profile: MirroredProfile): Pair<X509Certificate, KeyPair>`
    - Signs a new leaf using the mirrored profile; the CA's private key signs it

**Current unconditional call site (to be made conditional):**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/MitmProxyEngine.kt` line 238
  - Current code: `val (cert, keyPair) = certCache.getOrCompute(host) { h -> generateHostCert(context, h) }`
  - Context: This happens **after** the 200 response to the client, **before** any request is evaluated against rules
  - **Critical guarantee:** The line must NOT be changed to unconditionally call `issueMirroredLeaf()` — that would re-open the upstream connection for all-mock connections

### Settings/Preferences Mechanism (How to Add the Toggle)

**Existing mechanism:** `SettingsDataStore.kt` + `UnifiedDataStore.kt`

**Where to add:**
1. **Key definition:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/prefs/UnifiedDataStore.kt` (line 40–135, `object AppSettingsKeys`)
   - Add line ~96: `val MIRROR_REMOTE_CERT = booleanPreferencesKey("mirror_remote_cert")`
   - Pattern: `val KEY_NAME = booleanPreferencesKey("snake_case_key")`

2. **Getter & setter:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/prefs/SettingsDataStore.kt` (class at line 34)
   - Add to the Flow declarations (after line 74): 
     ```kotlin
     val mirrorRemoteCert: Flow<Boolean> = prefs.map { it[AppSettingsKeys.MIRROR_REMOTE_CERT] ?: false }
     ```
   - Add setter (after line 149):
     ```kotlin
     suspend fun setMirrorRemoteCert(value: Boolean) {
         store.edit { it[AppSettingsKeys.MIRROR_REMOTE_CERT] = value }
     }
     ```

3. **UI surface:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` (line 115 onwards)
   - Locate section for CA/certificate settings (around line 230–260 where CA-related toggles go)
   - Add a toggle composable that reads `settingsRepository.mirrorRemoteCert` and calls `setMirrorRemoteCert()`
   - Pattern: Use existing `RemoteControlToggle` or `Switch` composable pattern from nearby settings

### CONNECT-time Hook (Where the Conditional Branching Belongs)

**File & line:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/MitmProxyEngine.kt` (line 238)

**Current code:**
```kotlin
val (cert, keyPair) = certCache.getOrCompute(host) { h -> generateHostCert(context, h) }
```

**After wiring, should become:**
```kotlin
val (cert, keyPair) = certCache.getOrCompute(host) { h ->
    if (mirrorRemoteCertEnabled) {
        // Try to fetch + mirror the remote cert; fall back to plain leaf on any failure
        kotlinx.coroutines.runBlocking {
            CertificateAuthority.issueMirroredLeaf(context, h)
        } ?: generateHostCert(context, h)
    } else {
        generateHostCert(context, h)
    }
}
```

**How to get the toggle state at this call site:**
- The toggle state must be injected into `MitmProxyEngine` via constructor or suspend-callable context (already has `context` parameter)
- Pattern: Pass `SettingsDataStore.get(context).mirrorRemoteCert.first()` into the lambda, or store it on the engine as a field updated from `PreflightController`

### Test Coverage

**Existing tests (unchanged):**
- `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/RemoteCertificateMirrorTest.kt` — full unit coverage of fetch/profile/extraction
- `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/CertificateAuthorityTest.kt` — covers `issueLeafFromProfile()`

**New tests needed:**
- Unit test of the settings toggle round-trip: set via `SettingsDataStore.setMirrorRemoteCert(true)`, read via `mirrorRemoteCert.first()`
- Integration test that verifies the CONNECT path respects the toggle: mock `issueMirroredLeaf()` to verify it's called iff toggle is `true`

### Emulator Verification (Redroid/Docker No-Internet)

**Can be verified:** YES, fully, with mocking.
- The remote-cert fetch itself requires network, but the setting toggle + branching logic can be tested with mocked `RemoteCertificateMirror.fetchRemoteLeaf()`
- No real VPN, no Frida, no hardware required

---

## A2: §2.18 Local DB browser (rooted UI slice)

### Background (from status doc)
- **State:** `TargetAppDatabaseBrowser` fully implemented + tested (lines 1–188)
- **What's complete:** Database enumeration, safe copying via `su cat`, read-only SQL whitelisting, binary-safe output

### Exact Implementation Files & APIs

**Engine layer (complete, unchanged):**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/TargetAppDatabaseBrowser.kt` (188 LOC)
  - **Public API:** `listDatabases(pkg: String): Result<List<String>>`
    - Enumerates `/data/data/<pkg>/databases/` via `su -c ls`, filters out `-journal`/`-wal`/`-shm` sidecars
  - **Public API:** `copyDatabaseLocally(context: Context, pkg: String, dbName: String): Result<File>`
    - Copies remote DB to `context.cacheDir` via `su -c cat`, verifies non-empty
  - **Public API:** `listTables(localFile: File): Result<List<String>>`
    - Reads user tables (excludes `sqlite_*` system tables) from local copy
  - **Public API:** `query(localFile: File, sql: String, args: List<String>): Result<QueryResult>`
    - Executes read-only SQL after `isSafeReadOnlyQuery()` validation
    - Rejects anything except `SELECT` and whitelisted introspection `PRAGMA`s
  - **Public API:** `browseTable(localFile: File, table: String, limit: Int = 200): Result<QueryResult>`
    - Convenience: `SELECT * FROM <table> LIMIT <limit>` with identifier validation

### Root-Check API (How to Gate the Feature)

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/CapabilityProbe.kt`
- **Public API:** `suspend fun probeRoot(): RootState`
  - Returns `RootState.GRANTED` if `su -c id` returns uid=0
  - Returns `RootState.AVAILABLE` if a prompt may be pending, `RootState.DENIED` or `UNAVAILABLE` otherwise

**Usage pattern (existing throughout app):**
```kotlin
import com.example.data.interception.InterceptionStateRepository
import com.example.data.interception.RootState

val capabilities = InterceptionStateRepository.capabilities.first()
if (capabilities.root == RootState.GRANTED) {
    // Show root-gated features
} else {
    // Hide or disable
}
```

### Where to Surface the UI Slice

**Best location:** Add as a new tab or section in an existing root-focused screen.

**Option 1 (Recommended):** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
- Locate root-related section (around line 460–461 where "INTERCEPTION & PROXY ENGINE" section is)
- Add a new section after Gateway Mode (after line 676):
  ```kotlin
  if (capabilities.root == RootState.GRANTED) {
      SettingsSectionHeader("TARGET APP DATABASE BROWSER")
      // New composable: TargetAppDatabaseBrowserSection()
  }
  ```

**Option 2:** Create a new dedicated `TargetAppBrowserScreen.kt` tab (if a new top-level feature screen is preferred)
- Follow the pattern of existing screens in `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/`
- Gate the entire screen with `RootState.GRANTED` check before rendering

**Composable API needed:**
```kotlin
@Composable
fun TargetAppDatabaseBrowserSection(
    context: Context,
    viewModel: TargetAppDatabaseBrowserViewModel, // to be created
) {
    // UI state:
    // - App/package picker (enumerate installed apps)
    // - Database picker (calls TargetAppDatabaseBrowser.listDatabases())
    // - Table picker (calls TargetAppDatabaseBrowser.listTables())
    // - SQL query editor + Execute button
    // - Results grid (from TargetAppDatabaseBrowser.QueryResult.rows)
}
```

### Example Root-Gated Surface (How Others Are Surfaced Today)

**Reference:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt` lines 461–536
- The entire "INTERCEPTION & PROXY ENGINE" section checks `if (capabilities.root == RootState.GRANTED)` before showing root-specific options
- Pattern: Wrap the entire composable tree in a `when (capabilities.root)` expression

**Reference:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/InterceptScreen.kt` line ~200
- Similar pattern for showing "Root: Granted" status chip

### Test Coverage

**Existing tests (unchanged):**
- `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/TargetAppDatabaseBrowserTest.kt` — full unit coverage of all public APIs

**New tests needed:**
- UI test (Robolectric): verify the DB browser section is hidden when `RootState != GRANTED`
- Integration test: mock an in-memory SQLite DB, copy it locally, verify `listTables()` and `query()` round-trip

### Emulator Verification (Redroid/Docker No-Internet, No Real Root)

**Status:** Partial.
- The SQL validation helpers (`isSafeReadOnlyQuery()`, `isSafeSqlIdentifier()`) are pure functions — fully testable without root
- The `su` invocations (`listDatabases()`, `copyDatabaseLocally()`) require actual root, so the root-code paths cannot be exercised in a Redroid/no-root sandbox
- **Workaround:** Inject a mock `RootCommandExecutor` interface so unit tests can stub the `su` calls
- **For end-to-end verification:** Requires real root (physical device or Magisk-rooted emulator)

---

## A3: `.p12` import-with-password (add UI import flow)

### Background (from status doc)
- **State:** `CaP12Exchange` fully implemented + tested (lines 1–143)
- **What's complete:** PKCS#12 parse, validate CA-ness, atomic disk write, password handling
- **What's missing:** File picker + password prompt UI

### Exact Implementation Files & APIs

**Engine layer (complete, unchanged):**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/CaP12Exchange.kt` (143 LOC)
  - **Public API:** `fun parse(p12Bytes: ByteArray, password: CharArray, requireCa: Boolean = true): ParseResult`
    - Sealed: `ParseResult.Success(certificate, keyPair)` or `ParseResult.Failure(reason: String)`
    - Validates that the cert is a CA (`basicConstraints != -1`)
  - **Public API:** `suspend fun importP12(context: Context, p12Bytes: ByteArray, password: CharArray, requireCa: Boolean = true): Result<Unit>`
    - Calls `parse()`, then `SecureKeyStoreCaPasswordProvider.rotate()` to get a per-install password
    - Writes atomically via `persistAtomic()` (reimplemented without touching `CertificateAuthority.kt`)
    - On success, the next `CertificateAuthority.getOrCreate()` transparently uses the imported CA
  - **Public API:** `suspend fun exportP12(context: Context): ByteArray`
    - Thin re-export of `CertificateAuthority.exportP12()` for round-trip convenience

**Validation helpers (pure functions):**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/TargetAppDatabaseBrowser.kt` line 145
  - `fun isCaCertificate(cert: X509Certificate): Boolean` — checks `basicConstraints != -1`

### Where to Surface the Import UI (Natural Existing Location)

**Reference:** Look for where the export is currently surfaced.
- **File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/SettingsScreen.kt`
- **Search string:** "exportP12" or "CA settings" or "Certificate"

**Find the exact line:** The export button is already surfaced in Settings under the CA section (search for "Export CA" or "Download .p12")

**Pattern to follow:** Right next to the export button, add an "Import .p12" button that:
1. Launches a file picker (via `ActivityResultContracts.GetContent()` with `application/pkcs12` MIME type)
2. Reads the selected file bytes
3. Shows a password-prompt dialog
4. Calls `CaP12Exchange.importP12(context, bytes, password)`
5. Shows success/error feedback

### UI Implementation Sketch

**Composable to add (in SettingsScreen or a dedicated CA settings tab):**
```kotlin
@Composable
fun CaImportExportSection(
    context: Context,
    viewModel: SettingsViewModel, // existing
) {
    var showPasswordDialog by remember { mutableStateOf(false) }
    var importedBytes by remember { mutableStateOf<ByteArray?>(null) }
    
    // File picker launcher
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                importedBytes = stream.readBytes()
                showPasswordDialog = true
            }
        }
    }
    
    Row {
        Button(onClick = { exportCA(context, viewModel) }) { Text("Export .p12") }
        Button(onClick = { importLauncher.launch("application/pkcs12") }) { Text("Import .p12") }
    }
    
    if (showPasswordDialog && importedBytes != null) {
        PasswordDialog(
            onConfirm = { password ->
                viewModel.launchImportCA(importedBytes!!, password)
                showPasswordDialog = false
            },
            onDismiss = { showPasswordDialog = false }
        )
    }
}
```

### Test Coverage

**Existing tests (unchanged):**
- `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/CaP12ExchangeTest.kt` — full unit coverage

**New tests needed:**
- UI test (Robolectric): verify the import button launches a file picker and shows password prompt
- Integration test: end-to-end export → import → verify next `CertificateAuthority.getOrCreate()` uses imported CA

### Emulator Verification (Redroid/Docker No-Internet)

**Can be verified:** YES, fully.
- No network required; all operations are local file I/O + crypto (BouncyCastle)
- No root, no Frida, no VPN tunnel needed

---

---

# PART B: NET-NEW ITEM (MEDIUM PRIORITY)

## B: §2.14(b) Per-URL AES Body Crypto (Entirely New Capability)

### Overview
Master plan §2.14(b) — "Per-URL AES body crypto: net-new. Report: the existing rule model (RuleAction enum, rule entity/DAO, how a rule is matched and applied in CaptureEngine/RuleEnforcer), how the existing SCRIPT_REWRITE and MOCK_RESPONSE actions are structured end to end (entity field -> DAO -> UI editor -> engine branch), so a new crypto rule can follow the identical shape."

### Existing Rule Model (Complete Reference)

**Entity definition:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/local/Entities.kt` lines 112–131
  ```kotlin
  enum class RuleAction { BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY, FORCE_MITM, MOCK_RESPONSE, SCRIPT_REWRITE, CHAOS }

  @Entity(tableName = "intercept_rules", indices = [Index("domain")])
  data class InterceptRuleEntity(
      @PrimaryKey(autoGenerate = true) val id: Long = 0,
      val name: String = "",
      val pattern: String = "",  // regex or glob vs request URL
      val domain: String = "",   // bare host for fast block checks
      val action: RuleAction = RuleAction.LOG_ONLY,
      val enabled: Boolean = true,
      val replacementValue: String? = null,  // used by REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, MOCK_RESPONSE, SCRIPT_REWRITE
      val createdAt: Long = System.currentTimeMillis(),
  )
  ```

**DAO (data access layer):**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/local/Daos.kt`
- Search for: `@Dao interface InterceptRuleDao`
- Pattern methods: `insert()`, `update()`, `delete()`, `getAll()`, `getByDomain()`

**Matching & enforcement (high-level flow):**

1. **Match phase:**
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/RuleEnforcer.kt`
   - Function: `fun getMatchingRule(db: RequestrDatabase, url: String, host: String): InterceptRuleEntity?`
   - Logic: Iterates `intercept_rules` table, checks `pattern` regex against URL, returns first match
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/RuleEnforcer.kt`
   - Function: `fun checkRules(db: RequestrDatabase, url: String, host: String): RuleAction`
   - Logic: Calls `getMatchingRule()`, returns action or `LOG_ONLY` if no match

2. **Apply phase (in CaptureEngine):**
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/CaptureEngine.kt` (main request/response interception point)
   - Pseudo-code:
     ```kotlin
     val rule = RuleEnforcer.getMatchingRule(db, url, host)
     when (rule?.action) {
         RuleAction.BLOCK -> respondWithBlocked()
         RuleAction.REDIRECT -> redirectTo(rule.replacementValue)
         RuleAction.MODIFY_REQUEST -> modifyRequest(rule.replacementValue)
         RuleAction.MODIFY_RESPONSE -> modifyResponse(rule.replacementValue)
         RuleAction.MOCK_RESPONSE -> respondWithMocked(rule.replacementValue)
         RuleAction.SCRIPT_REWRITE -> runJavascript(rule.replacementValue)
         RuleAction.CHAOS -> injectChaosFailure()
         RuleAction.FORCE_MITM -> forceMitm()
         RuleAction.LOG_ONLY -> logOnly()
         null -> logOnly()
     }
     ```
   - Actual file: Search for the `when (rule?.action)` statement in `CaptureEngine.kt` to see the exact implementation

### How SCRIPT_REWRITE is Structured (Full End-to-End)

**1. Database schema (Entities.kt):**
   - Stored in `InterceptRuleEntity.replacementValue` as a plain string (the JavaScript source code)

**2. DAO (Daos.kt):**
   - Standard CRUD: `insertRule()`, `updateRule()`, `deleteRule()`, `queryByDomain()` — no special handling

**3. UI editor:**
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/RulesScreen.kt` or similar
   - Search for: "SCRIPT_REWRITE", "JavaScript editor", "script input field"
   - Pattern: Text field where user types JS, calls `RuleEnforcer.checkRules()` on save
   - The UI allows selecting `RuleAction.SCRIPT_REWRITE` as a radio/dropdown option

**4. Engine execution (RuleEnforcer.kt or CaptureEngine.kt):**
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/scripting/JsScriptEngine.kt`
   - Function: `fun executeScript(script: String, request: RequestWrapper, response: ResponseWrapper): ScriptResult`
   - Behavior:
     - Compiles the JS source from `rule.replacementValue`
     - Executes with `pm.request`, `pm.response`, `pm.variables` scope
     - Returns modified request/response or an error
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/scripting/ApiTestrScriptRunner.kt` or similar
   - Consumer: Called from within the `RuleAction.SCRIPT_REWRITE` case in the `when` statement

### How MOCK_RESPONSE is Structured (Full End-to-End)

**1. Database schema (Entities.kt):**
   - Stored in `InterceptRuleEntity.replacementValue` as a JSON string:
     ```json
     {
       "statusCode": 200,
       "headers": { "Content-Type": "application/json" },
       "body": "{...}"
     }
     ```

**2. DAO (Daos.kt):**
   - Standard CRUD, no special handling

**3. UI editor:**
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/RulesScreen.kt`
   - Search for: "MOCK_RESPONSE", "mock response editor"
   - Pattern: Fields for status code (number), headers (key-value pairs), body (text)
   - On save, serializes to JSON, stores in `rule.replacementValue`

**4. Engine execution:**
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/MockFixtureLoader.kt`
   - Function: `fun buildMockResponse(configJson: String): HttpResponse`
   - Behavior:
     - Parses the JSON from `rule.replacementValue`
     - Builds a real `HttpResponse` object with the specified status, headers, body
   - File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/CaptureEngine.kt`
   - Consumer: Called from the `RuleAction.MOCK_RESPONSE` case

### Template for New AES Crypto Rule (Same Shape)

**1. Add to RuleAction enum:**
   ```kotlin
   enum class RuleAction { ..., BODY_ENCRYPT_AES, ... }
   ```
   - Location: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/local/Entities.kt` line 112

**2. Extend InterceptRuleEntity** (if needed beyond replacementValue):
   - Currently `replacementValue` can hold the AES config (key, IV mode, algorithm)
   - Option A: Store entire config as JSON in `replacementValue`:
     ```json
     {
       "key": "base64-encoded-key",
       "algorithm": "AES/CBC/PKCS5Padding",
       "appliesTo": "response_body"
     }
     ```
   - Option B: Add dedicated fields if more complex (e.g., `encryptionKey: String`, `encryptionMode: String`)
     - If option B, add to Entity definition and DAO queries

**3. DAO queries:**
   - No special queries needed; standard CRUD suffices

**4. UI editor composable:**
   - File: Create new or extend `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/RulesScreen.kt` or a dedicated `CryptoRuleEditor.kt`
   - Pattern:
     ```kotlin
     when (selectedAction) {
         RuleAction.BODY_ENCRYPT_AES -> {
             CryptoRuleEditor(
                 key = rule.replacementValue?.let { parseKey(it) } ?: "",
                 algorithm = ...,
                 onSave = { key, algorithm ->
                     updateRule(rule.copy(replacementValue = serializeConfig(key, algorithm)))
                 }
             )
         }
         ...
     }
     ```

**5. Engine execution:**
   - File: Create `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/BodyCryptoEngine.kt`
   - Functions:
     ```kotlin
     fun encryptAesBody(plaintext: ByteArray, config: CryptoConfig): ByteArray { ... }
     fun decryptAesBody(ciphertext: ByteArray, config: CryptoConfig): ByteArray { ... }
     ```
   - Consumer: Call from `CaptureEngine.kt` in the `RuleAction.BODY_ENCRYPT_AES` case:
     ```kotlin
     RuleAction.BODY_ENCRYPT_AES -> {
         val cryptoConfig = parseCryptoConfig(rule.replacementValue)
         val encryptedBody = BodyCryptoEngine.encryptAesBody(request.body, cryptoConfig)
         request.copy(body = encryptedBody)
     }
     ```

### Existing Crypto Helper (Reuse If Present)

**Search for:** SecureKeyStore, CryptoHelper, AES, Cipher

**File:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/prefs/SecureKeyStore.kt`
- Handles AES-256-GCM encryption for at-rest secrets (e.g., CA private-key password)
- **Public API:** `suspend fun putSecret(context: Context, alias: String, plaintext: String)`
- **Public API:** `suspend fun getSecret(context: Context, alias: String): SecretReadResult`
- **Pattern:** Uses `AndroidKeyStore` provider for key material

**Recommendation:**
- For rule-engine AES body crypto, you likely need a different API: encrypt/decrypt arbitrary payloads with a user-provided key (not necessarily hardware-backed)
- **Reuse opportunity:** Study how `SecureKeyStore` handles key material initialization and cipher construction; apply the same pattern to the new `BodyCryptoEngine`
- **Do NOT reuse directly** because `SecureKeyStore` is single-value per alias, whereas rule-engine crypto needs per-rule keys

### Tests (Existing Examples + New Pattern)

**Existing rule tests:**
- File: `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/RuleEnforcerTest.kt`
- Pattern:
  ```kotlin
  @Test
  fun `domain match returns MOCK_RESPONSE`() {
      insertRule(domain = "api.example", action = RuleAction.MOCK_RESPONSE, replacementValue = "{...}")
      val rule = RuleEnforcer.getMatchingRule(db, "https://api.example/", "api.example")
      assertEquals(RuleAction.MOCK_RESPONSE, rule?.action)
  }
  ```

**Template for new crypto tests:**
```kotlin
@Test
fun `domain match returns BODY_ENCRYPT_AES`() {
    val configJson = """{"key":"...", "algorithm":"AES/CBC/PKCS5Padding"}"""
    insertRule(domain = "crypto.example", action = RuleAction.BODY_ENCRYPT_AES, replacementValue = configJson)
    val rule = RuleEnforcer.getMatchingRule(db, "https://crypto.example/", "crypto.example")
    assertEquals(RuleAction.BODY_ENCRYPT_AES, rule?.action)
}

@Test
fun `BodyCryptoEngine encrypts plaintext with AES-CBC`() {
    val plaintext = "Hello, World!".toByteArray()
    val key = "a1b2c3d4e5f6g7h8a1b2c3d4e5f6g7h8".fromHex() // 32 bytes
    val ciphertext = BodyCryptoEngine.encryptAesBody(plaintext, CryptoConfig(key, "AES/CBC/PKCS5Padding"))
    val decrypted = BodyCryptoEngine.decryptAesBody(ciphertext, CryptoConfig(key, "AES/CBC/PKCS5Padding"))
    assertEquals(plaintext.toList(), decrypted.toList())
}
```

### Emulator Verification (Redroid/Docker)

**Can be verified:** YES, fully.
- AES crypto is pure Java; no network, root, or hardware required
- All operations are local memory ↔ crypto → memory
- Unit tests can verify encryption/decryption round-trip in isolation
- Mock server tests can verify end-to-end rule matching + transformation

---

---

# PART C: 13 LOW-PRIORITY POLISH ITEMS

All from MASTER-FEATURE-ADDITION-PLAN.md §3 (lines 419–493), listed in order with exact quotes and implementation guidance.

## C1: Image Gallery

**Quote from plan (§3, line 440–441):**
> "**Automatic image-response gallery** aggregating captured image responses — `Flocon`'s `NetworkImageRepository`. (`flocon.md`)"

**Exact file location for implementation:**
- Create new file: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ImageGalleryScreen.kt`
- Create new DAO: Add `imageGalleryDao()` method to `RequestrDatabase` if not present

**Implementation pattern:**
- Query `CapturedTrafficEntity` for responses with `Content-Type` starting with `image/`
- Extract the response body (binary data)
- Render as a scrollable grid of thumbnails (Compose `LazyVerticalGrid`)
- On tap, show full-size image in a modal/bottom sheet
- Database query: `SELECT * FROM captured_traffic WHERE responseHeaders LIKE '%image/%' ORDER BY timestamp DESC`

**UI screen location:** Add to main navigation (typically a tab in the transaction list or a dedicated gallery screen)

**Where to wire:** `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/navigation/NavGraph.kt` (or equivalent routing)

**Emulator verification:** YES — requires only local data, no network/root/hardware.

---

## C2: Weak-Network Simulation

**Quote from plan (§3, line 466–468):**
> "**Weak-network simulation** (latency/jitter/packet-loss injection) — `proxypin`'s `network_condition.dart`, overlapping in spirit with Flocon's chaos-injection item above; pick one reference implementation. (`proxypin.md`)"

**Exact file location for implementation:**
- Create new file: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/NetworkConditionInjector.kt`
- Optional: extend existing `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/ChaosInjector.kt` if it's not already sufficient

**Implementation pattern:**
- Model: `data class NetworkCondition(val latencyMs: Int, val jitterMs: Int, val packetLossPercent: Int)`
- Storage: Add to `AppSettingsKeys` in `UnifiedDataStore.kt`:
  ```kotlin
  val NETWORK_CONDITION_LATENCY = intPreferencesKey("network_condition_latency_ms")
  val NETWORK_CONDITION_JITTER = intPreferencesKey("network_condition_jitter_ms")
  val NETWORK_CONDITION_PACKET_LOSS = intPreferencesKey("network_condition_packet_loss_pct")
  ```
- Engine: Intercept socket/stream reads/writes in `HttpProxyShared.kt` or `CaptureEngine.kt`; inject sleep/drop logic
- UI: Add sliders in Settings (SettingsScreen.kt) under "INTERCEPTION & PROXY ENGINE" section

**Where to apply:**
- Best applied at the proxy relay layer: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/HttpProxyShared.kt`
- Wrap every `socket.read()` / `socket.write()` with configurable `Thread.sleep()` and probabilistic packet drops

**Emulator verification:** YES — all local, pure Java, no network/root/hardware required.

---

## C3: Large-Body Render Gate

**Quote from plan (§3, line 425–426):**
> "**Large-body (\>100KB) render gate** to avoid Compose UI jank on huge captured bodies — `alice`'s `_LargeTextBody`. (`alice.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/TransactionDetailScreen.kt` (or wherever captured-traffic bodies are displayed)

**Implementation pattern:**
- Check `body.length > 100_000` (100 KB threshold)
- If true: Show a placeholder: "Body too large to display inline (XXX KB). [View in Hex] [Export]"
- If false: Render full body as before
- Add UI toggle in Settings: "Disable large-body render gate" (advanced option)

**Compose snippet:**
```kotlin
if (body.length > 100_000) {
    Text("Body too large to display inline (${body.length / 1024} KB)")
    Button(onClick = { /* show hex viewer */ }) { Text("View in Hex") }
    Button(onClick = { /* export body */ }) { Text("Export") }
} else {
    Text(body)
}
```

**Emulator verification:** YES — pure UI logic, no dependencies.

---

## C4: Content-Type–Gated Rendering

**Quote from plan (§3, line 421–422):**
> "**Content-type-gated body rendering** (image/video/binary vs text, instead of always dumping raw monospace) — `jhomlala/alice`'s `_BodyDataColumn`. (`alice.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/TransactionDetailScreen.kt` (response body tab)

**Implementation pattern:**
```kotlin
val contentType = response.headers["Content-Type"] ?: ""
when {
    contentType.startsWith("image/") -> Image(bitmap = loadImage(body))
    contentType.startsWith("video/") -> Text("[Video: not embeddable; export to view]")
    contentType.startsWith("application/json") -> JsonTreeView(parseJson(body))
    contentType.startsWith("text/") -> Text(body, fontFamily = FontFamily.Monospace)
    else -> HexViewer(body)  // fallback for binary
}
```

**Where to add:** In the response-body display composable within the transaction detail screen

**Emulator verification:** YES — pure UI + local parsing, no network/root/hardware required.

---

## C5: Aggregate Stats / Dashboard

**Quote from plan (§3, line 429–430):**
> "**Aggregate stats/dashboard screen** (`AliceStatsPage` pattern) summarizing capture session totals. (`alice.md`)"

**Exact file location for implementation:**
- Create new file: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/StatsScreen.kt`

**Implementation pattern (SQL queries):**
```kotlin
val totalRequests = db.capturedTrafficDao().countAll()
val totalBytes = db.capturedTrafficDao().sumResponseBodySize()
val topDomains = db.capturedTrafficDao().groupByDomain().limit(10)
val failureCount = db.capturedTrafficDao().countByStatus(400..599)
val avgResponseTime = db.capturedTrafficDao().averageResponseTimeMs()
```

**UI cards:**
- Total requests, total bytes, unique hosts, failure rate, avg response time
- Bar chart of requests by domain
- Timeline of request rate over capture session

**Where to wire:** Add as a new screen in the main navigation

**Emulator verification:** YES — all local database aggregations, no network/root/hardware required.

---

## C6: Multi-Key Sort

**Quote from plan (§3, line 427–428):**
> "**Multi-key sort** for the transaction list (`AliceCallsListSortOption` enum pattern). (`alice.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/TransactionListScreen.kt` (or `CapturedTrafficListScreen.kt`)

**Implementation pattern:**
- Define enum:
  ```kotlin
  enum class SortOption {
      TIME_DESC, TIME_ASC,
      STATUS_CODE_ASC, STATUS_CODE_DESC,
      DOMAIN_ASC, DOMAIN_DESC,
      SIZE_ASC, SIZE_DESC,
      // Compound sorts:
      DOMAIN_THEN_TIME,
      STATUS_THEN_DOMAIN,
  }
  ```
- Store selected sort in `SettingsDataStore`:
  ```kotlin
  val TRAFFIC_LIST_SORT = stringPreferencesKey("traffic_list_sort")
  ```
- Apply in DAO query:
  ```kotlin
  when (sortOption) {
      SortOption.DOMAIN_THEN_TIME -> 
          "SELECT * FROM captured_traffic ORDER BY domain ASC, timestamp DESC"
      SortOption.STATUS_THEN_DOMAIN ->
          "SELECT * FROM captured_traffic ORDER BY responseStatus ASC, domain ASC"
      ...
  }
  ```

**UI:** Add a "Sort by" dropdown/menu in the transaction list header

**Emulator verification:** YES — pure data sorting, no network/root/hardware required.

---

## C7: Repeat/Replay Dialog (Advanced)

**Quote from plan (§3, line 477–478):**
> "**Advanced repeat/replay dialog** (repeat count + jittered interval) for replaying a captured request — `proxypin`'s `CustomRepeatDialog`. (`proxypin.md`)"

**Exact file location for implementation:**
- Create new file: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/composables/RepeatReplayDialog.kt`
- Wire into: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/TransactionDetailScreen.kt` (context menu / action button)

**Implementation pattern:**
- Dialog fields:
  - Repeat count: `1..100` (slider or number input)
  - Interval: `0..60000` ms (slider)
  - Jitter: `0..10000` ms (slider, random ±variation)
- On confirm: Launch coroutine that re-sends the request `count` times with delay
  ```kotlin
  repeat(count) { i ->
    val actualDelay = interval + Random.nextInt(-jitter..jitter)
    delay(actualDelay)
    executeRequest(capturedRequest)
  }
  ```
- Show progress: "Replayed 3 of 10"

**Emulator verification:** YES — pure UI + local coroutine, no network/root/hardware required (though actual replay requires network if the target is live).

---

## C8: INTERNET Permission Prefilter

**Quote from plan (§3, line 456–457):**
> "**INTERNET-permission pre-filter** for the VPN app-picker list (fewer irrelevant apps shown) — `reqable/installed_apps`. (`installed-apps.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/AppPickerScreen.kt` (or wherever the app list is displayed)

**Implementation pattern:**
```kotlin
// In AppPickerScreen, filter installed apps:
val internetApps = installedApps.filter { app ->
    context.packageManager.checkPermission(Manifest.permission.INTERNET, app.packageName) ==
        PackageManager.PERMISSION_GRANTED
}
// Render only internetApps, not all installed apps
```

**Where to add:**
- Function: `fun filterAppsWithInternet(context: Context, apps: List<AppInfo>): List<AppInfo>`
- Location: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/models/AppModels.kt` or a new `AppUtils.kt`
- Call from the app-picker composable before rendering

**Emulator verification:** YES — pure Android API, no network/root/hardware required.

---

## C9: Launcher-Label Fallback

**Quote from plan (§3, line 458–459):**
> "**Launcher-activity label fallback** for mislabelled apps in the app-picker — `reqable/installed_apps`. (`installed-apps.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/models/AppModels.kt`
- Function: Already likely present as `AppInfo.displayName` or similar

**Implementation pattern:**
```kotlin
data class AppInfo(
    val packageName: String,
    val appLabel: String = "",  // from ApplicationInfo.loadLabel()
) {
    val displayLabel: String
        get() {
            if (appLabel.isNotBlank()) return appLabel
            // Fallback: try to get launcher activity label
            val launcher = context.packageManager.getLaunchIntentForPackage(packageName)
            val resolveInfo = launcher?.let {
                context.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY)
            }
            return resolveInfo?.loadLabel(context.packageManager)?.toString() ?: packageName
        }
}
```

**Where to apply:** In the app-picker UI, use `appInfo.displayLabel` instead of `appInfo.appLabel`

**Emulator verification:** YES — Android API only, no network/root/hardware required.

---

## C10: `{{var}}` Live-Rule Templating

**Quote from plan (§3, line 469–473):**
> "**`{{var}}` environment templating for the live rewrite-rule engine** — distinct from ApiTestr's own already-working `{{VAR}}` substitution (confirmed present, `easy-api.md`); `proxypin`'s `EnvironmentManager` pattern applied to `RuleEnforcer`/live-MITM rewrite rules specifically, which have no templating today. (`proxypin.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/RuleEnforcer.kt`
- Function: `fun applyRequestModification(request: ..., rule: ...) → ...`

**Implementation pattern:**
- Extend `RuleEnforcer` to resolve `{{VAR_NAME}}` placeholders in `rule.replacementValue`
- Placeholder resolution order:
  1. Active environment variables (from `EnvironmentRepository`)
  2. Dynamic variables (session ID, timestamp, random UUID)
  3. Literal fallback (leave as-is if not found)
- Example:
  ```kotlin
  val template = rule.replacementValue  // e.g., "Authorization: Bearer {{auth_token}}"
  val resolved = resolvePlaceholders(template, environmentVars, dynamicVars)
  ```
- Function to add:
  ```kotlin
  private fun resolvePlaceholders(
      template: String,
      envVars: Map<String, String>,
      dynamicVars: Map<String, String>
  ): String {
      return template.replace(Regex("""\\{\\{([^}]+)\\}\\}""")) { match ->
          val varName = match.groupValues[1]
          envVars[varName] ?: dynamicVars[varName] ?: match.value
      }
  }
  ```

**Storage:** Use existing `replacementValue` string field; no schema change needed

**UI:** Document the feature in the rule editor (tooltip: "Use {{VAR_NAME}} to substitute environment variables")

**Emulator verification:** YES — pure string substitution + local lookups, no network/root/hardware required.

---

## C11: Copy/Paste cURL for Live Traffic

**Quote from plan (§3, line 474–476):**
> "**Copy-as-cURL / paste-a-cURL-to-populate-a-request** for the live MITM transaction view (ApiTestr already has both directions; this extends the same convenience to captured live traffic) — `proxypin`. (`proxypin.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/TransactionDetailScreen.kt`
  - Add "Copy as cURL" button → calls `CodeExporter.toCurl(request, response)`
  - Add "Paste cURL" button → launches text-input dialog, parses cURL, populates request fields
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/export/CodeExporter.kt` (likely already has `toCurl()`)
  - Verify method exists; if not, add it
- File: Create `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/export/CurlParser.kt`
  - Parse cURL string back to `CapturedTrafficEntity` or `ApiRequestItem`
  - Regex/tokenize: `curl 'url' -H 'header: value' -d 'body'` → fields
  - Test fixture: `curl 'https://api.example.com/users' -H 'Authorization: Bearer token' -H 'Content-Type: application/json' -d '{"name":"John"}'`

**UI UX:**
- Copy: Long-press on request → "Copy as cURL" → toast "Copied!"
- Paste: Settings or transaction-list screen → button "New request from cURL" → dialog → "Paste cURL command" → [Populate] button

**Emulator verification:** YES — pure string/regex parsing, no network/root/hardware required.

---

## C12: Per-CONNECT CA Re-Check

**Quote from plan (§3, line 483–486):**
> "**Per-CONNECT CA-trust re-check with graceful tunnel-without-MITM fallback** — `RoRoIntercept`'s `handleConnect()` re-checks `isCertificateInstalled()` on every CONNECT and degrades to raw relay if the CA is no longer trusted, vs requestr's `PreflightController` which only gates once at session start. (`rorointercept.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/MitmProxyEngine.kt` (around line 170–240, CONNECT handling)
- Function: Already exists as `handleClient()` → parse CONNECT → check `ConnectControl.Disposition`

**Implementation pattern:**
- Current behavior: `PreflightController` checks CA once at session start; if the CA is uninstalled mid-session, subsequent CONNECTs still try MITM
- New behavior: Before line 238 (cert generation), add:
  ```kotlin
  val caInstalled = CapabilityProbe.probeCaInstalled()  // re-probe
  if (!caInstalled) {
      // Downgrade to AllowOpaque (tunnel without MITM)
      HttpProxyShared.handleConnectAttempt(
          client, target, ConnectControl.Disposition.AllowOpaque, output,
      ) { sent, recv ->
          persistConnectAttempt(...)
      }
      return
  }
  ```

**File to modify:**
- `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/MitmProxyEngine.kt` line ~238
- Insert CA re-check just before the cert generation call

**Testing:**
- Unit test: Mock `probeCaInstalled()` to return false mid-stream; verify CONNECT is downgraded to AllowOpaque
- Integration test: Uninstall CA mid-capture, verify next CONNECT tunnels opaquely

**Emulator verification:** Partial.
- The downgrade logic is pure; unit tests can verify with mocks
- The real re-probe of CA installation requires checking the system trust store, which may not fully work in Redroid
- Functional verification: YES with mocking; end-to-end: requires real or Magisk-rooted emulator

---

## C13: Basic-Auth Show/Hide Toggle

**Quote from plan (§3, line 491–493):**
> "**Basic Auth password show/hide toggle** in ApiTestr's `AuthSettingsTab` (currently always plaintext, zero `visualTransformation`) — `PostmanAndroidClient`. (`postmanandroidclient.md`)"

**Exact file location for implementation:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ApiTestrScreen.kt` (or `AuthSettingsTab.kt` if split)
- Search for: "basicPassword" or "BasicAuth" input field

**Implementation pattern:**
```kotlin
var showPassword by remember { mutableStateOf(false) }

OutlinedTextField(
    value = basicPassword,
    onValueChange = { basicPassword = it },
    label = { Text("Password") },
    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
    trailingIcon = {
        IconButton(onClick = { showPassword = !showPassword }) {
            Icon(
                imageVector = if (showPassword) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                contentDescription = if (showPassword) "Hide" else "Show"
            )
        }
    }
)
```

**Location in code:**
- File: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/ui/screens/ApiTestrScreen.kt` (main API tester screen)
- Function: Search for the composable that renders the auth settings (likely `AuthSection()` or similar)
- Find the line: `OutlinedTextField(value = basicPassword, ...)`
- Wrap in the pattern above

**Emulator verification:** YES — pure Compose UI, no dependencies.

---

# SUMMARY TABLE: LOW-PRIORITY ITEMS

| # | Feature | Quote | Files to Create/Modify | Can Verify in No-Internet Sandbox |
|---|---------|-------|------------------------|-----------------------------------|
| C1 | Image Gallery | (§3:440–441) Automatic image-response gallery | New: `ImageGalleryScreen.kt` | YES |
| C2 | Weak-Network Sim | (§3:466–468) Latency/jitter/packet-loss injection | New: `NetworkConditionInjector.kt`; Modify: `HttpProxyShared.kt` | YES |
| C3 | Large-Body Gate | (§3:425–426) Skip render for \>100KB bodies | Modify: `TransactionDetailScreen.kt` | YES |
| C4 | Content-Type Gating | (§3:421–422) Render by MIME type (image/JSON/binary/text) | Modify: `TransactionDetailScreen.kt` | YES |
| C5 | Aggregate Stats | (§3:429–430) Dashboard with totals & charts | New: `StatsScreen.kt` | YES |
| C6 | Multi-Key Sort | (§3:427–428) Compound sort options (domain+time, etc) | Modify: `TransactionListScreen.kt` + DAO query | YES |
| C7 | Repeat/Replay | (§3:477–478) Dialog with count, interval, jitter | New: `RepeatReplayDialog.kt` | YES |
| C8 | INTERNET Filter | (§3:456–457) Prefilter app-picker by INTERNET permission | New: `AppUtils.kt` + modify `AppPickerScreen.kt` | YES |
| C9 | Launcher Label | (§3:458–459) Fallback for unlabeled apps | Modify: `AppModels.kt` display logic | YES |
| C10 | {{var}} Templating | (§3:469–473) Live-rule `{{ENV_VAR}}` substitution | Modify: `RuleEnforcer.kt` apply methods | YES |
| C11 | cURL Copy/Paste | (§3:474–476) Copy transaction as cURL; paste cURL to populate | New: `CurlParser.kt`; Modify: `TransactionDetailScreen.kt`, `CodeExporter.kt` | YES |
| C12 | Per-CONNECT CA Re-Check | (§3:483–486) Degrade MITM if CA uninstalled mid-session | Modify: `MitmProxyEngine.kt` CONNECT handler | Partial (logic: YES; real CA re-probe: hardware) |
| C13 | Basic-Auth Toggle | (§3:491–493) Show/hide password in ApiTestr | Modify: `ApiTestrScreen.kt` password field | YES |

---

---

# PART D: TEST CONVENTIONS & STRUCTURE

## Where Tests Live

**Unit test directory:** `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/`

**Structure mirrors source:**
- Source: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/interception/RuleEnforcer.kt`
- Test: `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/RuleEnforcerTest.kt`

- Source: `/code/Mahesh953-hub/requestr/app/src/main/java/com/example/data/repository/ApiTestrRepository.kt`
- Test: `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/data/repository/ApiTestrRepository*Test.kt` (multiple files, one per feature)

## Naming Convention

- Class: `{SourceClassName}Test.kt`
- Function: `@Test fun \`description of test case\`()` (backtick-enclosed descriptions)
- Example: `` @Test fun `domain match returns FORCE_MITM`() ``

## Test Runners & Annotations

**Unit tests (no Android framework):**
```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class SomeTest {
    @Test fun `test case`() { ... }
}
```

**Tests with Room database:**
```kotlin
@RunWith(RobolectricTestRunner::class)
class SomeRepositoryTest {
    private lateinit var db: RequestrDatabase
    
    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
    }
    
    @After
    fun tearDown() {
        db.close()
    }
}
```

**Tests with mock HTTP server:**
```kotlin
class SomeApiTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().build()
    
    @Before fun setUp() {
        server = MockWebServer()
        server.start()
    }
    
    @After fun tearDown() {
        server.shutdown()
    }
    
    @Test fun `test case`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        // execute request to server.url("/...")
        val recorded = server.takeRequest()
        // assert recorded.method, body, headers, etc.
    }
}
```

## Representative Example 1: Intercept Rule Test

**File:** `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/interception/RuleEnforcerTest.kt`

```kotlin
package com.example.interception

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.InterceptRuleEntity
import com.example.data.local.RequestrDatabase
import com.example.data.local.RuleAction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class RuleEnforcerTest {
    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertRule(
        domain: String = "",
        pattern: String = "",
        action: RuleAction,
        enabled: Boolean = true,
        replacementValue: String? = null,
    ) = runBlocking {
        db.interceptRuleDao().insert(
            InterceptRuleEntity(
                name = "test",
                domain = domain,
                pattern = pattern,
                action = action,
                enabled = enabled,
                replacementValue = replacementValue,
            )
        )
    }

    @Test
    fun `no matching rule returns LOG_ONLY`() {
        assertEquals(RuleAction.LOG_ONLY, RuleEnforcer.checkRules(db, "https://example.com/", "example.com"))
    }

    @Test
    fun `domain match returns MOCK_RESPONSE`() {
        insertRule(domain = "api.example", action = RuleAction.MOCK_RESPONSE, replacementValue = """{"statusCode":200}""")
        assertEquals(
            RuleAction.MOCK_RESPONSE,
            RuleEnforcer.checkRules(db, "https://api.example/data", "api.example"),
        )
    }

    @Test
    fun `disabled rule does not match`() {
        insertRule(domain = "blocked.example", action = RuleAction.BLOCK, enabled = false)
        assertEquals(
            RuleAction.LOG_ONLY,
            RuleEnforcer.checkRules(db, "https://blocked.example/", "blocked.example"),
        )
    }
}
```

**Key patterns:**
- In-memory Room database via `inMemoryDatabaseBuilder()`
- Helper method `insertRule()` wraps suspension with `runBlocking {}`
- Assertions use simple `assertEquals(expected, actual)`

---

## Representative Example 2: ApiTestr Repository Test (Form Body)

**File:** `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/data/repository/ApiTestrRepositoryFormBodyTest.kt`

```kotlin
package com.example.data.repository

import com.example.data.models.ApiRequestItem
import com.example.data.models.HttpMethod
import com.example.data.models.KeyValueItem
import com.example.data.models.RequestBodyType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiTestrRepositoryFormBodyTest {
    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder().build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `FORM body url-encodes only enabled fields`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = server.url("/").toString(),
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.FORM,
            formData = listOf(
                KeyValueItem(key = "a", value = "1", isEnabled = true),
                KeyValueItem(key = "b", value = "two words", isEnabled = true),
                KeyValueItem(key = "c", value = "disabled", isEnabled = false)
            )
        )

        ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        assertEquals("application/x-www-form-urlencoded", recorded.headers["Content-Type"])
        assertEquals("a=1&b=two%20words", recorded.body.readUtf8())
    }

    @Test
    fun `MULTIPART body sends only enabled parts`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val item = ApiRequestItem(
            method = HttpMethod.POST,
            url = server.url("/").toString(),
            queryParams = emptyList(),
            headers = emptyList(),
            bodyType = RequestBodyType.MULTIPART,
            formData = listOf(
                KeyValueItem(key = "field1", value = "value1", isEnabled = true),
                KeyValueItem(key = "field2", value = "value2", isEnabled = false)
            )
        )

        ApiTestrRepository.executeRequest(item, followRedirects = true, reqClient = client)

        val recorded = server.takeRequest()
        val contentType = recorded.headers["Content-Type"] ?: ""
        assertTrue(contentType.startsWith("multipart/form-data; boundary="))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("name=\"field1\""))
        assertTrue(body.contains("value1"))
    }
}
```

**Key patterns:**
- Uses `MockWebServer` to simulate HTTP endpoint
- Enqueues responses via `server.enqueue()`
- Executes request via the repository method under test
- Captures the actual HTTP request via `server.takeRequest()`
- Asserts on headers, body encoding, status code, etc.

---

## Representative Example 3: Database Entity Round-Trip Test

**File:** `/code/Mahesh953-hub/requestr/app/src/test/java/com/example/data/local/CapturedTrafficDaoExportTest.kt` (existing; demonstrates pattern)

```kotlin
package com.example.data.local

import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.Q])
class CapturedTrafficDaoExportTest {

    private lateinit var db: RequestrDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            RequestrDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `time-range export includes only traffic in range`() = runBlocking {
        val session = 1001L
        val rows = listOf(
            CapturedTrafficEntity(sessionId = session, timestamp = 100, domain = "a.com"),
            CapturedTrafficEntity(sessionId = session, timestamp = 150, domain = "b.com"),
            CapturedTrafficEntity(sessionId = session, timestamp = 200, domain = "c.com"),
        )
        rows.forEach { db.capturedTrafficDao().insert(it) }

        val rangeResults = db.capturedTrafficDao().getRangePageBeforeCursor(
            sessionId = session,
            maxTimestamp = 180,
            limit = 100,
        )

        assertEquals(2, rangeResults.size)  // timestamps 100 and 150
        assertEquals("b.com", rangeResults[0].domain)
        assertEquals("a.com", rangeResults[1].domain)
    }

    @Test
    fun `out-of-range timestamps are excluded`() = runBlocking {
        val session = 1001L
        val inRange = listOf(
            CapturedTrafficEntity(sessionId = session, timestamp = 190, domain = "in1"),
            CapturedTrafficEntity(sessionId = session, timestamp = 180, domain = "in2"),
        )
        val outOfRange = listOf(
            CapturedTrafficEntity(sessionId = session, timestamp = 250, domain = "out1"),
            CapturedTrafficEntity(sessionId = session, timestamp = 240, domain = "out2"),
        )
        (inRange + outOfRange).forEach { db.capturedTrafficDao().insert(it) }

        val results = db.capturedTrafficDao().getRangePageBeforeCursor(
            sessionId = session,
            maxTimestamp = 200,
            limit = 100,
        )

        assertEquals(2, results.size)
        assertEquals(setOf("in1", "in2"), results.map { it.domain }.toSet())
    }
}
```

**Key patterns:**
- Fixture data (test rows) inserted via DAO
- Query executed
- Results asserted with counts, field values, ordering

---

## Command to Run All Tests

```bash
cd /code/Mahesh953-hub/requestr
./gradlew :app:testDebugUnitTest
```

Expected output (from status doc):
```
1100 tests, 0 failed
```

---

---

# IMPLEMENTATION CHECKLIST

Use this to track progress as each deferred + new item is wired:

## Part A: Deferred Items

- [ ] **A1 Remote-cert mirroring**
  - [ ] Add `MIRROR_REMOTE_CERT` key to `AppSettingsKeys` (UnifiedDataStore.kt)
  - [ ] Add getter/setter in `SettingsDataStore` class
  - [ ] Add UI toggle in `SettingsScreen.kt`
  - [ ] Modify cert-generation lambda in `MitmProxyEngine.kt` line 238
  - [ ] Write unit tests for settings toggle
  - [ ] Write integration test for CONNECT branching

- [ ] **A2 Local DB browser**
  - [ ] Create `TargetAppDatabaseBrowserSection` composable (or new screen)
  - [ ] Gate with `RootState.GRANTED` check
  - [ ] Wire navigation to new screen/section
  - [ ] Write UI tests for visibility gating

- [ ] **A3 .p12 import UI**
  - [ ] Add file-picker + password-dialog composable to CA settings
  - [ ] Wire to `CaP12Exchange.importP12()`
  - [ ] Show success/error feedback toast
  - [ ] Write UI tests for flow

## Part B: Net-New Item

- [ ] **B Per-URL AES body crypto**
  - [ ] Add `BODY_ENCRYPT_AES` to `RuleAction` enum (Entities.kt)
  - [ ] Create `BodyCryptoEngine.kt` with `encryptAesBody()` / `decryptAesBody()`
  - [ ] Add UI editor tab for crypto rule config
  - [ ] Wire into `CaptureEngine.kt` rule-action dispatch
  - [ ] Write encryption round-trip tests
  - [ ] Write rule-matching + transformation tests

## Part C: 13 LOW Items

- [ ] C1: Image gallery (new screen + query)
- [ ] C2: Weak-network simulation (new engine + settings)
- [ ] C3: Large-body render gate (UI check in TransactionDetailScreen)
- [ ] C4: Content-Type rendering (MIME-based rendering switch)
- [ ] C5: Aggregate stats dashboard (new screen + queries)
- [ ] C6: Multi-key sort (enum + DAO + UI dropdown)
- [ ] C7: Repeat/Replay dialog (new composable)
- [ ] C8: INTERNET permission filter (app picker logic)
- [ ] C9: Launcher-label fallback (AppModels.kt display logic)
- [ ] C10: {{var}} templating (regex substitution in RuleEnforcer)
- [ ] C11: cURL copy/paste (new parser + UI buttons)
- [ ] C12: Per-CONNECT CA re-check (MitmProxyEngine CONNECT handler)
- [ ] C13: Basic-Auth show/hide (ApiTestrScreen password field)

---

**End of dossier.**
