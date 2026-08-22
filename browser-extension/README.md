# requestr Browser Bridge — MV3 SDK

An unpacked MV3 Chrome extension and JS SDK layer that relays browser network activity —
including request and response bodies — to requestr's embedded Local API server
(`LocalApiServer.kt`), enforces requestr's Intercept Rules (`intercept_rules` table) against
browser traffic where Chrome's platform actually allows it, and survives service-worker
termination instead of silently dropping data.

This is a **parallel/fallback** path to the CDP Browser Bridge (Settings → Browser Bridge
(CDP)), which attaches directly to Chrome's remote-debugging port. The extension works even
when Chrome was **not** launched with remote debugging enabled.

## What changed from v1.0.0

The previous version of this extension observed headers/timing only, used `setInterval`
for batching (which stops running once the service worker idles out, silently losing any
events queued at that moment), dropped failed batches with no retry, and had no
authentication and no enforcement capability. All four of those are fixed here:

- **Request AND response bodies are captured** (`src/content/interceptor-main.mjs`), not
  just headers/status/timing.
- **Rules are enforced** — BLOCK / REDIRECT / MODIFY_REQUEST / MODIFY_RESPONSE, mirroring
  `RuleEnforcer.kt`'s exact matching and JSON config semantics — via a combination of
  `declarativeNetRequest` (the browser-enforced fast path) and MAIN-world interception
  (the only path for anything DNR cannot express, primarily body rewriting).
- **The event queue survives service-worker termination.** It lives in
  `chrome.storage.session` (not a `let` global) and is flushed by `chrome.alarms` (not
  `setInterval`), which keeps firing across SW restarts.
- **Failed batches are retried with exponential backoff** instead of being dropped. Events
  that fail more than 5 times move to a bounded dead-letter store (expires after 1 hour)
  rather than being retried forever or silently vanishing.
- **Requests to `/api/observe` and `/api/rules` carry `Authorization: Bearer <token>`.**

## Architecture

```
src/
├── sdk/                    Reusable, chrome.*-light SDK modules (unit-testable with plain node)
│   ├── version.mjs          SDK version/build constants
│   ├── config.mjs           chrome.storage.local-backed settings
│   ├── utils.mjs             backoff/base64/header/hash helpers -- pure functions
│   ├── pairing.mjs          Authorization header + token storage
│   ├── queue.mjs             persistent event queue, retry/backoff, dead-letter
│   ├── observer.mjs         chrome.webRequest normalization + interceptor event relay
│   ├── channel.mjs          rules pull (GET /api/rules) + health check
│   ├── rules.mjs             declarativeNetRequest dynamic-rule synchronization
│   ├── enforcer.mjs         rule-matching engine mirroring RuleEnforcer.kt
│   └── index.mjs             public SDK facade (getStatus/refreshRules/etc.)
├── background/
│   └── service-worker.mjs   entry point; boots config/observer/channel/queue, registers alarms
├── content/
│   ├── interceptor-main.mjs MAIN-world fetch/XHR patch: body capture + enforcement
│   └── bridge.mjs           ISOLATED-world bridge: relays events, forwards rule pushes
└── ui/
    ├── popup.html/mjs/css    status + rules-list popup
    └── options.html/mjs/css connection, rules table, traffic log, settings
test/
├── test-page.html           manual/visual integration test suite (see below)
├── slow-response-sw.js      test-only Service Worker for test-page.html's case 11
├── run-all.mjs              node --check + manifest validation + unit test runner
└── unit/*.test.mjs           plain-node unit tests for pure logic (enforcer, queue, utils)
```

## Auth / pairing

