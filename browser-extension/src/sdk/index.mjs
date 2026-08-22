// requestr Browser Bridge SDK -- public API surface.
//
// Re-exports the pieces popup.mjs / options.mjs need without reaching into individual
// sdk/* modules directly, and provides a small facade (init/getStatus/connect/disconnect)
// matching plan-mv3.md Task 2's contract.

import { SDK_VERSION, SDK_BUILD } from "./version.mjs";
import { getConfig, setApiBase, setToken, setEnforcementEnabled } from "./config.mjs";
import { getAuthHeaders, isPaired, unpair, fetchPairingInfo } from "./pairing.mjs";
import { syncRules, healthCheck, getConnectionStatus, getCachedRules } from "./channel.mjs";
import { getQueueStatus, drain } from "./queue.mjs";
import { getObserverCounts } from "./observer.mjs";

export { SDK_VERSION, SDK_BUILD };
export { getConfig, setApiBase, setToken, setEnforcementEnabled };
export { getAuthHeaders, isPaired, unpair, fetchPairingInfo };
export { syncRules, healthCheck, getConnectionStatus, getCachedRules };
export { getQueueStatus, drain };
export { getObserverCounts };

/** Aggregate status blob for the popup/options UI. */
export async function getStatus() {
  const config = await getConfig();
  const connection = await getConnectionStatus();
  const queue = await getQueueStatus();
  const observer = getObserverCounts();
  return {
    version: SDK_VERSION,
    build: SDK_BUILD,
    apiBase: config.apiBase,
    enforcementEnabled: config.enforcementEnabled,
    connection,
    queue,
    observer,
  };
}

/** Forces an immediate rules pull -- used by the popup's "Refresh Rules Now" button and
 *  on popup-open. */
export async function refreshRules() {
  return syncRules();
}
