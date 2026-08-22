// requestr Browser Bridge -- MAIN-world page interceptor with body capture + enforcement.
//
// Runs in the page's MAIN JS world (manifest.json content_scripts "world": "MAIN", Chrome
// 111+) so it patches the actual window.fetch / XMLHttpRequest the page's own scripts
// call -- an ISOLATED-world content script cannot do this (the page sees its own,
// unpatched fetch/XHR; see bridge.mjs's file header for why the split exists).
//
// MAIN-world scripts have no chrome.* API access, so all communication crosses the
// world boundary via window CustomEvent/postMessage, relayed by bridge.mjs:
//   - outbound (this file -> bridge.mjs -> service worker): CustomEvent(EVENT_NAME)
//   - inbound (service worker -> bridge.mjs -> this file): window.postMessage
//
// This file is loaded directly by Chrome as a classic (non-module) content script --
// Chrome does not support ES modules in content scripts, so it is written as a
// self-contained IIFE rather than importing sdk/enforcer.mjs etc. The rule-matching and
// modification-parsing logic below is a deliberate, small, hand-kept-in-sync duplicate of
// src/sdk/enforcer.mjs's pure functions (same matching semantics, same JSON config
// shapes) so both can be unit-tested independently: enforcer.mjs via plain `node`, this
// file via the manual test/test-page.html integration checks (Task 13).
(function () {
  "use strict";

  const EVENT_NAME = "__requestr_fetch_observed__";
  const RULES_UPDATE_MESSAGE = "__requestr_rules_update__";
  const DEFAULT_MAX_BODY = 1048576; // 1 MB, overridden by rules-update message's maxBodyBytes
  const MAX_BINARY_BODY = 262144; // 256 KB cap for base64-encoded binary bodies
  const READ_TIMEOUT_MS = 3000;

  let cachedRules = [];
  let enforcementEnabled = true;
  let maxBodyBytes = DEFAULT_MAX_BODY;

  window.addEventListener("message", (e) => {
    if (e.source !== window) return;
    const data = e.data;
    if (data && data.type === RULES_UPDATE_MESSAGE) {
      cachedRules = Array.isArray(data.rules) ? data.rules : [];
      enforcementEnabled = data.enforcementEnabled !== false;
      if (Number.isFinite(data.maxBodyBytes) && data.maxBodyBytes > 0) {
        maxBodyBytes = data.maxBodyBytes;
      }
    }
  });

  function dispatch(event) {
    try {
      window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: event }));
    } catch (_e) {
      /* page may have frozen/disabled CustomEvent under some CSPs -- best effort only */
    }
  }

  function nowMs() {
    return Date.now();
  }

  // ---- rule matching (kept in sync with src/sdk/enforcer.mjs) --------------

  function checkRules(url, host) {
    if (!enforcementEnabled) return null;
    for (const rule of cachedRules) {
      if (!rule || rule.enabled === false) continue;
      const matchesDomain =
        typeof rule.domain === "string" &&
        rule.domain.length > 0 &&
        typeof host === "string" &&
        host.toLowerCase().indexOf(rule.domain.toLowerCase()) !== -1;
      let matchesPattern = false;
      if (typeof rule.pattern === "string" && rule.pattern.length > 0) {
        try {
          matchesPattern = new RegExp(rule.pattern, "i").test(url);
        } catch (_e) {
          matchesPattern = false;
        }
      }
      if (matchesDomain || matchesPattern) return rule;
    }
    return null;
  }

  function parseModConfig(replacementValue) {
    if (!replacementValue) return null;
    try {
      return JSON.parse(replacementValue);
    } catch (_e) {
      return null;
    }
  }

  function hostOf(url) {
    try {
      return new URL(url, window.location.href).host;
    } catch (_e) {
      return "";
    }
  }

  // ---- body encoding helpers -------------------------------------------------

  function encodingForContentType(contentType) {
    const ct = String(contentType || "").toLowerCase();
    if (
      ct.startsWith("text/") ||
      ct.indexOf("application/json") !== -1 ||
      ct.indexOf("application/xml") !== -1 ||
      ct.indexOf("application/javascript") !== -1 ||
      ct.indexOf("application/x-www-form-urlencoded") !== -1 ||
      ct.indexOf("multipart/form-data") !== -1 ||
      ct.indexOf("+json") !== -1 ||
      ct.indexOf("+xml") !== -1
    ) {
      return "utf8";
    }
    if (ct === "") return "utf8";
    return "base64";
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const CHUNK = 8192;
    for (let i = 0; i < bytes.length; i += CHUNK) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    return btoa(binary);
  }

  function readWithTimeout(reader, ms) {
    return Promise.race([
      reader.read(),
      new Promise((resolve) => setTimeout(() => resolve({ done: true, value: undefined, __timedOut: true }), ms)),
    ]);
  }

  /** Reads a (cloned) Response body up to maxBodyBytes, honoring content-type-driven
   *  encoding. Never touches the original response -- callers pass a .clone(). */
  async function captureResponseBody(response) {
    const contentType = (function () {
      try {
        return response.headers.get("content-type") || "";
      } catch (_e) {
        return "";
      }
    })();
    if (!response.body) {
      return { body: null, bodyEncoding: "none", bodyTruncated: false };
    }
    const encoding = encodingForContentType(contentType);
    const cap = encoding === "utf8" ? maxBodyBytes : Math.min(maxBodyBytes, MAX_BINARY_BODY);

    let reader;
    try {
      reader = response.body.getReader();
    } catch (_e) {
      // Some CDN-optimized/locked-stream responses throw on getReader(); skip capture,
      // the page's own copy of the response is entirely unaffected either way.
      return { body: null, bodyEncoding: "error", bodyTruncated: false };
    }

    let totalSize = 0;
    let truncated = false;
    const chunks = [];
    try {
      while (true) {
        const { done, value, __timedOut } = await readWithTimeout(reader, READ_TIMEOUT_MS);
        if (__timedOut) {
          truncated = true;
          break;
        }
        if (done) break;
        if (totalSize + value.byteLength > cap) {
          const remaining = cap - totalSize;
          if (remaining > 0) chunks.push(value.subarray(0, remaining));
          totalSize = cap;
          truncated = true;
          break;
        }
        chunks.push(value);
        totalSize += value.byteLength;
      }
    } catch (_e) {
      return { body: null, bodyEncoding: "error", bodyTruncated: false };
    } finally {
      try {
        reader.releaseLock();
      } catch (_e) {
        /* already released or stream errored -- nothing more to do */
      }
    }

    const out = new Uint8Array(totalSize);
    let offset = 0;
    for (const c of chunks) {
      out.set(c.subarray(0, Math.min(c.byteLength, totalSize - offset)), offset);
      offset += c.byteLength;
      if (offset >= totalSize) break;
    }

    if (encoding === "utf8") {
      try {
        return { body: new TextDecoder("utf-8").decode(out), bodyEncoding: "utf8", bodyTruncated: truncated };
      } catch (_e) {
        return { body: bytesToBase64(out), bodyEncoding: "base64", bodyTruncated: truncated };
      }
    }
    return { body: bytesToBase64(out), bodyEncoding: "base64", bodyTruncated: truncated };
  }

  /** Captures a fetch() request body (init.body) before the real call is made. Does not
   *  consume a ReadableStream body (that would break the real request), and summarizes
   *  FormData rather than fully serializing multipart encoding (matches plan-mv3.md's
   *  documented approach for request-body capture). */
  function captureRequestBody(body) {
    if (body === null || body === undefined) {
      return { body: null, bodyEncoding: "none", bodyTruncated: false };
    }
    if (typeof body === "string") {
      const truncated = body.length > maxBodyBytes;
      return { body: truncated ? body.slice(0, maxBodyBytes) : body, bodyEncoding: "utf8", bodyTruncated: truncated };
    }
    if (typeof URLSearchParams !== "undefined" && body instanceof URLSearchParams) {
      const s = body.toString();
      const truncated = s.length > maxBodyBytes;
      return { body: truncated ? s.slice(0, maxBodyBytes) : s, bodyEncoding: "utf8", bodyTruncated: truncated };
    }
    if (typeof FormData !== "undefined" && body instanceof FormData) {
      let count = 0;
      try {
        for (const _ of body.entries()) count++;
      } catch (_e) {
        /* ignore */
      }
      return { body: `[FormData: ${count} entries]`, bodyEncoding: "utf8", bodyTruncated: false };
    }
    if (typeof ReadableStream !== "undefined" && body instanceof ReadableStream) {
      // Reading this here would consume the stream the real fetch() needs -- documented
      // as genuinely uncapturable without breaking the request, not a stub.
      return { body: "[ReadableStream: not captured]", bodyEncoding: "utf8", bodyTruncated: false };
    }
    if (body instanceof Blob) {
      return { body: null, bodyEncoding: "blob-async", bodyTruncated: false, __blob: body };
    }
    if (body instanceof ArrayBuffer) {
      const bytes = new Uint8Array(body);
      const truncated = bytes.byteLength > MAX_BINARY_BODY;
      return {
        body: bytesToBase64(truncated ? bytes.subarray(0, MAX_BINARY_BODY) : bytes),
        bodyEncoding: "base64",
        bodyTruncated: truncated,
      };
    }
    return { body: "[unsupported body type]", bodyEncoding: "utf8", bodyTruncated: false };
  }

  // ---- fetch() ---------------------------------------------------------------

  const originalFetch = window.fetch;
  if (typeof originalFetch === "function") {
    window.fetch = function (input, init) {
      const start = nowMs();
      let url;
      let method = "GET";
      try {
        if (typeof input === "string") {
          url = input;
        } else if (input && typeof input.url === "string") {
          url = input.url;
          method = input.method || method;
        }
        if (init && init.method) method = init.method;
      } catch (_e) {
        url = String(input);
      }

      const requestHeaders = {};
      try {
        const h = init && init.headers;
        if (h) {
          if (h instanceof Headers) {
            h.forEach((v, k) => (requestHeaders[k] = v));
          } else if (Array.isArray(h)) {
            for (const pair of h) requestHeaders[pair[0]] = pair[1];
          } else if (typeof h === "object") {
            for (const k of Object.keys(h)) requestHeaders[k] = h[k];
          }
        }
      } catch (_e) {
        /* best effort */
      }

      const requestBodyInfo = captureRequestBody(init && init.body);
      const host = hostOf(url);
      const matchedRule = checkRules(url, host);

      // BLOCK: if a DNR dynamic rule already exists for this rule id, DNR blocks the
      // request before it ever reaches this fetch() wrapper's originalFetch call below --
      // in that case the page never even gets here for this specific call because DNR
      // operates at the network layer beneath fetch(). This branch is the *backstop* for
      // BLOCK rules DNR could not express (none currently -- BLOCK is always DNR-eligible,
      // see rules.mjs) and for defense in depth if DNR sync hasn't landed yet.
      if (matchedRule && matchedRule.action === "BLOCK") {
        dispatch({
          url,
          method,
          requestHeaders,
          statusCode: null,
          durationMs: nowMs() - start,
          timestamp: nowMs(),
          requestBody: requestBodyInfo.body,
          requestBodyEncoding: requestBodyInfo.bodyEncoding,
          requestBodyTruncated: requestBodyInfo.bodyTruncated,
          ruleAction: "BLOCK",
          ruleId: matchedRule.id,
          ruleName: matchedRule.name,
        });
        return Promise.reject(new DOMException("Blocked by requestr rule", "AbortError"));
      }

      let effectiveInit = init;
      let ruleAction = matchedRule ? matchedRule.action : "LOG_ONLY";
      let ruleId = matchedRule ? matchedRule.id : null;
      let ruleName = matchedRule ? matchedRule.name : null;

      // These start as the page's original request data and are reassigned below when
      // MODIFY_REQUEST actually changes what goes over the wire -- the dispatched event
      // must reflect what was *actually sent*, not what the page originally asked for,
      // otherwise the requestr traffic log would misrepresent modified requests.
      let effectiveRequestHeaders = requestHeaders;
      let effectiveRequestBodyInfo = requestBodyInfo;

      if (matchedRule && matchedRule.action === "MODIFY_REQUEST") {
        const config = parseModConfig(matchedRule.replacementValue);
        if (config) {
          const newHeaders = Object.assign({}, requestHeaders);
          if (config.addHeaders) Object.assign(newHeaders, config.addHeaders);
          if (Array.isArray(config.removeHeaders)) {
            for (const name of config.removeHeaders) delete newHeaders[name];
          }
          effectiveInit = Object.assign({}, init, { headers: newHeaders });
          effectiveRequestHeaders = newHeaders;
          if (typeof config.bodyReplace === "string") {
            effectiveInit.body = config.bodyReplace;
            effectiveRequestBodyInfo = { body: config.bodyReplace, bodyEncoding: "utf8", bodyTruncated: false };
          }
        }
      }

      const responseRule =
        matchedRule && matchedRule.action === "MODIFY_RESPONSE" ? matchedRule : null;

      return originalFetch.call(this, input, effectiveInit).then(
        (response) => {
          const durationMs = nowMs() - start;
          let clone = null;
          try {
            clone = response.clone();
          } catch (_e) {
            // Locked/already-consumed stream -- skip capture entirely, return original
            // response untouched (the page's own read is never affected by this failure).
          }

          let finalResponse = response;
          let responseRuleApplied = null;

          if (responseRule && clone) {
            const config = parseModConfig(responseRule.replacementValue);
            if (config && typeof config.bodyReplace === "string") {
              const headers = new Headers(response.headers);
              if (config.addHeaders) {
                for (const k of Object.keys(config.addHeaders)) headers.set(k, config.addHeaders[k]);
              }
              finalResponse = new Response(config.bodyReplace, {
                status: config.statusCode || response.status,
                statusText: response.statusText,
                headers,
              });
              responseRuleApplied = responseRule;
            }
          }

          const bodySource = clone || null;
          if (bodySource) {
            captureResponseBody(bodySource).then((bodyInfo) => {
              dispatch({
                url,
                method,
                requestHeaders: effectiveRequestHeaders,
                statusCode: response.status,
                responseHeaders: (function () {
                  const out = {};
                  try {
                    response.headers.forEach((v, k) => (out[k] = v));
                  } catch (_e) {
                    /* ignore */
                  }
                  return out;
                })(),
                bodySize: bodyInfo.body ? bodyInfo.body.length : 0,
                durationMs,
                timestamp: nowMs(),
                requestBody: effectiveRequestBodyInfo.body,
                requestBodyEncoding: effectiveRequestBodyInfo.bodyEncoding,
                requestBodyTruncated: effectiveRequestBodyInfo.bodyTruncated,
                responseBody: bodyInfo.body,
                responseBodyEncoding: bodyInfo.bodyEncoding,
                responseBodyTruncated: bodyInfo.bodyTruncated,
                initiator: window.location.href,
                ruleAction: responseRuleApplied ? "MODIFY_RESPONSE" : ruleAction,
                ruleId: responseRuleApplied ? responseRuleApplied.id : ruleId,
                ruleName: responseRuleApplied ? responseRuleApplied.name : ruleName,
              });
            });
          }

          return finalResponse;
        },
        (error) => {
          const durationMs = nowMs() - start;
          dispatch({
            url,
            method,
            requestHeaders: effectiveRequestHeaders,
            statusCode: null,
            durationMs,
            timestamp: nowMs(),
            requestBody: effectiveRequestBodyInfo.body,
            requestBodyEncoding: effectiveRequestBodyInfo.bodyEncoding,
            requestBodyTruncated: effectiveRequestBodyInfo.bodyTruncated,
            initiator: window.location.href,
            ruleAction,
            ruleId,
            ruleName,
          });
          throw error;
        }
      );
    };
  }

  // ---- XMLHttpRequest ----------------------------------------------------

  const OriginalXHR = window.XMLHttpRequest;
  if (OriginalXHR) {
    const origOpen = OriginalXHR.prototype.open;
    const origSend = OriginalXHR.prototype.send;
    const origSetRequestHeader = OriginalXHR.prototype.setRequestHeader;

    OriginalXHR.prototype.open = function (method, url) {
      this.__requestr = { method: method || "GET", url: String(url), headers: {}, start: 0 };
      return origOpen.apply(this, arguments);
    };

    OriginalXHR.prototype.setRequestHeader = function (name, value) {
      if (this.__requestr) this.__requestr.headers[name] = value;
      return origSetRequestHeader.apply(this, arguments);
    };

    OriginalXHR.prototype.send = function (body) {
      const meta = this.__requestr;
      if (!meta) return origSend.apply(this, arguments);

      meta.start = nowMs();
      const requestBodyInfo = captureRequestBody(body);
      const host = hostOf(meta.url);
      const matchedRule = checkRules(meta.url, host);

      if (matchedRule && matchedRule.action === "BLOCK") {
        dispatch({
          url: meta.url,
          method: meta.method,
          requestHeaders: meta.headers,
          statusCode: null,
          durationMs: 0,
          timestamp: nowMs(),
          requestBody: requestBodyInfo.body,
          requestBodyEncoding: requestBodyInfo.bodyEncoding,
          requestBodyTruncated: requestBodyInfo.bodyTruncated,
          ruleAction: "BLOCK",
          ruleId: matchedRule.id,
          ruleName: matchedRule.name,
        });
        this.abort();
        return;
      }

      let effectiveBody = body;
      let ruleAction = matchedRule ? matchedRule.action : "LOG_ONLY";
      let ruleId = matchedRule ? matchedRule.id : null;
      let ruleName = matchedRule ? matchedRule.name : null;

      if (matchedRule && matchedRule.action === "MODIFY_REQUEST") {
        const config = parseModConfig(matchedRule.replacementValue);
        if (config) {
          if (config.addHeaders) {
            for (const k of Object.keys(config.addHeaders)) {
              try {
                this.setRequestHeader(k, config.addHeaders[k]);
              } catch (_e) {
                /* setRequestHeader must be called before send(); if it throws here the
                   header just doesn't get added for this request -- best effort */
              }
            }
          }
          if (typeof config.bodyReplace === "string") effectiveBody = config.bodyReplace;
        }
      }

      const responseRule =
        matchedRule && matchedRule.action === "MODIFY_RESPONSE" ? matchedRule : null;
      let responseModConfig = responseRule ? parseModConfig(responseRule.replacementValue) : null;

      this.addEventListener("loadend", () => {
        let bodySize = 0;
        let responseBody = null;
        let responseBodyEncoding = "none";
        try {
          if (typeof this.responseText === "string") {
            responseBody = this.responseText;
            responseBodyEncoding = "utf8";
            bodySize = this.responseText.length;
          } else if (this.response instanceof ArrayBuffer) {
            const bytes = new Uint8Array(this.response);
            responseBody = bytesToBase64(bytes.subarray(0, Math.min(bytes.byteLength, MAX_BINARY_BODY)));
            responseBodyEncoding = "base64";
            bodySize = bytes.byteLength;
          }
        } catch (_e) {
          // responseText throws for non-text responseType (blob/arraybuffer/document) in
          // some engines -- documented best-effort, not every XHR responseType is captured.
        }

        // Best-effort MODIFY_RESPONSE for XHR: there is no Response object to swap (unlike
        // fetch), so this monkey-patches responseText/status directly. Documented
        // limitation: this only works for access patterns that read these properties AFTER
        // loadend fires (the common case); code reading responseXML before loadend, or via
        // a synchronous XHR, sees the unmodified value. This is a genuine MV3/XHR platform
        // constraint, not a shortcut -- there is no API to replace an XHR's response body.
        if (responseModConfig && typeof responseModConfig.bodyReplace === "string") {
          try {
            Object.defineProperty(this, "responseText", { value: responseModConfig.bodyReplace, configurable: true });
            Object.defineProperty(this, "response", { value: responseModConfig.bodyReplace, configurable: true });
          } catch (_e) {
            /* some engines make these non-configurable; modification silently doesn't apply */
          }
          if (responseModConfig.statusCode) {
            try {
              Object.defineProperty(this, "status", { value: responseModConfig.statusCode, configurable: true });
            } catch (_e) {
              /* ignore */
            }
          }
          responseBody = responseModConfig.bodyReplace;
        }

        dispatch({
          url: meta.url,
          method: meta.method,
          requestHeaders: meta.headers,
          statusCode: this.status || null,
          bodySize,
          durationMs: nowMs() - meta.start,
          timestamp: nowMs(),
          requestBody: requestBodyInfo.body,
          requestBodyEncoding: requestBodyInfo.bodyEncoding,
          requestBodyTruncated: requestBodyInfo.bodyTruncated,
          responseBody,
          responseBodyEncoding,
          responseBodyTruncated: false,
          initiator: window.location.href,
          ruleAction: responseModConfig ? "MODIFY_RESPONSE" : ruleAction,
          ruleId: responseModConfig ? responseRule.id : ruleId,
          ruleName: responseModConfig ? responseRule.name : ruleName,
        });
      });

      return origSend.call(this, effectiveBody);
    };
  }
})();
