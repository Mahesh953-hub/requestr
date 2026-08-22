// requestr Browser Bridge -- isolated-world content script bridge.
//
// Runs in the content script's normal ISOLATED world (shares the DOM with the page, but
// has its own JS globals distinct from the page's own window.fetch/XHR). Two directions:
//
//   1. MAIN-world -> service worker (event relay): listens for the CustomEvent dispatched
//      by src/content/interceptor-main.mjs and relays it via chrome.runtime.sendMessage,
//      since only isolated-world content scripts (and the background worker itself) have
//      chrome.* API access -- MAIN-world code does not.
//   2. service worker -> MAIN-world (rule/config push): forwards rule updates written to
//      chrome.storage.session by channel.mjs into the MAIN world via window.postMessage,
//      which crosses the ISOLATED -> MAIN boundary because both worlds share the same DOM
//      `window` object even though they have separate JS contexts.
//
// Loaded as a classic (non-module) content script, same constraint as interceptor-main.mjs.
(function () {
  "use strict";

  const EVENT_NAME = "__requestr_fetch_observed__";
  const RULES_UPDATE_MESSAGE = "__requestr_rules_update__";
  const INTERCEPTOR_RULES_KEY = "interceptorRules";

  // ---- 1. MAIN-world event -> service worker ------------------------------

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

  // ---- 2. chrome.storage.session (rules) -> MAIN-world postMessage -------
  //
  // Uses chrome.storage.session rather than chrome.tabs.sendMessage for rule broadcast so
  // the service worker doesn't need to track tab ids (a tab loaded before the SW started
  // wouldn't otherwise be reachable without an explicit re-query of all tabs on every
  // sync). Requires TRUSTED_AND_UNTRUSTED_CONTEXTS access level, set once by
  // service-worker.mjs so content scripts (untrusted context) can read this key.

  function forwardRules(rules, enforcementEnabled, maxBodyBytes) {
    window.postMessage(
      { type: RULES_UPDATE_MESSAGE, rules, enforcementEnabled, maxBodyBytes },
      window.location.origin === "null" ? "*" : window.location.origin
    );
  }

  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName !== "session") return;
    if (changes[INTERCEPTOR_RULES_KEY]) {
      chrome.storage.local.get(["requestr_config"], (cfg) => {
        const config = (cfg && cfg.requestr_config) || {};
        forwardRules(
          changes[INTERCEPTOR_RULES_KEY].newValue || [],
          config.enforcementEnabled !== false,
          config.maxBodyBytes
        );
      });
    }
  });

  // Prime the MAIN-world interceptor with whatever's already cached, in case this content
  // script (re)loaded after the last rules sync rather than before it.
  chrome.storage.session.get([INTERCEPTOR_RULES_KEY], (res) => {
    const rules = res && res[INTERCEPTOR_RULES_KEY];
    if (Array.isArray(rules)) {
      chrome.storage.local.get(["requestr_config"], (cfg) => {
        const config = (cfg && cfg.requestr_config) || {};
        forwardRules(rules, config.enforcementEnabled !== false, config.maxBodyBytes);
      });
    }
  });
})();
