// requestr Browser Bridge -- background service worker (MV3)
//
// Two independent sources of network activity are relayed to requestr's
// embedded Local API server (LocalApiServer.kt, POST /api/observe):
//
//   1. chrome.webRequest.* -- fires for every real HTTP(S) request the browser
//      makes for this tab/frame (main documents, XHR/fetch, images, scripts,
//      etc.), including ones a page's own JS never sees (e.g. requests issued
//      by the browser itself, redirects, cached-then-revalidated requests).
//      This is the same class of data the CDP Network domain adapter
//      (CdpNetworkAdapter.kt) already captures when a debugger is attached --
//      the extension is a *fallback/parallel* path that works even when Chrome
//      wasn't launched with --remote-debugging-port, at the cost of only
//      exposing headers/timing/status (no request/response bodies -- MV3
//      removed blocking bodies access from webRequest).
//
//   2. Messages relayed from content.js -- page-context fetch()/XHR calls,
//      captured by monkey-patching in the page's own JS realm. This sees
//      things webRequest can categorize differently (e.g. same-document
//      fetches to blob:/data: URLs) and carries the calling page's URL as
//      "initiator" context, which the extension can't derive as reliably from
//      chrome.webRequest.onBeforeRequest alone (initiator there is an origin,
//      not a source-line/stack).
//
// Both sources are normalized into the exact schema receiveObserverEvents()
// in LocalApiServer.kt expects:
//   {"events":[{"url":..,"method":..,"headers":{..},"statusCode":..,
//               "bodySize":..,"durationMs":..,"timestamp":..}, ...]}
//
// Batched and flushed periodically so a burst of page loads doesn't open a
// new TCP connection to localhost:9876 per request.

const API_BASE_DEFAULT = "http://localhost:9876";
const FLUSH_INTERVAL_MS = 2000;
const MAX_BATCH = 200;

let apiBase = API_BASE_DEFAULT;
let eventQueue = [];
let relayedCount = 0;
let lastError = null;
let lastPingOk = null;

chrome.storage.local.get(["apiBase"], (res) => {
  if (res && res.apiBase) apiBase = res.apiBase;
});
chrome.storage.onChanged.addListener((changes) => {
  if (changes.apiBase) apiBase = changes.apiBase.newValue || API_BASE_DEFAULT;
});

// ---- 1. chrome.webRequest observation -------------------------------------
// Pure observation (no "blocking"/"requestBody" extraInfoSpec) so this works
// under Manifest V3 without the deprecated blocking-webRequest permission.
// We correlate onBeforeRequest -> onCompleted/onErrorOccurred by requestId to
// get status code + duration, mirroring what CdpNetworkAdapter does with CDP
// requestWillBeSent/responseReceived/loadingFinished.

const pending = new Map(); // requestId -> { url, method, startedAt, requestHeaders }

chrome.webRequest.onBeforeRequest.addListener(
  (details) => {
    if (details.url.startsWith(apiBase)) return; // never observe our own relay traffic
    pending.set(details.requestId, {
      url: details.url,
      method: details.method || "GET",
      startedAt: details.timeStamp || Date.now(),
      requestHeaders: {},
    });
  },
  { urls: ["<all_urls>"] }
);

chrome.webRequest.onSendHeaders.addListener(
  (details) => {
    const entry = pending.get(details.requestId);
    if (!entry) return;
    const headers = {};
    for (const h of details.requestHeaders || []) {
      if (h.value !== undefined) headers[h.name] = h.value;
    }
    entry.requestHeaders = headers;
  },
  { urls: ["<all_urls>"] },
  ["requestHeaders"]
);

chrome.webRequest.onCompleted.addListener(
  (details) => {
    const entry = pending.get(details.requestId);
    pending.delete(details.requestId);
    if (!entry) return;
    const durationMs = Math.max(0, Math.round((details.timeStamp || Date.now()) - entry.startedAt));
    let bodySize = 0;
    for (const h of details.responseHeaders || []) {
      if (h.name.toLowerCase() === "content-length") {
        bodySize = parseInt(h.value, 10) || 0;
      }
    }
    enqueue({
      url: entry.url,
      method: entry.method,
      headers: entry.requestHeaders,
      statusCode: details.statusCode,
      bodySize,
      durationMs,
      timestamp: Date.now(),
    });
  },
  { urls: ["<all_urls>"] },
  ["responseHeaders"]
);

chrome.webRequest.onErrorOccurred.addListener(
  (details) => {
    const entry = pending.get(details.requestId);
    pending.delete(details.requestId);
    if (!entry) return;
    const durationMs = Math.max(0, Math.round((details.timeStamp || Date.now()) - entry.startedAt));
    // Failed requests are still emitted (never silently dropped), mirroring
    // CdpNetworkAdapter's handling of Network.loadingFailed. No HTTP status
    // was ever received, so statusCode is omitted (server treats -1/absent
    // as null).
    enqueue({
      url: entry.url,
      method: entry.method,
      headers: entry.requestHeaders,
      statusCode: -1,
      bodySize: 0,
      durationMs,
      timestamp: Date.now(),
    });
  },
  { urls: ["<all_urls>"] }
);

// ---- 2. content.js relay ----------------------------------------------------

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message && message.source === "requestr-content" && message.type === "fetch-observed") {
    enqueue(message.event);
    sendResponse({ ok: true });
    return;
  }
  if (message && message.type === "get-status") {
    sendResponse({
      apiBase,
      relayedCount,
      queued: eventQueue.length,
      lastError,
      lastPingOk,
    });
    return;
  }
});

// ---- Batching + flush ------------------------------------------------------

function enqueue(event) {
  eventQueue.push(event);
  if (eventQueue.length >= MAX_BATCH) flush();
}

async function flush() {
  if (eventQueue.length === 0) return;
  const batch = eventQueue.splice(0, eventQueue.length);
  try {
    const res = await fetch(`${apiBase}/api/observe`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ events: batch }),
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    relayedCount += batch.length;
    lastError = null;
  } catch (e) {
    // requestr app may not be running / LocalApiServer toggle may be off.
    // Drop the batch rather than growing an unbounded retry queue.
    lastError = String(e && e.message ? e.message : e);
  }
}

setInterval(flush, FLUSH_INTERVAL_MS);

async function pingServer() {
  try {
    const res = await fetch(`${apiBase}/api/ping`, { method: "GET" });
    lastPingOk = res.ok;
  } catch (_e) {
    lastPingOk = false;
  }
  return lastPingOk;
}
setInterval(pingServer, 5000);
pingServer();

