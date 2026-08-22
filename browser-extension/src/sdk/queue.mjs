// requestr Browser Bridge SDK -- persistent event queue.
//
// Survives service-worker termination by living in chrome.storage.session
// (in-memory, 10 MB quota, cleared only on browser restart/extension
// reload -- NOT on SW idle timeout) instead of a `let` global + setInterval,
// which is what background.js did before (dies after ~30s idle, see
// plan-mv3.md Task 3 grounding). Flushing is driven by chrome.alarms from
// service-worker.mjs, not by a timer owned by this module, so this module
// stays pure enough to unit-test the retry/backoff math without chrome.*.

import { getConfig } from "./config.mjs";
import { getAuthHeaders } from "./pairing.mjs";
import { backoffDelay } from "./utils.mjs";

const QUEUE_KEY = "eventQueue";
const DEAD_KEY = "deadQueue";
const MAX_QUEUE = 500;
const MAX_DEAD = 200;
const MAX_RETRIES = 5;
const DEAD_TTL_MS = 60 * 60 * 1000; // 1 hour

let droppedCount = 0;
let relayedCount = 0;
let lastError = null;
let lastFlushAt = 0;

/** Pure function (unit-tested directly): given a queue length and the configured
 *  max, returns how many oldest items to drop to stay within budget. */
export function overflowDropCount(currentLength, maxLength) {
  if (currentLength <= maxLength) return 0;
  return currentLength - maxLength;
}

/** Pure function: partitions events into keep/dead based on retry count, mirroring
 *  what flush() does against real storage. Exposed for unit testing without chrome.*. */
export function partitionByRetries(events, maxRetries) {
  const keep = [];
  const dead = [];
  for (const e of events) {
    if ((e.retries || 0) > maxRetries) dead.push(e);
    else keep.push(e);
  }
  return { keep, dead };
}

async function readSession(key) {
  const res = await chrome.storage.session.get([key]);
  return Array.isArray(res[key]) ? res[key] : [];
}

async function writeSession(key, arr) {
  await chrome.storage.session.set({ [key]: arr });
}

/** Adds an event to the persistent queue. Called by observer.mjs for every
 *  normalized webRequest/interceptor event. */
export async function enqueue(event) {
  const queue = await readSession(QUEUE_KEY);
  queue.push(Object.assign({ retries: 0 }, event));
  const drop = overflowDropCount(queue.length, MAX_QUEUE);
  if (drop > 0) {
    queue.splice(0, drop);
    droppedCount += drop;
  }
  await writeSession(QUEUE_KEY, queue);
}

/** Moves expired dead-letter entries out entirely (never resurrected -- they're gone). */
export async function purgeDead() {
  const dead = await readSession(DEAD_KEY);
  const now = Date.now();
  const kept = dead.filter((e) => now - (e.deadAt || 0) < DEAD_TTL_MS);
  if (kept.length !== dead.length) await writeSession(DEAD_KEY, kept);
  return dead.length - kept.length;
}

/**
 * Flushes the queue: POSTs the current batch to `${apiBase}/api/observe`
 * with the pairing token. On success, clears the flushed items from
 * storage. On failure, increments each item's retry counter; items that
 * exceed MAX_RETRIES move to the dead-letter queue instead of being retried
 * forever. Never silently drops a batch without accounting for it in either
 * the persisted queue, the dead-letter queue, or droppedCount.
 */
export async function flush() {
  const queue = await readSession(QUEUE_KEY);
  if (queue.length === 0) return { sent: 0, failed: 0 };

  const config = await getConfig();
  const authHeaders = await getAuthHeaders();

  let ok = false;
  let statusForRetryDecision = null;
  try {
    const res = await fetch(`${config.apiBase}/api/observe`, {
      method: "POST",
      headers: Object.assign({ "Content-Type": "application/json" }, authHeaders),
      body: JSON.stringify({ events: queue.map(stripRetryMeta) }),
    });
    ok = res.ok;
    statusForRetryDecision = res.status;
    if (!ok) lastError = `HTTP ${res.status}`;
  } catch (e) {
    lastError = String(e && e.message ? e.message : e);
  }

  lastFlushAt = Date.now();

  if (ok) {
    relayedCount += queue.length;
    lastError = null;
    await writeSession(QUEUE_KEY, []);
    return { sent: queue.length, failed: 0 };
  }

  // A 401 means the token is wrong/missing -- retrying immediately won't help,
  // but we still keep the events (server may get paired later) rather than
  // dropping them, since that's a config problem, not a data problem.
  const bumped = queue.map((e) => Object.assign({}, e, { retries: (e.retries || 0) + 1 }));
  const { keep, dead } = partitionByRetries(bumped, MAX_RETRIES);
  await writeSession(QUEUE_KEY, keep);
  if (dead.length > 0) {
    const existingDead = await readSession(DEAD_KEY);
    const now = Date.now();
    const newDead = existingDead.concat(dead.map((e) => Object.assign({}, e, { deadAt: now })));
    const drop = overflowDropCount(newDead.length, MAX_DEAD);
    if (drop > 0) newDead.splice(0, drop);
    await writeSession(DEAD_KEY, newDead);
  }
  return { sent: 0, failed: keep.length, deadLettered: dead.length, statusCode: statusForRetryDecision };
}

function stripRetryMeta(event) {
  const { retries, deadAt, ...rest } = event;
  return rest;
}

/** Flushes immediately; used on SW startup and popup "Refresh now" actions. Also
 *  called from the alarm handler. */
export async function drain() {
  return flush();
}

/** Snapshot for popup/options status display. */
export async function getQueueStatus() {
  const queue = await readSession(QUEUE_KEY);
  const dead = await readSession(DEAD_KEY);
  return {
    queued: queue.length,
    dead: dead.length,
    droppedCount,
    relayedCount,
    lastError,
    lastFlushAt,
  };
}

/** Returns the backoff delay (ms) that would apply to the given event's next retry.
 *  Not currently used to schedule anything (chrome.alarms fires on a fixed 30s cadence
 *  regardless), but exposed so the options page can show "next retry in ~Ns" and so the
 *  math is unit-testable. */
export function nextRetryDelayMs(event) {
  return backoffDelay(event.retries || 0);
}