Per the authoritative token scheme implemented on the Android side
(`LocalApiServer.kt`'s pairing work, see `docs/plans/plan-sdk-detailed.md`), the pairing
token is generated **server-side** and only revealed when the user turns on requestr's
"Show Pairing Token" switch (Settings → SDK Pairing), which auto-hides again after 5
minutes. There is no extension-initiated handshake endpoint (`POST /api/pair`) — pairing is
either:

1. **Manual**: copy the token from requestr's Settings screen, paste it into this
   extension's Options page ("Pairing token" field), or
2. **Automatic** (Options page's "Pair automatically" button): GETs
   `${apiBase}/api/pairing-info`, which the server only serves while "Show Pairing Token"
   is on — returns 403 otherwise.

Every authenticated call (`POST /api/observe`, `GET /api/rules`) sends
`Authorization: Bearer <token>`. `GET /api/ping` requires no auth. The token is never
logged by any module in this extension.

## Rule enforcement — what actually happens per action

| Action | Mechanism | Why |
|---|---|---|
| **BLOCK** | `declarativeNetRequest` dynamic rule (`action: "block"`) | Enforced by Chrome's network stack before any JS runs. |
| **REDIRECT** | `declarativeNetRequest` dynamic rule (`action: "redirect"`) | Same — DNR redirect needs no body access. |
| **MODIFY_REQUEST**, headers only | `declarativeNetRequest` `modifyHeaders` | DNR can add/remove/set request headers declaratively. |
| **MODIFY_REQUEST**, body (`bodyReplace`) | MAIN-world interceptor rewrites `init.body`/`send(body)` before the real call | **DNR cannot touch request bodies at all** — this is a genuine Chrome platform limit, not a shortcut. The interceptor is the only mechanism that can. |
| **MODIFY_RESPONSE**, `fetch()` | MAIN-world interceptor clones the response, builds a `new Response(bodyReplace, {status, headers})`, and returns that instead | `Response.clone()` tees the body stream so the page's own read of the *original* is completely unaffected by the interceptor's read of the clone. |
| **MODIFY_RESPONSE**, `XMLHttpRequest` | Best-effort: monkey-patches `responseText`/`response`/`status` via `Object.defineProperty` after `loadend` | **Genuine limitation, stated plainly: there is no API to swap an XHR's response body the way `Response` objects can be swapped for fetch.** This works for the common case (code that reads `responseText`/`status` after `loadend`), but code that reads `responseXML` before `loadend`, or uses synchronous XHR, will see the unmodified value. If `Object.defineProperty` fails (non-configurable in some engine), the modification silently does not apply — this is logged nowhere as a false success. |

BLOCK/REDIRECT/MODIFY_REQUEST(headers) are DNR-eligible and always synced to DNR
(`src/sdk/rules.mjs`). The MAIN-world interceptor also independently re-checks every rule
on every `fetch()`/XHR call, so BLOCK still works even before the first DNR sync completes
(e.g. immediately after install), and so body-level rules are never missed.

## Body capture — what is and isn't captured

- **`fetch()` responses**: read from a `response.clone()`, capped at the configured max
  (default 1 MB for text, 256 KB for binary), streamed with a 3-second per-chunk timeout so
  a stalled/never-ending stream (e.g. a broken SSE feed) can't hang the interceptor
  indefinitely — the read is simply marked truncated and released.
- **`fetch()` requests**: captured from `init.body`. Strings/`URLSearchParams` captured as
  UTF-8 text. `Blob`/`ArrayBuffer` captured as base64 (256 KB cap). `FormData` is
  summarized as `"[FormData: N entries]"` rather than fully re-serialized to multipart
  encoding. **A `ReadableStream` body is explicitly NOT read** — doing so would consume the
  stream the real `fetch()` call needs, corrupting the actual request; it's reported as
  `"[ReadableStream: not captured]"`, not silently omitted.
- **`XMLHttpRequest`**: request body captured from the `send()` argument with the same
  rules as fetch. Response body read from `responseText`/`response` once `loadend` fires
  (the page already has access to the same properties at the same time, so this doesn't
  race the page).
- **`chrome.webRequest` events** (separate from the interceptor) carry **no body data at
  all** — Manifest V3 removed blocking/body access from `webRequest`. These events are
  explicitly tagged `requestBodyEncoding: "none"` / `responseBodyEncoding: "none"`, never a
  fabricated placeholder.
- **DNR-blocked/redirected requests never reach `fetch()`/`send()`** at the browser level,
  so the interceptor's per-call body capture doesn't run for those — that traffic is
  captured by `chrome.webRequest` instead (headers/status/timing only, consistent with the
  point above).

## Loading the extension (unpacked)

1. Build and run requestr on the device/emulator you'll browse from.
2. In the app, go to **Settings → Local API Server** and enable the toggle (default port
   `9876`).
3. In the app, go to **Settings → SDK Pairing**, enable "Show Pairing Token", and copy the
   token shown.
4. In Chrome, open `chrome://extensions`, enable **Developer mode**, click **Load
   unpacked**, and select this `browser-extension/` directory.
5. Open the extension's **Options** page (right-click the toolbar icon → Options, or via
   the popup's "Open Options" link). Paste the token into "Pairing token" and click
   **Save** — or click **Pair automatically** while the Settings toggle is still on.
6. Click the toolbar icon to open the popup — it should show **Connected** once paired and
   reachable.

## Reaching a non-localhost device

Same as before: `localhost:9876` only works when the Local API server and the browser
share a network namespace. For a physical device on the same Wi-Fi network, use the
device's LAN IP (e.g. `http://192.168.1.42:9876`), configured via the popup/options API
base field.

## Integration testing

`test/test-page.html` is a self-contained, network-independent visual test suite covering
all 11 cases from the implementation plan's Task 13:

1. `fetch GET` with JSON response — unmodified response reaches the page
2. `fetch POST` with a JSON body — request body capture path doesn't throw
3. `fetch` with a blob response — blob isn't corrupted by the interceptor's clone+read
4. `XMLHttpRequest GET` — response body captured via `responseText`
5. `XMLHttpRequest POST` with a body — request body capture doesn't alter what's sent
6. `fetch` to an unreachable host — the real network error surfaces, isn't swallowed
7. `window.postMessage` rules-update — MAIN-world listener accepts it without throwing
8. Concurrency — 5 simultaneous fetches, no cross-request interleaving corruption
9. `fetch` → `MODIFY_REQUEST` rule (`addHeaders`) — asserts the interceptor's own dispatched
   event reports the *actually-sent* (modified) headers, not the page's originals
10. `fetch` → `MODIFY_RESPONSE` rule (`bodyReplace`) — asserts the page receives the
    replacement body verbatim, with the original body fully discarded
11. Slow, multi-chunk response — uses a test-only Service Worker
    (`test/slow-response-sw.js`) to answer with a `ReadableStream` that enqueues 3 chunks
    with a real ~150ms delay each, so `captureResponseBody()`'s reader loop is exercised
    against genuine network-timing latency (not an instantly-resolved `data:` URL).
    Case 11 needs a real HTTP(S) origin to register a Service Worker — opening this file
    directly via `file://` has no origin to register against, so on `file://` (or any
    context where SW registration fails) this case reports **SKIP**, not a faked pass.
    Serve the `browser-extension/test/` directory over `http://` (e.g.
    `npx http-server test -p 8088` or `python3 -m http.server 8088 -d test`) and open
    `http://localhost:8088/test-page.html` to run it for real.

Cases 1-8 and 9-10 work with or without a running requestr app (they use `data:` URLs and
a guaranteed-unreachable loopback port for the network-error case, and drive rule
enforcement directly via synthetic `__requestr_rules_update__` messages rather than the
real `/api/rules` sync). Load the page in Chrome with the extension installed; open the
extension's service worker console (`chrome://extensions` → Inspect views) to also watch
`__requestr_fetch_observed__` events flow through to the relay queue.

`test/unit/*.test.mjs` are plain-node unit tests for pure logic that doesn't need
`chrome.*` at all: `enforcer.test.mjs` (rule matching + JSON config parsing, mirroring
`RuleEnforcer.kt`'s exact semantics), `queue.test.mjs` (overflow/retry/backoff math),
`utils.test.mjs` (backoff calculation, base64 encoding, header serialization). Run all of
them plus a manifest/syntax check with:

```
node test/run-all.mjs
```

## Known limitations (genuine platform constraints, not by-design gaps)

- **XHR response-body modification is best-effort**, as detailed in the enforcement table
  above — there is no API to replace an XHR's response the way `Response` objects can be
  swapped for `fetch()`.
- **Streaming response bodies (SSE, chunked) are captured up to the configured cap**
  (default 1 MB) and then the capture read is released — the *page's own* read of the
  stream is entirely unaffected and continues normally; only the extension's captured copy
  is truncated.
- **`declarativeNetRequest` has a 1,000-rule budget for `regexFilter`-based dynamic rules**
  (rules using `pattern` rather than `domain`). Rules beyond that budget fall back to
  MAIN-world-interceptor-only enforcement, which only sees requests initiated by page JS
  (not, e.g., prefetches the browser issues on its own) — the popup does not currently
  surface a count of rules in this fallback state; check the service worker console for DNR
  sync errors if you have more than ~1,000 regex-pattern rules.
- **`chrome.webRequest`'s `onErrorOccurred` events carry no HTTP status** (none was ever
  received) — reported as `statusCode: null`, matching the schema's convention for "no
  status available" rather than fabricating one.
- **`chrome.storage.session` is cleared on browser restart and extension
  reload/update** (not on service-worker idle termination, which is the case this was
  built to survive). Any events queued at the moment of an actual browser restart are
  lost — this is an accepted, documented tradeoff: `chrome.storage.local` would survive it
  but is not designed for high-churn ephemeral data at this volume.
- **No automated `POST /api/pair` handshake.** Pairing requires either manually copying a
  token from requestr's Settings screen or using the "Pair automatically" button while the
  "Show Pairing Token" switch is on — see the Auth section above for why.
