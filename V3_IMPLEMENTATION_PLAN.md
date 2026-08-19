# V3 Implementation Plan — MODERNIZATION_OF_INTERCEPTION + Feature Vision V3

## Plan Version
1

## Synthesis

Both vision documents converge on: **Capture → Understand → Inspect → Transform → Test → Replay → Automate**

## Implementation Phases (This Session)

### Phase 1 — Traffic Model Modernization (MOD #28, V3 #1)
- Enhanced `CapturedRequest` with: trafficSource, protocolHint, tlsVersion, tlsCipher, handshakeMs
- Timing breakdown: dnsMs, connectMs, tlsMs, waitMs, downloadMs, totalMs
- `TrafficClassifier` utility

### Phase 2 — Request Inspector Tabs (V3 #1, MOD #27)
- Tabbed detail view: Overview | Headers | Body | Timing | TLS | Raw
- Structured JSON body viewer
- TLS certificate chain display

### Phase 3 — Traffic Search (V3 #13, MOD #35)
- Query parser: `status:500`, `method:POST`, `host:...`, `duration:>1000`
- Combined queries: `status:429 AND host:api.example.com`

### Phase 4 — Code Exporters (V3 #14)
- cURL, Python requests, JavaScript fetch, Go http.NewRequest
- Replace simple export with rich multi-format

### Phase 5 — Environments (V3 #2)
- `Environment` data model + repository
- Variable substitution: `{{BASE_URL}}`, `{{TOKEN}}`, `{{TIMESTAMP}}`, `{{USER_ID}}`

---

## Implementation Order

1. **AppModels.kt** — Enhanced CapturedRequest + TrafficSource + ProtocolHint + timing
2. **TrafficClassifier.kt** — Source/protocol detection
3. **CaptureLogRepository.kt** — Updated to populate new fields
4. **TrafficSearch.kt** — Query parser + filter engine
5. **CodeExporter.kt** — cURL/Python/JS/Go generators
6. **Environment.kt** — Model + repository
7. **InterceptScreen.kt** — Request inspector tabs, search bar integration, timing view
8. **ApiTestrScreen.kt** — Environment variable substitution
