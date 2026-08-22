// requestr Browser Bridge SDK -- rule-matching engine.
//
// Mirrors RuleEnforcer.kt's matching semantics exactly (app/src/main/java/com/example/
// interception/RuleEnforcer.kt): domain is a case-insensitive substring match against the
// host, pattern is a case-insensitive regex matched against the full URL, first matching
// enabled rule wins. Deliberately pure (no chrome.* calls) so it's unit-testable with
// plain `node` -- see test/unit/enforcer.test.mjs.

/**
 * Returns the first matching enabled rule, or null. `rules` is the array cached from
 * `GET /api/rules` (see channel.mjs), each shaped like:
 * `{ id, name, pattern, domain, action, enabled, replacementValue }`.
 */
export function checkRules(rules, url, host) {
  if (!Array.isArray(rules)) return null;
  for (const rule of rules) {
    if (!rule || rule.enabled === false) continue;
    const matchesDomain =
      typeof rule.domain === "string" &&
      rule.domain.length > 0 &&
      typeof host === "string" &&
      host.toLowerCase().includes(rule.domain.toLowerCase());
    let matchesPattern = false;
    if (typeof rule.pattern === "string" && rule.pattern.length > 0) {
      try {
        matchesPattern = new RegExp(rule.pattern, "i").test(url);
      } catch (_e) {
        matchesPattern = false; // invalid regex from server -- never let it throw here
      }
    }
    if (matchesDomain || matchesPattern) return rule;
  }
  return null;
}

/**
 * Parses a rule's replacementValue as the request-modification JSON config, matching
 * RuleEnforcer.kt's applyRequestModification() shape exactly:
 * `{"addHeaders":{...}, "removeHeaders":[...], "bodyReplace":"..."}`.
 * Returns `{ addHeaders, removeHeaders, bodyReplace }` with defaults for missing keys, or
 * null if replacementValue isn't valid JSON.
 */
export function parseRequestModification(replacementValue) {
  if (!replacementValue) return null;
  try {
    const json = JSON.parse(replacementValue);
    return {
      addHeaders: json.addHeaders && typeof json.addHeaders === "object" ? json.addHeaders : null,
      removeHeaders: Array.isArray(json.removeHeaders) ? json.removeHeaders : null,
      bodyReplace: typeof json.bodyReplace === "string" ? json.bodyReplace : null,
    };
  } catch (_e) {
    return null;
  }
}

/**
 * Parses a rule's replacementValue as the response-modification JSON config, matching
 * RuleEnforcer.kt's applyResponseModification() shape exactly:
 * `{"statusCode":200, "addHeaders":{...}, "bodyReplace":"..."}`.
 */
export function parseResponseModification(replacementValue) {
  if (!replacementValue) return null;
  try {
    const json = JSON.parse(replacementValue);
    return {
      statusCode: Number.isInteger(json.statusCode) ? json.statusCode : null,
      addHeaders: json.addHeaders && typeof json.addHeaders === "object" ? json.addHeaders : null,
      bodyReplace: typeof json.bodyReplace === "string" ? json.bodyReplace : null,
    };
  } catch (_e) {
    return null;
  }
}

/** Applies a parsed request-modification config to a Headers-like plain object + body string.
 *  Returns new { headers, body } -- does not mutate the input. */
export function applyRequestModification(headers, body, config) {
  const outHeaders = Object.assign({}, headers);
  let outBody = body;
  if (!config) return { headers: outHeaders, body: outBody };
  if (config.addHeaders) {
    for (const k of Object.keys(config.addHeaders)) outHeaders[k] = config.addHeaders[k];
  }
  if (config.removeHeaders) {
    for (const k of config.removeHeaders) {
      const match = Object.keys(outHeaders).find((h) => h.toLowerCase() === k.toLowerCase());
      if (match) delete outHeaders[match];
    }
  }
  if (config.bodyReplace !== null && config.bodyReplace !== undefined) {
    outBody = config.bodyReplace;
  }
  return { headers: outHeaders, body: outBody };
}

/** Applies a parsed response-modification config. Returns new { statusCode, headers, body }. */
export function applyResponseModification(statusCode, headers, body, config) {
  let outStatus = statusCode;
  const outHeaders = Object.assign({}, headers);
  let outBody = body;
  if (!config) return { statusCode: outStatus, headers: outHeaders, body: outBody };
  if (config.statusCode !== null && config.statusCode !== undefined) outStatus = config.statusCode;
  if (config.addHeaders) {
    for (const k of Object.keys(config.addHeaders)) outHeaders[k] = config.addHeaders[k];
  }
  if (config.bodyReplace !== null && config.bodyReplace !== undefined) outBody = config.bodyReplace;
  return { statusCode: outStatus, headers: outHeaders, body: outBody };
}
