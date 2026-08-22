// requestr Browser Bridge SDK -- shared pure-logic helpers.
//
// Deliberately dependency-free (no chrome.* calls) so every function here can
// be unit-tested with plain `node` (see test/unit/utils.test.mjs).

/**
 * Exponential backoff delay in milliseconds for a given retry attempt
 * (0-indexed: attempt 0 is the first retry after an initial failure).
 * Capped at 30s so a long-dead server doesn't push the next retry out
 * past the next `queue-flush` alarm tick (which fires every 30s anyway).
 */
export function backoffDelay(attempt) {
  const n = Number.isFinite(attempt) && attempt >= 0 ? attempt : 0;
  return Math.min(1000 * Math.pow(2, n), 30000);
}

/**
 * Encodes an ArrayBuffer/Uint8Array as base64, capped at maxBytes. Returns
 * `{ base64, truncated }`. Uses chunked String.fromCharCode + btoa because a
 * single spread over a very large Uint8Array can blow the call stack.
 */
export function bodyToBase64(bytes, maxBytes) {
  const arr = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const truncated = arr.byteLength > maxBytes;
  const capped = truncated ? arr.subarray(0, maxBytes) : arr;
  let binary = "";
  const CHUNK = 8192;
  for (let i = 0; i < capped.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, capped.subarray(i, i + CHUNK));
  }
  const base64 = typeof btoa === "function" ? btoa(binary) : Buffer.from(capped).toString("base64");
  return { base64, truncated };
}

/** Concatenates an array of Uint8Array chunks into a single Uint8Array of totalSize bytes. */
export function concatChunks(chunks, totalSize) {
  const out = new Uint8Array(totalSize);
  let offset = 0;
  for (const chunk of chunks) {
    if (offset >= totalSize) break;
    const take = Math.min(chunk.byteLength, totalSize - offset);
    out.set(chunk.subarray(0, take), offset);
    offset += take;
  }
  return out;
}

/** Normalizes fetch Headers / header-tuple-array / plain-object into a plain object. */
export function serializeHeaders(headers) {
  const out = {};
  if (!headers) return out;
  try {
    if (typeof Headers !== "undefined" && headers instanceof Headers) {
      headers.forEach((v, k) => (out[k] = v));
      return out;
    }
    if (Array.isArray(headers)) {
      for (const pair of headers) {
        if (Array.isArray(pair) && pair.length >= 2) out[pair[0]] = pair[1];
      }
      return out;
    }
    if (typeof headers === "object") {
      for (const k of Object.keys(headers)) out[k] = headers[k];
    }
  } catch (_e) {
    /* best effort */
  }
  return out;
}

/**
 * Simple FNV-1a 32-bit hash, hex-encoded. Used only for cheap local change
 * detection (e.g. "did the rules list I cached differ from what I just
 * fetched") -- not a security primitive.
 */
export function hashString(str) {
  let h = 0x811c9dc5;
  const s = String(str == null ? "" : str);
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, "0");
}

/** JSON-safe serialization of a queue event. Events are already plain objects/strings by the
 *  time they reach the queue, so this is just JSON.stringify with a defensive fallback. */
export function toStorageJson(event) {
  try {
    return JSON.stringify(event);
  } catch (_e) {
    return JSON.stringify({ error: "serialization-failed" });
  }
}

/** Inverse of toStorageJson; returns null on parse failure rather than throwing. */
export function fromStorageJson(str) {
  try {
    return JSON.parse(str);
  } catch (_e) {
    return null;
  }
}

/** Trailing-edge debounce: fn is invoked at most once per `ms` after the last call. */
export function debounce(fn, ms) {
  let timer = null;
  return function debounced(...args) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      fn.apply(this, args);
    }, ms);
  };
}

/** Classifies a content-type into an encoding strategy for body capture. */
export function encodingForContentType(contentType) {
  const ct = String(contentType || "").toLowerCase();
  if (
    ct.startsWith("text/") ||
    ct.includes("application/json") ||
    ct.includes("application/xml") ||
    ct.includes("application/javascript") ||
    ct.includes("application/x-www-form-urlencoded") ||
    ct.includes("multipart/form-data") ||
    ct.includes("+json") ||
    ct.includes("+xml")
  ) {
    return "utf8";
  }
  if (ct === "" ) return "utf8"; // no content-type: assume text, cheapest safe default
  return "base64";
}
