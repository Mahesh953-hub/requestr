// requestr Browser Bridge SDK -- webRequest normalization + interceptor event relay.
//
// Replaces the observation logic that lived directly in background.js. Two sources feed
// the same normalized schema (see the JSDoc on normalizeEvent below) and both flow into
// queue.enqueue(): chrome.webRequest (headers/timing/status only -- MV3 removed body
// access from webRequest, this is a real platform limitation, not a shortcut we chose)
// and the MAIN-world interceptor relayed via bridge.mjs (has body data).

import { enqueue } from "./queue.mjs";

const pending = new Map(); // requestId -> { url, method, startedAt, requestHeaders }
const RECENT_EVENTS_KEY = "recentEvents";
const MAX_RECENT = 200;

let bodyCapturedCount = 0;
let webRequestCount = 0;
let interceptorCount = 0;

async function pushRecent(event) {
  try {
    const res = await chrome.storage.session.get([RECENT_EVENTS_KEY]);
    const recent = Array.isArray(res[RECENT_EVENTS_KEY]) ? res[RECENT_EVENTS_KEY] : [];
    recent.push(event);
    if (recent.length > MAX_RECENT) recent.splice(0, recent.length - MAX_RECENT);
    await chrome.storage.session.set({ [RECENT_EVENTS_KEY]: recent });
  } catch (_e) {
    /* options page traffic log is best-effort; never blocks the real queue/relay path */
  }
}

/**
 * Canonical event shape persisted by the queue and sent to POST /api/observe. Both
 * webRequest-sourced and interceptor-sourced events are normalized to this exact shape
 * (see plan-mv3.md Task 9) so the server-side consumer (LocalApiServer.kt
 * receiveObserverEvents, per the SDK scope's schema) sees one consistent event structure
 * regardless of which browser-side mechanism produced it.
 */
function normalizeEvent(partial) {
  return Object.assign(
    {
      url: "",
      method: "GET",
      requestHeaders: {},
      statusCode: null,
      responseHeaders: {},
      bodySize: 0,
      durationMs: 0,
      timestamp: Date.now(),
      requestBody: null,
      requestBodyEncoding: "none",
      requestBodyTruncated: false,
      responseBody: null,
      responseBodyEncoding: "none",
      responseBodyTruncated: false,
      initiator: null,
      source: "webRequest",
      ruleAction: "LOG_ONLY",
      ruleId: null,
      ruleName: null,
    },
    partial
  );
}

function isOwnApiTraffic(url, apiBase) {
  return typeof url === "string" && apiBase && url.startsWith(apiBase);
}

function record(event) {
  enqueue(event);
  pushRecent(event);
}

/** Registers chrome.webRequest listeners and the chrome.runtime.onMessage handler that
 *  receives relayed interceptor events from bridge.mjs. `getApiBase` is a function
 *  (rather than a captured value) so it always reflects the live config even though this
 *  is called once at SW startup. */
export function startObserver(getApiBase) {
  chrome.webRequest.onBeforeRequest.addListener(
    (details) => {
      if (isOwnApiTraffic(details.url, getApiBase())) return;
      pending.set(details.requestId, {
        url: details.url,
        method: details.method || "GET",
        startedAt: details.timeStamp || Date.now(),
        requestHeaders: {},
        initiator: details.initiator || null,
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
      const responseHeaders = {};
      for (const h of details.responseHeaders || []) {
        responseHeaders[h.name] = h.value;
        if (h.name.toLowerCase() === "content-length") bodySize = parseInt(h.value, 10) || 0;
      }
      webRequestCount++;
      record(
        normalizeEvent({
          url: entry.url,
          method: entry.method,
          requestHeaders: entry.requestHeaders,
          statusCode: details.statusCode,
          responseHeaders,
          bodySize,
          durationMs,
          timestamp: Date.now(),
          initiator: entry.initiator,
          source: "webRequest",
          // webRequest cannot see bodies in MV3 -- explicit "none", never fabricated.
          requestBody: null,
          requestBodyEncoding: "none",
          responseBody: null,
          responseBodyEncoding: "none",
        })
      );
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
      webRequestCount++;
      record(
        normalizeEvent({
          url: entry.url,
          method: entry.method,
          requestHeaders: entry.requestHeaders,
          statusCode: null, // no HTTP status was ever received; server treats missing as null
          bodySize: 0,
          durationMs,
          timestamp: Date.now(),
          initiator: entry.initiator,
          source: "webRequest",
        })
      );
    },
    { urls: ["<all_urls>"] }
  );

  chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message && message.source === "requestr-content" && message.type === "fetch-observed") {
      interceptorCount++;
      if (message.event && (message.event.requestBody || message.event.responseBody)) {
        bodyCapturedCount++;
      }
      record(normalizeEvent(Object.assign({ source: "interceptor" }, message.event)));
      sendResponse({ ok: true });
      return;
    }
    if (message && message.type === "get-observer-status") {
      sendResponse({ webRequestCount, interceptorCount, bodyCapturedCount });
      return;
    }
  });
}

export function getObserverCounts() {
  return { webRequestCount, interceptorCount, bodyCapturedCount };
}
