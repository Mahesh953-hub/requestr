// requestr Browser Bridge SDK -- pairing / auth header helper.
//
// CROSS-SCOPE CONTRACT NOTE (read before touching this file):
// The Android SDK scope (docs/plans/plan-sdk-detailed.md, work item 6) defines
// the *actual* pairing scheme LocalApiServer.kt implements: the token is
// generated and stored SERVER-SIDE (in requestr's own DataStore) when the
// Local API Server is started, and is only ever revealed to the user via a
// "Show Pairing Token" switch in requestr's Settings screen (auto-hides again
// after 5 minutes). There is no `POST /api/pair` handshake endpoint -- the
// user copies the token shown in Settings and pastes it into this extension's
// options page. This deliberately differs from plan-mv3.md's Task 4, which
// assumed an automated handshake; that assumption is superseded by the
// authoritative sibling-scope schema and is not implemented here. See the
// mv3 scope's final report for this note.
//
// So this module does exactly three things:
//   1. Hold the pasted token (via config.mjs, chrome.storage.local).
//   2. Produce the `Authorization: Bearer <token>` header for every
//      authenticated call the extension makes (`/api/observe`, `/api/rules`).
//   3. Report whether a token has been configured at all ("paired" from the
//      extension's point of view means "a token has been entered", since the
//      extension has no way to independently verify server-side acceptance
//      short of a successful authenticated call -- see isLikelyPaired()).
//
// The token is NEVER logged: no console.log/warn/error call in this file (or
// anywhere in the SDK) may include config.token.

import { getConfig, setToken } from "./config.mjs";

/** Returns the Authorization header object to spread into a fetch() init.headers,
 *  or {} if no token has been configured yet (server will 401 on protected routes). */
export async function getAuthHeaders() {
  const config = await getConfig();
  if (!config.token) return {};
  return { Authorization: `Bearer ${config.token}` };
}

/** Whether the user has entered a token at all. Does not verify server acceptance --
 *  that's reported separately by channel.mjs's health check (a 401 there means the
 *  token is wrong even though isPaired() here is true). */
export async function isPaired() {
  const config = await getConfig();
  return !!config.token;
}

/**
 * Convenience path for the "Pair automatically" button: GETs
 * `${apiBase}/api/pairing-info`, which the server only serves while the user
 * has "Show Pairing Token" switched on in requestr's Settings screen (see
 * the file header note). Returns `{ ok: true }` on success after storing the
 * token, or `{ ok: false, reason }` -- `reason` is one of "unreachable"
 * (fetch/network failure), "hidden" (403 -- toggle is off), or "invalid"
 * (200 but no usable token field).
 *
 * Never logs the fetched token.
 */
export async function fetchPairingInfo(apiBase) {
  let res;
  try {
    res = await fetch(`${apiBase}/api/pairing-info`, { method: "GET" });
  } catch (_e) {
    return { ok: false, reason: "unreachable" };
  }
  if (res.status === 403) return { ok: false, reason: "hidden" };
  if (!res.ok) return { ok: false, reason: "unreachable" };
  let json;
  try {
    json = await res.json();
  } catch (_e) {
    return { ok: false, reason: "invalid" };
  }
  if (!json || typeof json.token !== "string" || !json.token) {
    return { ok: false, reason: "invalid" };
  }
  await setToken(json.token);
  return { ok: true };
}

/** Clears the stored token. Purely local -- there is no server-side unpair call in the
 *  authoritative sibling-scope schema (see file header); the server has no bookkeeping
 *  of "which client holds the token" to clear, only the token value itself. */
export async function unpair() {
  await setToken("");
}
