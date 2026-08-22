// requestr Browser Bridge SDK -- declarativeNetRequest synchronization.
//
// DNR is the "fast path" for BLOCK / REDIRECT / MODIFY_REQUEST(headers-only) /
// MODIFY_RESPONSE(headers-only) -- it runs in the browser's network stack before the
// page ever sees the request, so it works even for requests the MAIN-world interceptor
// never gets a chance to touch (sub-resources, service workers, etc). Body modification
// is NOT expressible in DNR (Chrome does not expose request/response body rewriting to
// declarativeNetRequest at all) -- that remains interceptor-only, see interceptor-main.mjs.

const RESOURCE_TYPES = [
  "main_frame",
  "sub_frame",
  "xmlhttprequest",
  "script",
  "stylesheet",
  "image",
  "font",
  "media",
  "websocket",
  "other",
];

/** Builds a DNR condition object for a rule, preferring urlFilter (substring/glob, cheap,
 *  no DNR regex-count budget) and falling back to regexFilter only when `pattern` looks
 *  like it needs real regex semantics. */
function buildCondition(rule) {
  if (rule.domain) {
    return { urlFilter: `||${rule.domain}`, resourceTypes: RESOURCE_TYPES };
  }
  if (rule.pattern) {
    return { regexFilter: rule.pattern, isUrlFilterCaseSensitive: false, resourceTypes: RESOURCE_TYPES };
  }
  return { urlFilter: "*", resourceTypes: RESOURCE_TYPES };
}

/**
 * Converts the server's rule list into DNR dynamic rules. Returns `{ dnrRules, skipped }`
 * where `skipped` lists rules this function deliberately did not translate to DNR (because
 * DNR cannot express them -- body modification -- or because the config JSON didn't parse),
 * so the caller (enforcer/interceptor) knows which rules it must still enforce itself.
 */
export function buildDnrRules(rules) {
  const dnrRules = [];
  const skipped = [];

  for (const rule of rules || []) {
    if (!rule || rule.enabled === false) continue;
    const condition = buildCondition(rule);

    if (rule.action === "BLOCK") {
      dnrRules.push({
        id: Number(rule.id),
        priority: 1,
        action: { type: "block" },
        condition,
      });
      continue;
    }

    if (rule.action === "REDIRECT") {
      if (!rule.replacementValue) {
        skipped.push({ rule, reason: "redirect-missing-target" });
        continue;
      }
      dnrRules.push({
        id: Number(rule.id),
        priority: 1,
        action: { type: "redirect", redirect: { url: rule.replacementValue } },
        condition,
      });
      continue;
    }

    if (rule.action === "MODIFY_REQUEST") {
      let config = null;
      try {
        config = rule.replacementValue ? JSON.parse(rule.replacementValue) : null;
      } catch (_e) {
        skipped.push({ rule, reason: "invalid-json" });
        continue;
      }
      if (!config) {
        skipped.push({ rule, reason: "no-config" });
        continue;
      }
      if (config.bodyReplace) {
        // DNR cannot touch request bodies -- the interceptor must handle this rule instead.
        skipped.push({ rule, reason: "body-modification-requires-interceptor" });
        continue;
      }
      const requestHeaders = [];
      if (config.addHeaders) {
        for (const name of Object.keys(config.addHeaders)) {
          requestHeaders.push({ header: name, operation: "set", value: String(config.addHeaders[name]) });
        }
      }
      if (Array.isArray(config.removeHeaders)) {
        for (const name of config.removeHeaders) {
          requestHeaders.push({ header: name, operation: "remove" });
        }
      }
      if (requestHeaders.length === 0) {
        skipped.push({ rule, reason: "no-header-ops" });
        continue;
      }
      dnrRules.push({
        id: Number(rule.id),
        priority: 1,
        action: { type: "modifyHeaders", requestHeaders },
        condition,
      });
      continue;
    }

    if (rule.action === "MODIFY_RESPONSE") {
      // Response body replacement and status-code override are not expressible in DNR at
      // all (DNR's modifyHeaders only touches headers) -- always interceptor-only.
      skipped.push({ rule, reason: "response-body-requires-interceptor" });
      continue;
    }

    // LOG_ONLY: no enforcement action, DNR has nothing to do.
  }

  return { dnrRules, skipped };
}

/**
 * Applies the given DNR rule set as the extension's *entire* dynamic rule set, replacing
 * whatever was there before (removeRuleIds = all currently-installed dynamic rule ids).
 * Called by channel.mjs after every successful rules sync.
 */
export async function syncDnrRules(dnrRules) {
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const removeRuleIds = existing.map((r) => r.id);
  await chrome.declarativeNetRequest.updateDynamicRules({ removeRuleIds, addRules: dnrRules });
}

/** Rules that need MAIN-world enforcement because DNR can't express them (body
 *  modification of any kind, or a JSON config that failed to parse). Kept for symmetry;
 *  the interceptor primarily works off the same `checkRules()` used for everything else,
 *  this is exposed for the options page's "N rules handled by DNR / N by interceptor" stat. */
export function getInterceptorOnlyRuleIds(skipped) {
  return skipped.map((s) => s.rule && s.rule.id).filter((id) => id !== undefined);
}
