// requestr Browser Bridge -- isolated-world content script bridge.
//
// This script runs in the content script's normal ISOLATED world (shares the DOM with the
// page, but has its own JS globals, distinct from the page's own window.fetch/XHR). It does
// two things:
//
//   1. Listens for the CustomEvent dispatched by page-interceptor.js (which is injected into
//      the page's MAIN world -- see manifest.json's content_scripts entry with
//      "world": "MAIN" -- and therefore patches the *actual* window.fetch/XMLHttpRequest the
//      page's own scripts call). Isolated-world content scripts CAN observe DOM events
//      dispatched by MAIN-world code even though they can't see MAIN-world variables
//      directly, which is what makes this bridge possible.
//   2. Relays each captured event to the background service worker via
//      chrome.runtime.sendMessage, since only isolated-world content scripts (and the
//      background worker itself) have access to chrome.* extension APIs -- MAIN-world code
//      does not.
(function () {
  "use strict";

  const EVENT_NAME = "__requestr_fetch_observed__";

  window.addEventListener(EVENT_NAME, (e) => {
    const detail = e && e.detail;
    if (!detail || !detail.url) return;
    try {
      chrome.runtime.sendMessage({
        source: "requestr-content",
        type: "fetch-observed",
        event: detail,
      });
    } catch (_e) {
      // Extension context invalidated (e.g. extension reloaded mid-session) -- nothing
      // meaningful to recover from page context, drop silently.
    }
  });
})();
