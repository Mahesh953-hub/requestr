// requestr Browser Bridge -- background service worker (MV3 module entry point).
//
// This module runs fresh every time the SW starts (both on browser startup and after the
// SW has been terminated for idling ~30s and something wakes it back up -- e.g. an alarm
// firing or a message arriving). Nothing here relies on module-level state surviving a
// termination; all state that must survive lives in chrome.storage.session (queue.mjs) or
// chrome.storage.local (config.mjs), never in a `let` global (that was background.js's
// bug -- see plan-mv3.md Task 3 grounding).

import { getConfig } from "../sdk/config.mjs";
import { startObserver } from "../sdk/observer.mjs";
import { syncRules, healthCheck } from "../sdk/channel.mjs";
import { drain, purgeDead } from "../sdk/queue.mjs";
import { getStatus } from "../sdk/index.mjs";

const ALARM_QUEUE_FLUSH = "queue-flush";
const ALARM_RULES_SYNC = "rules-sync";
const ALARM_HEALTH_CHECK = "health-check";

async function boot() {
  // getApiBase looks up the live config synchronously via a cached snapshot that observer.mjs
  // refreshes lazily; since config.mjs keeps its own module-level cache updated via
  // chrome.storage.onChanged, re-reading getConfig()'s cache (not this boot-time snapshot)
  // ensures a user changing the API base in the popup takes effect without a SW restart.
  let liveConfig = await getConfig();
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes.requestr_config) {
      liveConfig = Object.assign({}, liveConfig, changes.requestr_config.newValue || {});
    }
  });
  startObserver(() => liveConfig.apiBase);

  // Content scripts run in an "untrusted" storage context by default; bridge.mjs needs to
  // read chrome.storage.session's cached rules to forward them into the MAIN world (see
  // channel.mjs's syncRules() and bridge.mjs's file header for why session storage was
  // chosen over chrome.tabs.sendMessage for this broadcast).
  try {
    await chrome.storage.session.setAccessLevel({ accessLevel: "TRUSTED_AND_UNTRUSTED_CONTEXTS" });
  } catch (_e) {
    /* setAccessLevel can only be called once per session in some Chrome versions and
       throws if already set to the same value -- non-fatal either way. */
  }

  // Immediate pulls on every SW start (covers both cold browser startup and post-idle
  // wake) so the extension doesn't wait up to 30s for its first rules sync / health check.
  await Promise.allSettled([syncRules(), healthCheck(), drain()]);

  chrome.alarms.create(ALARM_QUEUE_FLUSH, { periodInMinutes: 0.5 });
  chrome.alarms.create(ALARM_RULES_SYNC, { periodInMinutes: 0.5 });
  chrome.alarms.create(ALARM_HEALTH_CHECK, { periodInMinutes: 1 });
}

chrome.alarms.onAlarm.addListener(async (alarm) => {
  switch (alarm.name) {
    case ALARM_QUEUE_FLUSH:
      await drain();
      await purgeDead();
      break;
    case ALARM_RULES_SYNC:
      await syncRules();
      break;
    case ALARM_HEALTH_CHECK:
      await healthCheck();
      break;
    default:
      break;
  }
});

// Popup/options request an immediate rules pull + health check on open, awaiting the
// result before rendering (see ui/popup.mjs). Also handles "flush-now" for the manual
// "Refresh Rules Now"/drain actions in the UI.
chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message && message.type === "force-sync") {
    Promise.allSettled([syncRules(), healthCheck()]).then(() => sendResponse({ ok: true }));
    return true; // keep the message channel open for the async sendResponse
  }
  if (message && message.type === "force-flush") {
    drain().then((result) => sendResponse({ ok: true, result }));
    return true;
  }
  if (message && message.type === "get-status") {
    getStatus().then((status) => sendResponse(status));
    return true;
  }
});

boot();
