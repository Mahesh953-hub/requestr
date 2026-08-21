# MV3 Browser Extension SDK — Implementation Plan

> **TL;DR:** Overhaul the observation-only `browser-extension/` into a real MV3 SDK with request/response body capture, rule-driven enforcement (block/redirect/modify), a bidirectional control channel for push-rules, resilient persistent batching, real popup/options UI, and token-based pairing with `LocalApiServer`.

---

## Summary

Current state (`backup` branch, TODO_v5.md B7 row): four ad-hoc scripts that observe headers/timing via `chrome.webRequest` and a MAIN-world `fetch`/`XMLHttpRequest` patch, batch-POST to `POST /api/observe` with a `setInterval`-based flush. No bodies, no enforcement, no persistent queue, no auth — "by design" limitations the user rejects.

Goal: a real, versioned JS SDK layer inside `browser-extension/` that captures request *and* response bodies, enforces requestr's `intercept_rules` table rules (BLOCK / REDIRECT / MODIFY_REQUEST / MODIFY_RESPONSE), survives service-worker termination, authenticates its calls to `LocalApiServer`, and provides a meaningful control surface in the popup and options page.

---

## Grounding Evidence (what was read)

| Source | Key finding |
|---|---|
| `manifest.json` L1-41 | No `declarativeNetRequest`, `alarms`, or `options_page` declared; host_permissions is `["<all_urls>"]` but no explicit localhost entry |
| `background.js` L37-41, L162-179, L181, L192 | Uses `let` globals for all state (`eventQueue`, `relayedCount`, etc.); `setInterval(flush, 2000)` and `setInterval(pingServer, 5000)` — both die on SW termination; `flush()` drops failed batches silently (`lastError = String(e…)` at L177) with zero retry |
| `page-interceptor.js` L267-334, L355-380 | Patches `window.fetch` and `XMLHttpRequest` in MAIN world; captures headers/status/timing but **never reads request body** and **only reads Content-Length from response headers** — no actual response body capture |
| `content.js` L216-229 | Bridges MAIN-world `CustomEvent` to `chrome.runtime.sendMessage` — correct pattern, but event payload is the existing header-only schema |
| `popup.html` L1-104, `popup.js` L1-155 | Shows connection dot, relayed count, queued count, API base input — no rule visibility, no body-count metrics, no enforcement controls |
| `LocalApiServer.kt` L127-153, L287-326 | `route()` has zero auth (line 152 has no token check); `receiveObserverEvents()` at L287 reads `url/method/headers/statusCode/bodySize/durationMs/timestamp` from events array — **no requestBody or responseBody fields consumed**; `listRules()` at L266 returns rules as JSON but is read-only |
| `RuleEnforcer.kt` L29-31, L34-51, L57-78, L84-100 | `checkRules(db, url, host)` matches by domain substring OR pattern regex; `applyRequestModification()` expects JSON `{"addHeaders":{…}, "removeHeaders":[…], "bodyReplace":"…"}`; `applyResponseModification()` expects `{"statusCode":200, "addHeaders":{…}, "bodyReplace":"…"}`; REDIRECT uses `rule.replacementValue` as raw target URL |
| `Entities.kt` L82, L90-101 | `RuleAction` enum: `BLOCK, REDIRECT, MODIFY_REQUEST, MODIFY_RESPONSE, LOG_ONLY`; `InterceptRuleEntity` has `id/name/pattern/domain/action/enabled/replacementValue`; `CapturedTrafficEntity` at L26-29 has `requestHeaders/responseHeaders/requestBody/responseBody` columns ready for body data |
| `README.md` L65-69, L81-85 | Self-documents as "no request/response body capture," "no ability to block/redirect/modify requests" — these are the "by design" sentences being rejected |

---

## File Layout (proposed)

```
browser-extension/
├── manifest.json                          # Updated: DNR, alarms, options, new CS entries
├── src/
│   ├── sdk/                               # Reusable SDK modules
│   │   ├── index.mjs                      # Public SDK exports (version, init, connect, disconnect, getStatus)
│   │   ├── version.mjs                    # SDK version constant (semver string)
│   │   ├── config.mjs                     # chrome.storage-backed config: apiBase, token, maxBodyBytes, flushInterval
│   │   ├── observer.mjs                   # Normalizes webRequest events + interceptor events into single schema
│   │   ├── interceptor-main.mjs           # MAIN-world: fetch/XHR patch with body capture, request/response mutation
│   │   ├── enforcer.mjs                   # Rule-matching engine (mirrors RuleEnforcer.kt logic in JS)
│   │   ├── rules.mjs                      # Rule fetch, caching, DNR rule sync (dynamic rules)
│   │   ├── channel.mjs                    # Bidirectional: rules pull + optional push endpoint polling
│   │   ├── queue.mjs                      # Persistent event queue (chrome.storage.session) + chrome.alarms flush + retry/backoff
│   │   ├── pairing.mjs                    # Token generation, handshake, auth header injection
│   │   └── utils.mjs                      # Backoff calc, base64, body serialization helpers
│   ├── background/
│   │   └── service-worker.mjs             # Entry point: init config, register alarms, wire observer/enforcer/channel/queue
│   ├── content/
│   │   ├── bridge.mjs                     # Isolated-world: receives CustomEvent from MAIN-world, relays via chrome.runtime.sendMessage (replaces content.js)
│   │   └── interceptor-main.mjs           # MAIN-world: injected as `"world": "MAIN"`, imports interceptor logic from sdk/interceptor-main.mjs
│   └── ui/
│       ├── popup.html                     # Redesigned: status, rules list, body counts, enforcement toggle, API base, pair status
│       ├── popup.mjs                      # Popup logic
│       ├── popup.css                      # Popup styles
│       ├── options.html                   # New: full-page options with rule management, traffic log, export
│       ├── options.mjs                    # Options logic
│       └── options.css                    # Options styles
├── test/
│   └── test-page.html                     # Integration test page: exercises all intercept/body/enforcement paths
├── icons/                                 # existing
└── README.md                              # Updated
```

**Why `.mjs` extension:** Chrome MV3 service workers support `"type": "module"` in the manifest, enabling ES module `import`/`export`. Using `.mjs` makes the module intent explicit and avoids content-type negotiation issues. Content scripts can NOT be loaded as modules (Chrome limitation), so `bridge.mjs` and `interceptor-main.mjs` are loaded as classic scripts — the `.mjs` extension is for local editor tooling; they use IIFE patterns internally.

**Note on MAIN-world interception module:** The `src/sdk/interceptor-main.mjs` module contains the fetch/XHR patching logic. The content-script entry `src/content/interceptor-main.mjs` is a thin wrapper that imports it. Since Chrome does not yet support ES modules in content scripts, the actual deployment bundles interceptor logic into the content-script file via simple concatenation at build time (or uses a manual IIFE wrapper that references a shared global). The plan assumes a manual bundling step: `src/sdk/interceptor-main.mjs` is the canonical source, and `src/content/interceptor-main.mjs` is a duplicate or `import` shim that resolves at extension load time.

---

## Android-Side Dependencies & Boundaries

The following Android-side changes are **outside this plan's scope** but the MV3 extension depends on them. Each is flagged so another agent can pick them up.

