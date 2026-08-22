// requestr Browser Bridge SDK -- rules pull + server health channel.
//
// Design decision (see plan-mv3.md "Bidirectional Control Channel"): periodic pull via
// chrome.alarms rather than WebSocket/long-poll, because both of those fight the MV3
// service-worker lifecycle (connections/long-polls die on SW termination and have to be
// re-established on every wake, which is worse than a 30s-cadence pull). The alarm is
// registered by service-worker.mjs; this module only implements what runs on each tick.

import { getConfig, setLastRulesGeneration } from "./config.mjs";
import { getAuthHeaders } from "./pairing.mjs";
import { buildDnrRules, syncDnrRules } from "./rules.mjs";

const RULES_CACHE_KEY = "cachedRules";
const INTERCEPTOR_RULES_KEY = "interceptorRules"; // chrome.storage.session, read by bridge.mjs

let lastHealthOk = null;
let lastHealthAt = 0;

/**
 * Pulls `GET /api/rules`, and if the response's `generation` differs from what's cached,
 * updates the local rule cache, re-syncs DNR dynamic rules, and pushes the
 * interceptor-only subset into chrome.storage.session for bridge.mjs to forward into the
 * MAIN world. Returns `{ changed, rulesCount, error }`.
 */
export async function syncRules() {
  const config = await getConfig();
  const authHeaders = await getAuthHeaders();

  let res;
  try {
    res = await fetch(`${config.apiBase}/api/rules`, { headers: authHeaders });
  } catch (e) {
    return { changed: false, rulesCount: 0, error: String(e && e.message ? e.message : e) };
  }
  if (!res.ok) {
    return { changed: false, rulesCount: 0, error: `HTTP ${res.status}` };
  }
  let json;
  try {
    json = await res.json();
  } catch (e) {
    return { changed: false, rulesCount: 0, error: "invalid JSON from /api/rules" };
  }

  const rules = Array.isArray(json.rules) ? json.rules : Array.isArray(json) ? json : [];
  const generation = Number(json.generation) || 0;

  if (generation !== 0 && generation === config.lastRulesGeneration) {
    return { changed: false, rulesCount: rules.length };
  }

  await chrome.storage.local.set({ [RULES_CACHE_KEY]: rules });
  await setLastRulesGeneration(generation);

  const { dnrRules, skipped } = buildDnrRules(rules);
  await syncDnrRules(dnrRules);

  // The interceptor is pushed the FULL rule list (via chrome.storage.session, forwarded by
  // bridge.mjs into the MAIN world), not just the DNR-skipped subset -- it needs to know
  // about DNR-handled rules too so it can recognize "this request never reached send()
  // because DNR already blocked/redirected it" versus "this request reached send() and I
  // must handle it myself". See interceptor-main.mjs.
  await chrome.storage.session.set({ [INTERCEPTOR_RULES_KEY]: rules });

  return { changed: true, rulesCount: rules.length, dnrRuleCount: dnrRules.length, skippedCount: skipped.length };
}

/** GET /api/ping -- no auth required per plan-sdk-detailed.md item 6 ("/api/ping still
 *  works without auth (public endpoint)"). Used for the popup's connection dot. */
export async function healthCheck() {
  const config = await getConfig();
  try {
    const res = await fetch(`${config.apiBase}/api/ping`, { method: "GET" });
    lastHealthOk = res.ok;
  } catch (_e) {
    lastHealthOk = false;
  }
  lastHealthAt = Date.now();
  return lastHealthOk;
}

export async function getConnectionStatus() {
  const config = await getConfig();
  const cached = await chrome.storage.local.get([RULES_CACHE_KEY]);
  const rules = Array.isArray(cached[RULES_CACHE_KEY]) ? cached[RULES_CACHE_KEY] : [];
  return {
    connected: lastHealthOk === true,
    lastHealthOk,
    lastHealthAt,
    paired: !!config.token,
    rulesCount: rules.length,
  };
}

export async function getCachedRules() {
  const cached = await chrome.storage.local.get([RULES_CACHE_KEY]);
  return Array.isArray(cached[RULES_CACHE_KEY]) ? cached[RULES_CACHE_KEY] : [];
}
