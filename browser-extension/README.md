# requestr Browser Bridge — Chrome Extension

An unpacked MV3 Chrome extension that relays browser network activity to requestr's
embedded Local API server (`LocalApiServer.kt`, `POST /api/observe`), so browser traffic
shows up alongside VPN/root/MITM-captured traffic in the app's Intercept traffic log.

This is a **parallel/fallback** path to the CDP Browser Bridge (Settings → Browser Bridge
(CDP)), which attaches directly to Chrome's remote-debugging port (`--remote-debugging-port`,
default `9222`) and reads the `Network`/`Fetch`/`ServiceWorker`/`DOMStorage`/`IndexedDB`
domains (see `CdpNetworkAdapter.kt`, `CdpFetchAdapter.kt`, `ServiceWorkerObserver.kt`,
`BrowserStorageObserver.kt`). The extension works even when Chrome was **not** launched
with remote debugging enabled, at the cost of a narrower feature set — no request/response
body access under MV3, no request blocking/modification (that's still CDP Fetch-domain-only,
see `CdpFetchAdapter.kt`), and no service worker/storage introspection.

## What it captures

- **`background.js`** — `chrome.webRequest` observation (`onBeforeRequest` /
  `onSendHeaders` / `onCompleted` / `onErrorOccurred`), correlated by `requestId` into one
  event per HTTP(S) request with method, headers, status code, response size, and duration.
  This sees every request the browser makes for the tab, including ones a page's own JS
  never touches.
- **`page-interceptor.js`** — injected into the page's **MAIN** JS world (`"world": "MAIN"`
  in `manifest.json`, requires Chrome 111+) so it patches the actual `window.fetch` /
  `XMLHttpRequest` the page's own scripts call. Captures page-initiated fetch/XHR calls,
  including ones to `blob:`/`data:` URLs that `chrome.webRequest` may not surface distinctly.
- **`content.js`** — bridges `page-interceptor.js` (MAIN world, no `chrome.*` API access) to
  `background.js` (which does the actual relay), via a `CustomEvent` on `window` that the
  isolated-world content script listens for and forwards with `chrome.runtime.sendMessage`.

Both sources are deliberately allowed to overlap — the same request may be observed by both
`chrome.webRequest` and the page interceptor. requestr's `CorrelationEngine` on the Android
side already de-duplicates/correlates events from multiple observer sources within a ±500ms
window (see `ObservationEvent.ObserverSource`), so redundant observation is not a problem.

Events are batched (up to 200 per flush, flushed every 2s) and POSTed to
`<apiBase>/api/observe` as:

```json
{"events":[{"url":"...","method":"GET","headers":{...},"statusCode":200,"bodySize":1234,"durationMs":56,"timestamp":1700000000000}]}
```

which matches `receiveObserverEvents()` in `LocalApiServer.kt` exactly — each event is
persisted as a `CapturedTrafficEntity` with `protocol = "sdk"` under a dedicated
always-open `CaptureTier.SDK` session, so it shows up immediately in the Intercept UI, no
capture session needs to be started first.

## Loading the extension (unpacked)

1. Build and run requestr on the device/emulator you'll browse from (or one reachable from
   it — see "Reaching a non-localhost device" below).
2. In the app, go to **Settings → Local API Server** and enable the toggle (default port
   `9876`). Note the address shown.
3. In Chrome, open `chrome://extensions`.
4. Enable **Developer mode** (top-right toggle).
5. Click **Load unpacked** and select this `browser-extension/` directory.
6. Click the extension's toolbar icon to open the popup. It should show **Connected** once
   it can reach `/api/ping` on the configured address.
7. If requestr's Local API server is not on `http://localhost:9876` (e.g. you're testing
   against an emulator's host-forwarded port, or a physical device on the same network),
   change the address in the popup and click **Save** — it's saved via `chrome.storage.local`
   and used for both `background.js`'s webRequest relay and status polling.

## Reaching a non-localhost device

`localhost:9876` only works when requestr's Local API server and the browser share the same
network namespace (e.g. an Android emulator with `adb forward tcp:9876 tcp:9876`, or Chrome
running directly on the same machine as a rooted/debuggable device bridge). For a physical
device on the same Wi-Fi network, use the device's LAN IP instead (e.g.
`http://192.168.1.42:9876`) — the Local API server binds `127.0.0.1` only by default inside
the device's own network namespace, so reaching it from another machine on the LAN requires
either `adb reverse`/`adb forward` port mapping or a proxy; see the CDP Browser Bridge
Settings card for the same host:port pattern used there.

## Known limitations

- No request/response body capture (Manifest V3 removed blocking access to request bodies
  via `chrome.webRequest`, and this extension does not use `chrome.debugger` — that's what
  the in-app CDP Browser Bridge is for).
- No ability to block/redirect/modify requests (see `CdpFetchAdapter.kt` for that, via CDP's
  `Fetch` domain against the same `intercept_rules` table used by MITM/VPN capture).
- `chrome.webRequest`'s `onErrorOccurred` events are relayed with `statusCode: -1` (no HTTP
  status was ever received); the server maps any negative status to `null`.
- If the extension is reloaded while a page is open, existing page-interceptor bridges in
  already-loaded tabs lose their message channel until the page is refreshed (standard MV3
  content-script lifecycle behavior).