| Boundary item | Android file | What the extension needs |
|---|---|---|
| **`POST /api/pair`** (new endpoint) | `LocalApiServer.kt` | Accept `{"deviceName":"...","publicKey":"..."}`, generate/store a pairing token, return `{"token":"abc123","accepted":true}`. See pairing section below. |
| **Auth on `/api/observe`** | `LocalApiServer.kt` L287 | Check `Authorization: Bearer` header on every POST; reject with 401 if missing/invalid. Token validated against stored pairing. |
| **Auth on `/api/rules`** | `LocalApiServer.kt` L266 | Add token check to `listRules()`; return rules with full `replacementValue` included (L271 currently omits it: only sends `id/name/action/pattern/enabled` — must add `replacementValue`). |
| **`GET /api/rules` → include `replacementValue`** | `LocalApiServer.kt` L268-273 | Current L271 sends only `id/name/action/pattern/enabled`. Must also include `replacementValue` and `domain` fields so the extension can sync DNR rules and local enforcement rules. |
| **Shared pairing token scheme** | Shared concern with Android SDK | Extension generates a random hex token on first install; Android SDK sibling scope plans the same. Both should POST to the same `/api/pair` endpoint and use the same `Authorization: Bearer` header convention. The token scheme is: random 32-byte hex string, stored in `chrome.storage.local` (extension) or SharedPreferences (Android SDK). |

---

## Architecture: Enforcement Strategy

MV3 genuinely limits enforcement. Here is what each rule action uses:

| RuleAction | Mechanism | MV3 constraint |
|---|---|---|
| **BLOCK** | `declarativeNetRequest` dynamic rule (`action: "block"`) | DNR `block` needs no host permissions for safe rules. Max 30,000 dynamic safe rules. |
| **REDIRECT** | `declarativeNetRequest` dynamic rule (`action: "redirect"` + `redirect.url` or `redirect.transform`) | Requires host permissions for request URL. Max 5,000 "unsafe" dynamic rules (redirect counts as unsafe). |
| **MODIFY_REQUEST (headers)** | `declarativeNetRequest` dynamic rule (`action: "modifyHeaders"`) | Requires host permissions. DNR can add/remove/set request headers declaratively. |
| **MODIFY_REQUEST (body)** | MAIN-world `fetch`/`XMLHttpRequest` interception — rewrite `init.body` / `send(body)` before the real call | DNR cannot modify request bodies. MAIN-world patching is the only MV3-compatible path. |
| **MODIFY_RESPONSE (headers)** | `declarativeNetRequest` dynamic rule (`action: "modifyHeaders"` on response headers) | DNR can modify response headers with `responseHeaders` in `modifyHeaders` rule. Chrome ≥ 114. |
| **MODIFY_RESPONSE (body)** | MAIN-world `fetch()` interception — replace the Response object with a `new Response(modifiedBody, {status, headers})` | DNR cannot modify response bodies. XHR response-body modification is not feasible (no Response object to swap); XHR gets response observation only. Documented: MODIFY_RESPONSE body works for fetch()-based requests, observed-only for XHR. |

**Key architectural decision:** The DNR rules serve as the "fast path" browser-enforced layer. The MAIN-world interceptor serves as the "slow path" for what DNR cannot express. Both consult the same rule cache (synced from the Android server). The interceptor checks rules on every fetch/XHR call; if a rule matches and the action can be handled by DNR alone, the interceptor can trust DNR already handled it (it checks if the request made it to `send()` at all — if DNR blocked it, `send()` never fires). For MODIFY_RESPONSE body, the interceptor must always check since DNR can't touch bodies.

---

## Bidirectional Control Channel: Design Decision

**Evaluated options:**

| Option | Pros | Cons relative to MV3 SW lifecycle |
|---|---|---|
| WebSocket | Instant push, low overhead | Connection dies on SW termination (~30s idle). Reconnect on every wake adds complexity and flapping. `fetch()` from SW to ws:// may not be reliable. |
| Long-poll | Near-real-time delivery | SW terminates during long poll unless actively processing. Requires SW to stay alive for duration of poll which fights the lifecycle. |
| **Periodic pull via `chrome.alarms`** | Survives SW termination naturally. `chrome.alarms` persists across restarts. Simple, predictable. On-popup-open immediate pull. | 30s minimum period (Chrome ≥120; 60s on older). Rule changes have up to 30s delay. |
| Poll-on-event only | Zero background overhead | No push at all — rules stale between popup opens. |

**Decision: Periodic pull via `chrome.alarms` (period 30s) + on-popup-open immediate pull + on-SW-startup immediate pull.**

- `chrome.alarms.create("rules-sync", { periodInMinutes: 0.5 })` fires the sync handler reliably across SW terminations
- On SW startup (`service-worker.mjs` top-level), trigger an immediate pull
- On popup open (`popup.mjs`), trigger an immediate pull and await result before rendering
- The `rules.mjs` module compares fetched rules against its `chrome.storage.local` cache (by `id` + `version` hash) and only updates DNR rules / interceptor cache when something changed

