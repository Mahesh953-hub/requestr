// requestr Browser Bridge SDK -- persistent configuration.
//
// Backed by chrome.storage.local (survives service-worker termination AND
// browser restarts, unlike chrome.storage.session which is used only for the
// ephemeral event queue -- see queue.mjs).

const STORAGE_KEY = "requestr_config";

export const DEFAULT_CONFIG = Object.freeze({
  apiBase: "http://localhost:9876",
  token: "",
  maxBodyBytes: 1048576, // 1 MB
  flushIntervalMs: 2000,
  enforcementEnabled: true,
  lastRulesGeneration: 0,
});

let cache = null;
const listeners = new Set();

function notify(config) {
  for (const fn of listeners) {
    try {
      fn(config);
    } catch (_e) {
      /* listener errors must not break other listeners */
    }
  }
}

/** Reads the full config, seeding defaults on first run. Always resolves (never rejects). */
export async function getConfig() {
  if (cache) return cache;
  const stored = await chrome.storage.local.get([STORAGE_KEY]);
  cache = Object.assign({}, DEFAULT_CONFIG, stored[STORAGE_KEY] || {});
  return cache;
}

async function patch(partial) {
  const current = await getConfig();
  cache = Object.assign({}, current, partial);
  await chrome.storage.local.set({ [STORAGE_KEY]: cache });
  notify(cache);
  return cache;
}

export async function setApiBase(url) {
  const value = (url || "").trim() || DEFAULT_CONFIG.apiBase;
  return patch({ apiBase: value });
}

/** Stores the pairing token pasted by the user from requestr's Settings screen.
 *  Never logged -- callers must not console.log the return value's token field. */
export async function setToken(token) {
  return patch({ token: String(token || "") });
}

export async function setMaxBodyBytes(n) {
  const value = Number.isFinite(n) && n > 0 ? Math.floor(n) : DEFAULT_CONFIG.maxBodyBytes;
  return patch({ maxBodyBytes: value });
}

export async function setFlushIntervalMs(ms) {
  const value = Number.isFinite(ms) && ms > 0 ? Math.floor(ms) : DEFAULT_CONFIG.flushIntervalMs;
  return patch({ flushIntervalMs: value });
}

export async function setEnforcementEnabled(enabled) {
  return patch({ enforcementEnabled: !!enabled });
}

export async function setLastRulesGeneration(gen) {
  return patch({ lastRulesGeneration: Number(gen) || 0 });
}

/** Subscribes to config changes. Returns an unsubscribe function. */
export function onConfigChange(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

// Keep the in-memory cache in sync if another context (popup/options) writes
// chrome.storage.local directly via patch() above running in a different
// execution context (each has its own module cache).
if (typeof chrome !== "undefined" && chrome.storage && chrome.storage.onChanged) {
  chrome.storage.onChanged.addListener((changes, areaName) => {
    if (areaName !== "local" || !changes[STORAGE_KEY]) return;
    cache = Object.assign({}, DEFAULT_CONFIG, changes[STORAGE_KEY].newValue || {});
    notify(cache);
  });
}

/** Test-only reset hook so unit tests don't leak state across cases. Not used at runtime. */
export function __resetCacheForTests() {
  cache = null;
}
