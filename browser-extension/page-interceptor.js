// requestr Browser Bridge -- MAIN-world page interceptor.
//
// IMPORTANT: content scripts declared with the default ("ISOLATED") world share the DOM
// with the page but run in a *separate* JS environment -- patching window.fetch /
// XMLHttpRequest there does NOT affect fetch/XHR calls made by the page's own scripts,
// because the page sees its own, unpatched window.fetch. To actually intercept
// page-initiated calls, this file is injected into the page's MAIN world (see
// manifest.json's `"world": "MAIN"` entry, MV3/Chrome 111+), so it patches the *same*
// window.fetch / XMLHttpRequest the page's own JS calls.
//
// MAIN-world scripts have no access to chrome.* extension APIs (they are indistinguishable
// from a script the page itself loaded), so this file cannot call chrome.runtime.sendMessage
// directly. Instead it dispatches a CustomEvent on `window`, which content.js (running in
// the isolated world, which *can* see DOM events dispatched by MAIN-world code) picks up and
// relays to the background service worker.
(function () {
  "use strict";

  const EVENT_NAME = "__requestr_fetch_observed__";

  function dispatch(event) {
    try {
      window.dispatchEvent(new CustomEvent(EVENT_NAME, { detail: event }));
    } catch (_e) {
      /* page may have frozen/disabled CustomEvent in some CSP setups -- best effort only */
    }
  }

  function nowMs() {
    return Date.now();
  }

  // ---- fetch() ----------------------------------------------------------

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

      const headers = {};
      try {
        const h = init && init.headers;
        if (h) {
          if (h instanceof Headers) {
            h.forEach((v, k) => (headers[k] = v));
          } else if (Array.isArray(h)) {
            for (const [k, v] of h) headers[k] = v;
          } else if (typeof h === "object") {
            for (const k of Object.keys(h)) headers[k] = h[k];
          }
        }
      } catch (_e) {
        /* best effort */
      }

      return originalFetch.apply(this, arguments).then(
        (response) => {
          const durationMs = nowMs() - start;
          let bodySize = 0;
          try {
            const cl = response.headers.get("content-length");
            if (cl) bodySize = parseInt(cl, 10) || 0;
          } catch (_e) {
            /* ignore */
          }
          dispatch({
            url,
            method,
            headers,
            statusCode: response.status,
            bodySize,
            durationMs,
            timestamp: nowMs(),
          });
          return response;
        },
        (error) => {
          const durationMs = nowMs() - start;
          dispatch({
            url,
            method,
            headers,
            statusCode: -1,
            bodySize: 0,
            durationMs,
            timestamp: nowMs(),
          });
          throw error;
        }
      );
    };
  }

  // ---- XMLHttpRequest -----------------------------------------------------

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

    OriginalXHR.prototype.send = function () {
      const meta = this.__requestr;
      if (meta) {
        meta.start = nowMs();
        this.addEventListener("loadend", () => {
          let bodySize = 0;
          try {
            const cl = this.getResponseHeader("content-length");
            if (cl) bodySize = parseInt(cl, 10) || 0;
            else if (typeof this.responseText === "string") bodySize = this.responseText.length;
          } catch (_e) {
            /* ignore, cross-origin response headers may be inaccessible */
          }
          dispatch({
            url: meta.url,
            method: meta.method,
            headers: meta.headers,
            statusCode: this.status || -1,
            bodySize,
            durationMs: nowMs() - meta.start,
            timestamp: nowMs(),
          });
        });
      }
      return origSend.apply(this, arguments);
    };
  }
})();