**New Android endpoint contract (not in this plan's scope, flagged for sibling agent):**

`GET /api/rules` — already exists at `LocalApiServer.kt` L266 but must be enhanced:
- **Auth:** Validate `Authorization: Bearer` header
- **Response body** (enhanced):
```json
{
  "rules": [
    {
      "id": 1,
      "name": "Block ads",
      "pattern": "doubleclick\\.net",
      "domain": "doubleclick.net",
      "action": "BLOCK",
      "enabled": true,
      "replacementValue": null
    },
    {
      "id": 2,
      "name": "Redirect CDN",
      "pattern": "cdn\\.example\\.com",
      "domain": "",
      "action": "REDIRECT",
      "enabled": true,
      "replacementValue": "https://local-mirror.example.com"
    },
    {
      "id": 3,
      "name": "Inject header",
      "pattern": "api\\.example\\.com",
      "domain": "",
      "action": "MODIFY_REQUEST",
      "enabled": true,
      "replacementValue": "{\"addHeaders\":{\"X-Injected\":\"true\"},\"removeHeaders\":[\"Cookie\"]}"
    },
    {
      "id": 4,
      "name": "Mock response",
      "pattern": "api\\.example\\.com/data",
      "domain": "",
      "action": "MODIFY_RESPONSE",
      "enabled": true,
      "replacementValue": "{\"statusCode\":200,\"addHeaders\":{\"X-Mock\":\"true\"},\"bodyReplace\":\"{\\\"mocked\\\":true}\"}"
    }
  ],
  "generation": 1700000000000
}
```
- `generation` is a monotonic timestamp/hash so the extension can detect changes without comparing every rule field

---

## Manifest: Exact Required Shape

The resulting `manifest.json` must be:

```json
{
  "manifest_version": 3,
  "name": "requestr Browser Bridge",
  "version": "2.0.0",
  "description": "requestr MV3 SDK: captures request/response bodies, enforces intercept rules (block/redirect/modify), and syncs bidirectionally with the requestr Android app.",
  "permissions": [
    "webRequest",
    "storage",
    "scripting",
    "activeTab",
    "declarativeNetRequest",
    "declarativeNetRequestFeedback",
    "alarms"
  ],
  "host_permissions": [
    "<all_urls>",
    "http://localhost:*/*",
    "http://127.0.0.1:*/*"
  ],
  "background": {
    "service_worker": "src/background/service-worker.mjs",
    "type": "module"
  },
  "content_scripts": [
    {
      "matches": ["<all_urls>"],
      "js": ["src/content/interceptor-main.mjs"],
      "run_at": "document_start",
      "all_frames": true,
      "world": "MAIN"
    },
    {
      "matches": ["<all_urls>"],
      "js": ["src/content/bridge.mjs"],
      "run_at": "document_start",
      "all_frames": true
    }
  ],
  "action": {
    "default_popup": "src/ui/popup.html",
    "default_title": "requestr Browser Bridge"
  },
  "options_ui": {
    "page": "src/ui/options.html",
    "open_in_tab": true
  },
  "icons": {
    "16": "icons/icon16.png",
    "48": "icons/icon48.png",
    "128": "icons/icon128.png"
  }
}
```

**Key changes from current manifest:**
- Version bump `1.0.0` → `2.0.0` (breaking: SDK module structure)
- Added `"declarativeNetRequest"`, `"declarativeNetRequestFeedback"`, `"alarms"` permissions
- Added explicit `"http://localhost:*/*"` and `"http://127.0.0.1:*/*"` to `host_permissions` (required for DNR rules targeting localhost endpoints, and ensures `fetch()` to the API server is never blocked)
- `service_worker` path updated; `"type": "module"` added
- Content script paths updated to new `src/` layout
- Added `options_ui` for full rule management page

---

## Response Body Capture: Detailed Strategy

### Fetch API

```
page calls fetch(url, init)
  → interceptor calls originalFetch(url, init)
  → originalFetch resolves with Response
  → interceptor clones: const cloned = response.clone()   ← tees the body stream
  → interceptor schedules async body read from cloned
  → interceptor returns response (the original) to page
```

**The `clone()` guarantee:** `Response.clone()` is specified to tee the underlying body stream, meaning the clone and the original each get independent copies of the body data. The page's `response.json()` or `response.text()` call on the original will work exactly as if the interceptor never touched it.

**Body reading from the clone (`src/sdk/interceptor-main.mjs`, response-capture section):**

```javascript
async function captureResponseBody(clonedResponse, url, contentType) {
  const MAX_BODY = 1_048_576; // 1 MB cap (configurable via config.mjs)
  try {
    if (!clonedResponse.body) return { body: null, bodyTruncated: false, bodyEncoding: 'none' };
    if (contentType.includes('text/event-stream')) {
      return captureSSEBody(clonedResponse, MAX_BODY);
    }
    const reader = clonedResponse.body.getReader();
    let totalSize = 0;
    const chunks = [];
    while (true) {
      const { done, value } = await reader.readWithTimeout(3000); // 3s per-chunk timeout
      if (done) break;
      totalSize += value.byteLength;
      if (totalSize > MAX_BODY) { totalSize = MAX_BODY; break; } // truncated
      chunks.push(value);
    }
    reader.releaseLock();
    const fullBuffer = concatChunks(chunks, totalSize);
    return classifyAndEncode(fullBuffer, contentType);
  } catch {
    return { body: null, bodyTruncated: false, bodyEncoding: 'error' };
  }
}
```

**Body encoding per content type:**
- `text/*`, `application/json`, `application/xml`, `application/javascript`: UTF-8 string
- `application/x-www-form-urlencoded`, `multipart/form-data`: UTF-8 string
- `image/*`, `application/octet-stream`, `application/pdf`, etc.: base64-encoded, max 256 KB before truncation (binary bodies are expensive and rarely useful to inspect inline)
- `text/event-stream`: captured as concatenated event text up to 1 MB

**The `readWithTimeout` concern:** `ReadableStreamDefaultReader.read()` has no built-in timeout. If the server never sends more data, the reader hangs. The interceptor wraps reads in a `Promise.race` against a timeout. If the timeout fires, the reader is released and the body is marked as truncated. This prevents the interceptor from hanging indefinitely on streaming responses that never end.

### XMLHttpRequest

XHR does not have `clone()`. The response is already buffered in memory when `loadend` fires. Reading `this.responseText` or `this.response` after the event is safe — the page already has access to these same properties at the same time. The interceptor reads them once and serializes.

```
xhr.addEventListener('loadend', () => {
  const body = xhr.responseText || serializeXhrBody(xhr.response, xhr.responseType);
  // dispatch with body included
})
```

For `responseType = "blob"` or `"arraybuffer"`: read `xhr.response`, encode as base64 (capped at 256 KB for binary). For `responseType = ""` or `"text"`: read `xhr.responseText`, pass as UTF-8 string. For `responseType = "document"`: serialize to XML string. For `responseType = "json"`: `JSON.stringify(xhr.response)`.

### SSE / Streaming Responses

If `content-type` is `text/event-stream`, the interceptor enters SSE mode:
1. Clone the response
2. Create a `TextDecoderStream` → `ReadableStream` pipeline on the clone
3. Accumulate events up to MAX_BODY
4. After reading, release
5. The original (returned to page) is untouched — the page's `EventSource` or manual `response.body` reader reads independently

**Important:** SSE streams can be long-lived. The interceptor caps capture at MAX_BODY bytes and then releases the clone's reader. The capture is a best-effort snapshot of the first ~1MB of the stream.

---

## Request Body Capture: Detailed Strategy

### Fetch

Before calling `originalFetch(input, init)`:
- Extract `init.body`: can be `string`, `Blob`, `ArrayBuffer`, `FormData`, `URLSearchParams`, `ReadableStream`, or `null`
- `string`, `URLSearchParams`, `FormData` → capture as UTF-8 text (FormData serialized to `multipart/form-data` boundary encoding is complex; capture as `"[FormData: N entries]"` summary or attempt serialization)
- `Blob`, `ArrayBuffer` → capture as base64 (capped at 256 KB)
- `ReadableStream` → note as `"[ReadableStream: not captured]"` — reading would consume the stream, breaking the page
- `null`/`undefined` → no body

### XMLHttpRequest

In `send(body)`:
- Capture `body` argument before calling `origSend(body)` — same rules as fetch body
- For `FormData`: `new FormData()` bodies are serializable via iteration

---

## Work Items

Below are ordered, independently-implementable work items with `[parallel]` / `[after N]` dependency annotations.

---

### Task 1: Manifest and scaffolding

**[parallel — no dependencies on other tasks]**

**Files:** `browser-extension/manifest.json` (replace)

**Change:** Rewrite manifest to the exact shape shown in the "Manifest" section above. Add `declarativeNetRequest`, `declarativeNetRequestFeedback`, `alarms` permissions. Add explicit localhost `host_permissions`. Switch service_worker to `"type": "module"` with updated path. Add `options_ui`. Update content_script paths.

**Grounding:**
- Current manifest L4-24 uses MV3 with only `webRequest`, `storage`, `scripting`, `activeTab` — no DNR, no alarms. `host_permissions` L15-17 only `<all_urls>`.
- `chrome.declarativeNetRequest` docs: permission is required for `updateDynamicRules`/`updateSessionRules`. `declarativeNetRequestFeedback` needed for `onRuleMatchedDebug`.
- `chrome.alarms` docs: permission required for `chrome.alarms.create()`.
- Service worker `"type": "module"` supported since Chrome 91, required for ES `import`/`export`.

**Verification:** Load unpacked extension; confirm no manifest parse errors in `chrome://extensions`; confirm service worker shows as "module" type in the SW console; confirm `chrome.alarms` and `chrome.declarativeNetRequest` are accessible in the SW console.

---

### Task 2: SDK module bootstrap — version, config, utils

**[parallel — no dependencies except manifest exists]**

**Files:**
- `browser-extension/src/sdk/version.mjs` (new)
- `browser-extension/src/sdk/config.mjs` (new)
- `browser-extension/src/sdk/utils.mjs` (new)
- `browser-extension/src/sdk/index.mjs` (new)

**Changes:**

`version.mjs`: export `const SDK_VERSION = "2.0.0"` and `const SDK_BUILD = "<date>"`.

`config.mjs`: Config module backed by `chrome.storage.local`. Exports:
- `async getConfig()` → `{ apiBase, token, maxBodyBytes, flushIntervalMs, enforcementEnabled, lastRulesGeneration }`
- `async setApiBase(url)` — persists, notifies listeners
- `async setToken(t)` — persists
- `async setEnforcementEnabled(bool)` — persists
- `async setLastRulesGeneration(gen)` — persists
- Defaults: `apiBase = "http://localhost:9876"`, `maxBodyBytes = 1_048_576`, `flushIntervalMs = 2000`, `enforcementEnabled = true`
- Subscribe pattern: `onConfigChange(listener)` using `chrome.storage.onChanged`

`utils.mjs`: Shared helpers:
- `backoffDelay(attempt)` → exponential backoff: `min(1000 * 2^attempt, 30000)` milliseconds
- `bodyToBase64(arrayBuffer, maxBytes)` → capped base64 string
- `concatChunks(chunks, totalSize)` → Uint8Array
- `serializeHeaders(headers)` → plain object (handles `Headers`, `Array`, `object`)
- `hashString(str)` → simple FNV-1a hash for change detection
- `toStorageJson(event)` → JSON-safe serialization of observer events (handles undefined/null/Blob references)
- `fromStorageJson(str)` → deserialization
- `debounce(fn, ms)` — for flush coalescing

`index.mjs`: Re-exports all public symbols. Public API:
- `init(config?)` — boot the SDK (configures apiBase, starts alarms, pulls rules)
- `getStatus()` — returns current status blob
- `connect()` — alias for init + pairing handshake
- `disconnect()` — clear token, stop enforcement
- `onEvent(callback)` — subscribe to observed events (for popup/options live feed)
- `getRules()` — return cached rules
- `refreshRules()` — force immediate rules pull

**Verification:** Load extension, call `chrome.storage.local.get(null)` in SW console, verify default config keys exist.

---

### Task 3: Persistent event queue with alarm-based flush

**[after 1] (needs manifest with alarms permission)**

**Files:**
- `browser-extension/src/sdk/queue.mjs` (new)
- Modify `browser-extension/src/background/service-worker.mjs` (new)

**Changes:**

`queue.mjs`:
- Queue stored in `chrome.storage.session` (survives SW termination, 10 MB quota, no content-script access needed)
- Key: `"eventQueue"` → array of serialized event objects
- `enqueue(event)`: serialize event, push to in-memory array, persist to `chrome.storage.session.set({ eventQueue: [...] })`, flush if ≥ maxBatch
- `flush()`: read queue from storage, POST to `${apiBase}/api/observe` with `Authorization: Bearer` header, on success remove from storage, on failure keep in storage + increment retry counter. Retry with exponential backoff: each event tracks `retries` count, events with retries > 5 are moved to dead-letter storage (key `"deadQueue"`) with a timestamp
- `purgeDead()`: removes items from dead-letter older than 1 hour
- `drain()`: flush everything immediately (called on SW shutdown if possible)
- Memory bound: max 500 queued events (older events in a burst are dropped with a counter `droppedCount`); max 200 dead-letter events
- Alarm registration: uses `chrome.alarms.create("queue-flush", { periodInMinutes: 0.5 })` — since the minimum alarm period is 30s, this fires on a regular cadence independent of SW lifecycle. The alarm handler calls `flush()`.
- **Why not `setInterval`:** `background.js` L181 uses `setInterval(flush, 2000)` — this dies when the SW terminates after ~30s idle. `chrome.alarms` persists across SW restarts.
- **Why not `chrome.storage.local` for queue:** `chrome.storage.session` is in-memory and faster; the queue is ephemeral by nature (events are eventually flushed or expire). `chrome.storage.session` quota (10 MB) is sufficient for the bounded queue.

**`service-worker.mjs` entry point:**
```javascript
import { initConfig } from '../sdk/config.mjs';
import { startQueue, drainQueue } from '../sdk/queue.mjs';
import { startObserver } from '../sdk/observer.mjs';
import { initChannel } from '../sdk/channel.mjs';
import { initPairing } from '../sdk/pairing.mjs';

// Top-level (runs on every SW start, including after termination)
(async () => {
  const config = await initConfig();
  await initPairing(config);
  startObserver(config);
  initChannel(config);
  startQueue(config);
  // register alarms
  chrome.alarms.create('queue-flush', { periodInMinutes: 0.5 });
  chrome.alarms.create('rules-sync', { periodInMinutes: 0.5 });
  chrome.alarms.create('health-check', { periodInMinutes: 1 });
})();

chrome.alarms.onAlarm.addListener(async (alarm) => {
  switch (alarm.name) {
    case 'queue-flush': await drainQueue(); break;
    case 'rules-sync': await syncRules(); break;
    case 'health-check': await checkServerHealth(); break;
  }
});
```

**Grounding:**
- `background.js` L37-41: Uses `let` globals — these are wiped on SW termination
- `background.js` L162-179: `flush()` drops failed batches silently and uses `setInterval` — both fail across SW restarts
- `background.js` L177: `lastError = String(e…)` — no retry logic whatsoever
- Chrome alarms minimum period: 30s since Chrome 120; `periodInMinutes: 0.5` = 30s

**Verification:**
1. Load extension, browse to a site, kill the SW from `chrome://extensions` → "Inspect views: service worker"
2. Verify the SW restarts and `chrome.storage.session` queue is intact
3. Verify alarm fires and flush succeeds on next alarm tick
4. Verify events appear in requestr's Intercept log even after SW kill/restart

---

### Task 4: Pairing / token-based auth

**[after 2] (needs config.mjs)**

**Files:**
- `browser-extension/src/sdk/pairing.mjs` (new)

**Changes:**

`pairing.mjs`:
- `initPairing(config)`: checks `config.token`; if absent, generates one
- Token generation: `crypto.randomUUID()` (available in Chrome SW) → store via `config.setToken(uuid)`
- `performHandshake(config)`: POST to `${apiBase}/api/pair` with body `{"deviceName":"Chrome on <OS>","token":"<uuid>"}`. Expect response `{"accepted":true,"token":"<uuid>"}`. On success, mark token as `paired: true` in config.
- `getAuthHeaders()`: returns `{"Authorization": "Bearer <token>"}` — used by queue, channel, observer for all API calls
- `isPaired()`: returns whether pairing has been accepted
- `unpair()`: POST to `${apiBase}/api/unpair`, clear token from storage

**Android-side endpoint (boundary — flagged for sibling agent):**

`POST /api/pair` — new endpoint in `LocalApiServer.kt`:
- Request: `{"deviceName": "Chrome on Linux x86_64", "token": "550e8400-e29b-41d4-a716-446655440000"}`
- Logic: If no token is currently paired, store token and device name in a new `DataStore` preference or Room table `paired_extensions`. Return `{"accepted": true, "token": "<same>"}`.
- If a token is already paired, return `{"accepted": false, "reason": "already paired — unpair first"}`
- On Android app settings UI: show paired extension, allow unpair

`DELETE /api/unpair` — clears the stored token.

**Auth on existing endpoints:** `receiveObserverEvents()` at `LocalApiServer.kt` L287 and `listRules()` at L266 must check `Authorization: Bearer` header against stored token. Unauthenticated requests → 401.

**Grounding:**
- `LocalApiServer.kt` L127-153 `route()`: has zero authentication checks. The `when` block at L129-152 dispatches directly with no `Authorization: Bearer` extraction.
- `receiveObserverEvents()` L287-326: accepts all POSTs without validation. Any local process (not just the extension) can inject data.
- Sibling SDK plan (Android `RequestrObserver`) shares this concern — the token scheme must be identical between the obfuscation SDK and the extension SDK.

**Verification:**
1. Load extension fresh (no token) → check SW console shows token generated
2. Verify `POST /api/observe` from extension includes `Authorization: Bearer` header
3. Verify `GET /api/rules` from extension includes `Authorization: Bearer` header
4. With `/api/pair` implemented on Android: confirm pairing handshake succeeds; confirm rejecting pairing shows error in extension popup

---

### Task 5: Bidirectional channel — rules pull

**[after 4] (needs pairing for auth headers)**

**Files:**
- `browser-extension/src/sdk/channel.mjs` (new)

**Changes:**

`channel.mjs`:
- `initChannel(config)`: starts the rules-sync cycle
- `syncRules()`: called by alarm + on popup open + on SW startup
  1. GET `${apiBase}/api/rules` with `Authorization: Bearer` header
  2. Compare response `generation` against stored `lastRulesGeneration`
  3. If unchanged, skip
  4. If changed, update `chrome.storage.local` cache (`key: "cachedRules"`), then call `enforcer.syncDnrRules(rules)` and `enforcer.updateInterceptorRules(rules)`
- `healthCheck()`: GET `${apiBase}/api/ping` with token; update `chrome.storage.local` `lastHealthOk` and `lastHealthAt` for popup display
- `getConnectionStatus()`: returns `{ connected: bool, lastHealthAt, paired: bool, rulesCount }` for popup

**Android-side dependency:** `listRules()` at `LocalApiServer.kt` L266 must be enhanced to include `replacementValue` and `domain` in the response (currently L271 only emits `id/name/action/pattern/enabled`). And must add auth check.

**Verification:**
1. Create a rule in requestr on Android
2. Wait up to 30s (or open popup to force pull)
3. Verify extension popup shows the new rule
4. Verify SW console logs rule sync

---

### Task 6: Rule engine and DNR synchronization

**[after 5] (needs channel for rule data)**

**Files:**
- `browser-extension/src/sdk/enforcer.mjs` (new)
- `browser-extension/src/sdk/rules.mjs` (new)

**Changes:**

`rules.mjs`:
- `syncDnrRules(rules)`:
  1. Iterate enabled rules
  2. For each BLOCK rule: create DNR rule `{id: ruleId, priority: 1, action: {type: "block"}, condition: {urlFilter: pattern || domain || "*", resourceTypes: ["main_frame","sub_frame","xmlhttprequest","script","stylesheet","image","font","media","websocket","other"]}}`
  3. For each REDIRECT rule: create DNR rule `{id: ruleId, priority: 1, action: {type: "redirect", redirect: {url: replacementValue}}, condition: {urlFilter: pattern || domain, resourceTypes: [...]}}` — **Note:** DNR redirect `url` must be a complete URL; if `replacementValue` contains `{url}` placeholder, use `regexSubstitution` instead
  4. For each MODIFY_REQUEST (headers only): create DNR rule `{id: ruleId, priority: 1, action: {type: "modifyHeaders", requestHeaders: [{header: headerName, operation: "set", value: headerValue}, ...]}, condition: {...}}`
  5. Call `chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds: oldIds, addRules: newRules })`
  6. `oldIds` tracked in memory from previous sync; new rules replace old
- `getInterceptorRules(rules)`: returns subset of rules requiring MAIN-world enforcement: MODIFY_REQUEST with bodyReplace, all MODIFY_RESPONSE rules, and any rule where pattern needs regex evaluation (DNR `regexFilter` has limits)
- Rule ID mapping: use `rule.id` directly as DNR rule ID (Room auto-increment IDs are small enough to fit in DNR's integer ID space)

`enforcer.mjs`:
- Mirrors `RuleEnforcer.kt` logic in JS
- `checkRules(rules, url, host)`: returns first matching enabled rule or `null`. Matching: `rule.domain && host.toLowerCase().includes(rule.domain.toLowerCase())` OR `rule.pattern && new RegExp(rule.pattern, 'i').test(url)`
- `applyRequestModification(init, config)`: parses JSON config (same shape as `RuleEnforcer.kt` L57-78: `addHeaders`, `removeHeaders`, `bodyReplace`). Mutates `init.headers` and `init.body`.
- `applyResponseModification(response, config)`: parses JSON config (same shape as `RuleEnforcer.kt` L84-100). Returns `{ statusCode, headers, body }` for constructing `new Response()`.

**Grounding:**
- `RuleEnforcer.kt` L29-51: domain substring match + pattern regex match, first-enabled-wins
- `RuleEnforcer.kt` L57-78, L84-100: exact JSON config shapes for modification
- `CdpFetchAdapter.kt` L95-98: REDIRECT uses `rule.replacementValue` as target URL
- DNR dynamic rule limits: 30,000 safe rules, 5,000 unsafe (redirect/modifyHeaders count as unsafe)
- DNR `regexFilter` limit: 1,000 regex rules total across all dynamic rules; if rules exceed this, fall back to interceptor-only for regex-based rules

**Verification:**
1. Create rules in requestr with each action type
2. Verify DNR rules appear in `chrome.declarativeNetRequest.getDynamicRules()`
3. For BLOCK: visit matching URL → verify blocked (error page or network error)
4. For REDIRECT: visit matching URL → verify redirected to replacementValue URL
5. For MODIFY_REQUEST headers: verify DNR rules have `modifyHeaders` action
6. For MODIFY_RESPONSE: verify rules appear in interceptor rule cache (not DNR)

---

### Task 7: MAIN-world interceptor with body capture and response modification

**[parallel with Task 6, after 2] (needs config.mjs and enforcer.mjs for rule consultation)**

**Files:**
- `browser-extension/src/sdk/interceptor-main.mjs` (new — the canonical interceptor logic)
- `browser-extension/src/content/interceptor-main.mjs` (new — thin wrapper that loads interceptor into MAIN world)

**Changes:**

This is the largest single file. It replaces `page-interceptor.js` entirely and adds real body capture + enforcement.

**Architecture:**

`content/interceptor-main.mjs` is the content-script entry injected via `"world": "MAIN"`. Since MAIN world has no `chrome.*` API access but can still use `window.postMessage`, the content script entry cannot import SDK modules. The approach:

1. `src/sdk/interceptor-main.mjs` is the canonical source of interceptor logic. It's written as an IIFE that attaches to `window.__requestr_interceptor__`.
2. `src/content/interceptor-main.mjs` is the actual file loaded by Chrome. It loads the interceptor logic. At build/deploy time, it's the same file. If `import()` or dynamic script injection from MAIN world is needed for code sharing, the service worker can execute `chrome.scripting.executeScript({ world: 'MAIN', func: interceptorBootstrap })` to inject the compiled logic.

**Simplest working approach (recommended):** `src/content/interceptor-main.mjs` contains the full interceptor logic as a self-contained IIFE. `src/sdk/interceptor-main.mjs` is a module-format re-export of the same logic for use by the service worker if needed. At implementation time, both files are kept in sync manually or via a concatenation step.

**The interceptor logic (what goes in the MAIN-world file):**

**A. Boot:**
```javascript
(function() {
  'use strict';
  const EVENT_NAME = '__requestr_fetch_observed__';
  const RULES_EVENT = '__requestr_rules_sync__';
  const MAX_BODY = 1048576; // 1MB cap

  let cachedRules = []; // populated by bridge via postMessage
  let enforcementEnabled = true;

  // Listen for rule updates from isolated-world bridge
  window.addEventListener('message', (e) => {
    if (e.source !== window) return;
    if (e.data?.type === '__requestr_rules_update__') {
      cachedRules = e.data.rules || [];
      enforcementEnabled = e.data.enforcementEnabled !== false;
    }
  });
})();
```

**B. Fetch interception (replaces L267-334):**

Before calling `originalFetch`:
1. Determine URL and method (same as current L269-280)
2. Extract headers (same as current L283-297)
3. **NEW: Capture request body** from `init.body`
4. **NEW: Check rules** against `cachedRules`. If matching rule found:
   - `BLOCK`: dispatch a blocked event, call the real fetch but return a synthetic `Response` with 403 status — or better, throw a TypeError like a network failure (consistent with DNR block behavior). Actually, for MAIN-world BLOCK when DNR didn't catch it: reject with a new Error (abort-like). Return `Promise.reject(new DOMException('Blocked by requestr rule', 'AbortError'))` — this is the same error pattern page code expects from aborted requests.
   - `REDIRECT`: if DNR didn't already redirect, rewrite `url` to `replacementValue`. But DNR should handle redirects — interceptor REDIRECT is a backstop.
   - `MODIFY_REQUEST`: apply header/body modifications per `replacementValue` JSON config
   - `MODIFY_RESPONSE`: note that response must be intercepted after resolution
   - `LOG_ONLY`: no action

After calling `originalFetch` and `.then(response)`:
1. **NEW: Clone response** `const clone = response.clone()`
2. **NEW: Schedule async body capture** from clone (as described in "Response Body Capture Strategy")
3. **NEW: If MODIFY_RESPONSE rule matched**, construct `new Response(modifiedBody, { status: modifiedStatus, headers: modifiedHeaders })` and return it instead. Return the clone (or original if no modification).
4. Dispatch event via `CustomEvent` with full body data

**C. XMLHttpRequest interception (replaces L337-381):**

Same pattern as current but with body capture added to `send()` and response body reading in `loadend`.

`open()`: capture method, URL, store on `this.__requestr`
`setRequestHeader()`: capture headers (same as L350-353)
`send(body)`: **NEW: capture request body**; **NEW: check rules**; if BLOCK → `this.abort()` + dispatch blocked event; if MODIFY_REQUEST → apply modifications to body argument
`loadend`: **NEW: read response body** from `this.responseText` or `this.response`; **NEW: if MODIFY_RESPONSE** → cannot replace XHR response object (no `Response` to swap). Apply `statusCode` override by monkey-patching `this.status` getter. Apply `bodyReplace` by monkey-patching `this.responseText`/`this.response`. **Documented limitation:** XHR response-body modification is best-effort and may not work for all access patterns (e.g., `responseXML` parsed before `loadend`).

**D. Event dispatch:**

Expand the event schema dispatched via `CustomEvent` from the current (L309-317):
```json
{
  "url": "...",
  "method": "GET",
  "headers": {...},
  "statusCode": 200,
  "bodySize": 1234,
  "durationMs": 56,
  "timestamp": 1700000000000,
  "requestBody": "string or base64 or null",
  "requestBodyEncoding": "utf8|base64|none",
  "requestBodyTruncated": false,
  "responseBody": "string or base64 or null",
  "responseBodyEncoding": "utf8|base64|none",
  "responseBodyTruncated": false,
  "initiator": "https://example.com/page",
  "ruleAction": "MODIFY_RESPONSE",
  "ruleId": 3,
  "ruleName": "Mock API response"
}
```

**Grounding:**
- `page-interceptor.js` L267-334: Current fetch patch captures only headers/status — no body
- `page-interceptor.js` L299: `originalFetch.apply(this, arguments).then(response => { … dispatch(…) })` — response flows through `.then()` but is returned to page unchanged at L318
- `page-interceptor.js` L303: `response.headers.get("content-length")` — only uses Content-Length header, never reads actual body
- `RuleEnforcer.kt` L57-78, L84-100: JSON config shapes to mirror in JS

**Verification:**
1. Load `test/test-page.html` which makes fetch requests with JSON bodies
2. Verify response bodies appear in dispatched events
3. Verify the page still receives uncorrupted responses (call response.json(), response.text(), etc. on the returned response)
4. Create a MODIFY_RESPONSE rule → verify response is replaced
5. Create a MODIFY_REQUEST rule with bodyReplace → verify PAGE's fetch sends the modified body (inspect via the test page's own logging)
6. Test SSE endpoint → verify body capture works and page EventSource still receives events
7. Test blob response → verify page receives blob correctly

---

### Task 8: Isolated-world bridge

**[after 7] (needs interceptor to define event shape)**

**Files:**
- `browser-extension/src/content/bridge.mjs` (new, replaces `content.js`)

**Changes:**

Replaces the current `content.js` entirely. The bridge has two directions:

**A. MAIN-world → service worker (event relay):**
Same pattern as current `content.js` L216-229: listen for `__requestr_fetch_observed__` CustomEvent, relay via `chrome.runtime.sendMessage`. The event payload now includes body fields.

**B. Service worker → MAIN-world (rule/configuration push):**

The bridge must push rules and enforcement settings to the MAIN-world interceptor. Since MAIN-world has no `chrome.runtime.onMessage`, and the isolated-world content script has no direct access to MAIN-world JS objects:

1. Service worker sends a message to the content script via `chrome.tabs.sendMessage(tabId, { type: 'rules-update', rules, enforcementEnabled })` — or broadcasts to all tabs
2. Bridge receives via `chrome.runtime.onMessage.addListener`
3. Bridge forwards to MAIN world via `window.postMessage({ type: '__requestr_rules_update__', rules, enforcementEnabled })` — `window.postMessage` crosses the ISOLATED → MAIN boundary because both worlds share the same DOM `window` object, though they have separate JS contexts
4. Interceptor-main.mjs listens for `window.addEventListener('message', …)` as described in Task 7 section A

**Important:** `chrome.tabs.sendMessage` requires knowing tab IDs. The bridge registers each tab on load via `chrome.runtime.connect({ name: 'tab-' + tabId })` or the service worker queries `chrome.tabs.query({})` to get active tabs.

**Alternative for broadcasting rules:** Use `chrome.storage.onChanged` on `chrome.storage.session` (after calling `setAccessLevel({ accessLevel: 'TRUSTED_AND_UNTRUSTED_CONTEXTS' })` from the service worker). The bridge listens for storage changes and forwards to MAIN world. This avoids tab-ID tracking.

**Decision:** Use `chrome.storage.session` with `setAccessLevel('TRUSTED_AND_UNTRUSTED_CONTEXTS')` for rule broadcast. After rules sync, the service worker writes to `chrome.storage.session` key `"interceptorRules"`. The bridge's `chrome.storage.onChanged` listener picks up changes and forwards via `window.postMessage`.

**Content script registration note:** The bridge must be injected into all frames (existing `all_frames: true` in manifest). The rule-forwarding `postMessage` goes to `window` which is frame-specific — so each frame's interceptor instance gets rules independently.

**Verification:**
1. After rules sync, verify `chrome.storage.session` has `interceptorRules` key
2. Open a page, inspect MAIN-world console → verify `__requestr_rules_update__` message is received
3. Create a new rule → verify it arrives in the MAIN-world interceptor within 30s (next alarm) or immediately on popup-open pull

---

### Task 9: Observer — webRequest normalization with body schema

**[after 7, 3] (needs interceptor body schema and queue)**

**Files:**
- `browser-extension/src/sdk/observer.mjs` (new)

**Changes:**

Replaces the webRequest observation logic currently in `background.js` L50-133. Adds the interceptor message relay currently at L137-153.

`observer.mjs`:
- `startObserver(config)`: registers webRequest listeners (same pattern as current L59-133) and `chrome.runtime.onMessage` listener for interceptor events
- WebRequest events emit the same schema but with `requestBody: null, responseBody: null, bodyEncoding: "none", source: "webRequest"` — webRequest cannot capture bodies in MV3
- Interceptor events pass through as-is (already have body fields, source: "interceptor")
- All events are normalized to the same schema and fed to `queue.enqueue()`
- `chrome.runtime.onMessage` handler (replaces L137-153): processes `type: "fetch-observed"` messages from bridge → enqueue; `type: "get-status"` → returns full status (extends current L143-152 to include rules count, enforcement status, pairing status, body captures count)

**Event schema (the single canonical shape):**
```json
{
  "url": "string",
  "method": "GET",
  "requestHeaders": {"Header-Name": "value"},
  "statusCode": 200,
  "responseHeaders": {"Content-Type": "application/json"},
  "bodySize": 1234,
  "durationMs": 56,
  "timestamp": 1700000000000,
  "requestBody": "string|null",
  "requestBodyEncoding": "utf8|base64|none",
  "requestBodyTruncated": false,
  "responseBody": "string|null",
  "responseBodyEncoding": "utf8|base64|none",
  "responseBodyTruncated": false,
  "initiator": "https://example.com/page",
  "source": "webRequest|interceptor",
  "ruleAction": "LOG_ONLY|BLOCK|REDIRECT|MODIFY_REQUEST|MODIFY_RESPONSE",
  "ruleId": null,
  "ruleName": null
}
```

**Android-side dependency:** `receiveObserverEvents()` at `LocalApiServer.kt` L287-326 must be extended to consume the new fields (`requestBody`, `responseBody`, `responseHeaders`, `requestBodyEncoding`, `responseBodyEncoding`) and persist them into `CapturedTrafficEntity.requestBody` / `CapturedTrafficEntity.responseBody` (which already exist at `Entities.kt` L28-29 but are never populated by the current extension path).

**Verification:**
1. Load extension, browse to a page
2. Confirm events flow to requestr with body fields populated
3. Confirm webRequest events and interceptor events both appear, with source field distinguishing them

---

### Task 10: Popup redesign

**[after 9, 6, 4] (needs status API, rules cache, pairing status)**

**Files:**
- `browser-extension/src/ui/popup.html` (replace)
- `browser-extension/src/ui/popup.mjs` (replace)
- `browser-extension/src/ui/popup.css` (new)

**Changes:**

Complete redesign from the current 104-line minimal popup. Two-tab layout with `[Status]` and `[Rules]` tabs.

**Status tab:**
- Connection dot + text (green=Connected, amber=Paired but server unreachable, red=Unpaired/Error, gray=Checking)
- Pairing status: "Paired to requestr" or "Not paired — pair from Settings" with a [Pair] button
- Events observed: count (total since load)
- Bodies captured: count (events with non-null body)
- Queued unsent: count
- Last error: text + timestamp
- Enforcement toggle: ON/OFF switch (persists to `chrome.storage.local`)
- API base input + [Save] button (existing, preserved)
- [Refresh Rules Now] button (forces immediate `syncRules()`)

**Rules tab:**
- List of active rules from cache: name, action badge (BLOCK=red, REDIRECT=amber, MODIFY_REQUEST=blue, MODIFY_RESPONSE=purple, LOG_ONLY=gray), pattern, enabled state
- Click rule → expand to show replacementValue
- "N rules active" header
- [Open Options] link to full options page

**CSS:** Dark theme consistent with current popup (background `#111827`, text `#e5e7eb`, indigo accent `#4f46e5`). Responsive to popup width (min 320px, max 400px).

**Verification:**
1. Open popup → status tab shows connection + pairing state
2. Toggle enforcement OFF → verify interceptor stops enforcing rules (but still observes)
3. Click [Refresh Rules Now] → verify rules list updates within 2 seconds
4. Switch to Rules tab → verify rules from Android appear with correct action badges
5. Change API base in input → save → verify subsequent API calls use new base

---

### Task 11: Options page

**[after 10] (can reuse popup patterns)**

**Files:**
- `browser-extension/src/ui/options.html` (new)
- `browser-extension/src/ui/options.mjs` (new)
- `browser-extension/src/ui/options.css` (new)

**Changes:**

Full-page options (opens in new tab via `options_ui.open_in_tab: true`). Panels:

**A. Connection:**
- API server address + port field
- Pairing token display (truncated) + [Copy] [Regenerate] [Unpair] buttons
- Server health: last ping timestamp, latency

**B. Rules (read-only view of server rules):**
- Full rule table: id, name, action, pattern, domain, enabled, replacementValue (truncated, expandable)
- "Edit rules in the requestr app" note
- Count: total, enabled, by action type

**C. Traffic Log:**
- Last 200 events observed (from `chrome.storage.session` key `"recentEvents"` populated by observer)
- Color-coded by source (webRequest=gray, interceptor=blue) and action (BLOCK=red row)
- Body columns: request body size, response body size, click to expand body content
- Filter: by URL, method, status, has-body
- [Clear log] button

**D. Settings:**
- Max body capture size (KB) — slider 64 KB → 2048 KB
- Flush interval — dropdown (2s, 5s, 10s, 30s)
- Max queue size — dropdown (100, 200, 500)
- Enforcement defaults — ON/OFF for new tabs

**Verification:**
1. Open options page → all panels render
2. Verify traffic log populates in real-time (polling from `chrome.storage.session`)
3. Verify body content expansion works (click to see captured response body)
4. Change settings → verify they persist and take effect

---

### Task 12: Update `LocalApiServer.kt` / Android-side contracts

**[FLAGGED — this is an Android-side task outside MV3 scope, documented for sibling agent]**

**Files:**
- `app/src/main/java/com/example/api/LocalApiServer.kt`

**Required changes (boundary items):**

1. **Add `POST /api/pair` endpoint** — Accept `{"deviceName":"string","token":"string"}`, validate/accept pairing, store token in DataStore/Room, return `{"accepted":true,"token":"..."}` or `{"accepted":false,"reason":"already paired"}`

2. **Add `DELETE /api/unpair` endpoint** — Clear stored token

3. **Add token validation middleware** — Extract `Authorization: Bearer` header from incoming requests in `handleClient()` (L89-123), validate against stored token, reject with 401 if missing/invalid

4. **Enhance `GET /api/rules` response** — `listRules()` at L266: include `replacementValue` and `domain` fields in JSON output. Current L271 emits only `id/name/action/pattern/enabled` — must add `replacementValue` and `domain`.

5. **Extend `POST /api/observe` schema** — `receiveObserverEvents()` at L287: consume new optional fields `requestBody`, `responseBody`, `responseHeaders`, `requestBodyEncoding`, `responseBodyEncoding` and persist into `CapturedTrafficEntity.requestBody`/`responseBody`/`responseHeaders` (columns already exist at `Entities.kt` L26-29)

6. **Add `generation` field to `/api/rules` response** — A monotonic `Long` (e.g., `System.currentTimeMillis()` on each rule mutation) so the extension can detect changes without full comparison.

**Grounding:**
- `LocalApiServer.kt` L127-153: `route()` has zero auth — confirmed by file read
- `LocalApiServer.kt` L266-273: `listRules()` response shape — current output confirmed at L268-272
- `Entities.kt` L26-29: `CapturedTrafficEntity` has `requestHeaders/responseHeaders/requestBody/responseBody` — columns exist but are never populated by extension path

---

### Task 13: Integration test page

**[after 7, 8, 9] (needs full interceptor + bridge + observer to be testable)**

**Files:**
- `browser-extension/test/test-page.html` (new)

**Changes:**

A self-contained HTML page that exercises every interceptor path and visually reports pass/fail.

**Test cases:**

| # | Test | What it verifies |
|---|---|---|
| 1 | `fetch GET` with JSON response | Response body captured, page receives correct response |
| 2 | `fetch POST` with JSON body | Request body captured, server receives correct body |
| 3 | `fetch` with blob response | Page receives blob, body captured as base64 |
| 4 | `fetch` with `ReadableStream` response (SSE simulation) | Page receives stream, body captured truncated |
| 5 | `XMLHttpRequest GET` | Response body captured via responseText |
| 6 | `XMLHttpRequest POST` | Request body captured |
| 7 | `fetch` → BLOCK rule | Request blocked (rejected/aborted), blocked event dispatched |
| 8 | `fetch` → MODIFY_REQUEST rule (addHeaders) | Modified headers sent to server |
| 9 | `fetch` → MODIFY_RESPONSE rule (bodyReplace) | Page receives modified body, original body discarded |
| 10 | Concurrency: 5 simultaneous fetches | No interleaving corruption, all responses correct |
| 11 | Slow response (server delay 5s) | Duration captured correctly, reader doesn't hang |

**Infrastructure:** The test page runs a tiny in-page "mock server" using a `ServiceWorker` registered by the test page itself, or simpler: uses `fetch` to real public endpoints with predictable responses (e.g., `https://httpbin.org/get`, `https://httpbin.org/post`, `https://httpbin.org/stream/5`). Rule enforcement tests use manually inserted in-memory rules via `window.postMessage` injecting `__requestr_rules_update__` into the page.

**Verification:**
1. Load `test-page.html` in Chrome
2. Open extension's SW console → observe events flowing
3. Check test-page visual output → all 11 tests should show green/pass
4. Check requestr Intercept screen → body data present in captured traffic

---

### Task 14: README update

**[after 13] (documentation reflects completed implementation)**

**Files:** `browser-extension/README.md` (replace)

**Changes:** Rewrite to document the new architecture, module layout, capabilities (body capture, enforcement, pairing, bidirectional channel), loading instructions (unchanged), and the integration test page. Remove all "Known limitations" sections that the new implementation addresses. Add new limitations only where MV3 truly requires them (XHR response-body modification is best-effort, streaming bodies are truncated at 1MB, DNR regex rule limit of 1,000).

---

## Testing

**Unit/standalone testing approach:** No npm/node test harness exists in the repo. The repo is an Android project with Gradle. Propose:

1. **Manual integration verification** via the `test/test-page.html` suite (Task 13) — covers all interceptor paths
2. **Chrome DevTools-based debugging** — extension's SW console, `chrome://extensions` → "Inspect views" for popup/options
3. **Android-side integration** — verify events appear in requestr's Intercept screen with body data populated, rules sync bidirectionally
4. **Edge case checklist** (manual, documented in test section of each task):
   - SW termination and restart → queue survives, alarms re-register
   - No Android server running → queue persists events, retries with backoff when server returns
   - Server on non-standard port → popup API base config takes effect
   - Multiple tabs → events from all tabs captured and attributed correctly
   - iframes → `all_frames: true` ensures interceptor loads in subframes
   - Large binary response (>1MB) → captured as truncated base64, page receives full response
   - DNR rule limit exceeded → interceptor-only fallback for excess rules

---

## Risk / Integration Notes

| Risk | Mitigation |
|---|---|
| `chrome.storage.session` is cleared on extension update/disable | Queue is ephemeral by nature; events are eventually flushed. Survives SW termination but not extension update. Acceptable — extension updates are rare. |
| DNR redirect requires host permissions for request URL | Extension has `<all_urls>` — suffices. `http://localhost:*/*` explicitly included for API server. |
| DNR `regexFilter` limit of 1,000 rules | Most rules use `urlFilter` (substring match). Only `pattern` rules using regex syntax need `regexFilter`. Fallback to interceptor-only for excess regex rules. |
| Service worker may not receive `chrome.tabs.sendMessage` for tabs loaded before SW start | Use `chrome.storage.session` for rule broadcast to content scripts — doesn't require tab targeting. |
| MAIN-world `fetch` patch breaks pages that call `fetch.toString()` or compare `fetch === window.fetch` | Avoid wrapping `fetch` in a way that changes `.toString()`. Use `Object.defineProperty` with `configurable: true` + `writable: true` to replace but preserve inspectability where possible. Document: some sites may detect the patch. |
| `Response.clone()` creates a new `ReadableStream` reader but some CDN-optimized responses use locked streams | Wrapped in try/catch. If clone fails, body capture is skipped, page receives original response. |
| `replacementValue` for REDIRECT may contain placeholders | DNR's `regexSubstitution` supports capture groups via `\1`, `\2`. The `rules.mjs` DNR sync detects `{url}` or regex capture patterns and generates appropriate `regexSubstitution` rules. |

---

## Dependency Graph

```
Task 1 (manifest) ─────────────────────────────────────────────────────────────┐
   │                                                                             │
   ├─ Task 2 (sdk bootstrap: version, config, utils, index)                     │
   │     │                                                                       │
   │     ├─ Task 3 (queue + alarms) ─────────────────────────────────────┐      │
   │     │     │                                                          │      │
   │     │     └─ Task 9 (observer) ──────────────────────────────────┐   │      │
   │     │                                                              │   │      │
   │     ├─ Task 4 (pairing) ──────────────────────────────────────────┤   │      │
   │     │     │                                                        │   │      │
   │     │     └─ Task 5 (channel/rules pull) ─────────────────────────┤   │      │
   │     │           │                                                   │   │      │
   │     │           └─ Task 6 (enforcer + DNR sync) ───────────────────┤   │      │
   │     │                                                                │   │      │
   │     ├─ Task 7 (interceptor with body capture) ──────────────────────┤   │      │
   │     │     │                                                          │   │      │
   │     │     └─ Task 8 (bridge) ───────────────────────────────────────┤   │      │
   │     │                                                                │   │      │
   │     └────────────────────────────────────────────────────────────────┘   │      │
   │                                                                             │      │
   └─ Task 10 (popup) ────────────────── (depends on 9+6+4+2)                    │      │
         │                                                                        │      │
         └─ Task 11 (options page) ── (depends on 10)                            │      │
                                                                                   │      │
   Task 12 (Android endpoints) ── FLAGGED for sibling agent ──────────────────────┤      │
                                                                                   │      │
   Task 13 (test page) ── (depends on 7+8+9) ─────────────────────────────────────┘      │
                                                                                          │
   Task 14 (README) ── (depends on 13) ───────────────────────────────────────────────────┘
```

**Parallel execution opportunities:**
- Tasks 1 + 2: parallel (manifest and sdk bootstrap are independent)
- Tasks 6 + 7: parallel (enforcer and interceptor are largely independent — enforcer needs rule schema from channel, interceptor needs config — but both can be developed once Task 4/5 define the contract)
- Task 11: can start once Task 10 establishes patterns, but not fully parallel
- Task 12: FULLY parallel — Android-side, different agent

---

## Blocking Questions

1. **XHR response modification:** The plan documents MODIFY_RESPONSE for XHR as "best-effort, monkey-patching `responseText`/`status` after `loadend`." This is inherently imperfect. Should we instead explicitly NOT support MODIFY_RESPONSE for XHR (documenting it as fetch-only for response modification), or attempt the best-effort approach? **Recommendation:** Attempt best-effort — it works for the common case (synchronous `responseText` access, or access after `loadend`), and documenting the limitation is honest.

2. **Shared pairing token with Android SDK:** Both the extension and the Android `RequestrObserver` SDK (sibling plan) will use the same `/api/pair` endpoint. Should the token be shared (both agents use the same token) or separate (each gets its own)? **Recommendation:** Separate tokens per client (identified by a `clientId` field in the `/api/pair` request). This lets the user independently revoke the extension and the SDK.
