// Unit tests for src/sdk/utils.mjs -- run with: node test/unit/utils.test.mjs
import assert from "node:assert/strict";
import {
  backoffDelay,
  bodyToBase64,
  concatChunks,
  serializeHeaders,
  hashString,
  toStorageJson,
  fromStorageJson,
  encodingForContentType,
} from "../../src/sdk/utils.mjs";

let passed = 0;
function test(name, fn) {
  try {
    fn();
    passed++;
    console.log(`PASS: ${name}`);
  } catch (e) {
    console.error(`FAIL: ${name}`);
    console.error(e);
    process.exitCode = 1;
  }
}

test("backoffDelay: exponential growth capped at 30000ms", () => {
  assert.equal(backoffDelay(0), 1000);
  assert.equal(backoffDelay(1), 2000);
  assert.equal(backoffDelay(2), 4000);
  assert.equal(backoffDelay(3), 8000);
  assert.equal(backoffDelay(4), 16000);
  assert.equal(backoffDelay(5), 30000); // 32000 capped
  assert.equal(backoffDelay(10), 30000);
});

test("backoffDelay: negative/non-finite attempt treated as 0", () => {
  assert.equal(backoffDelay(-1), 1000);
  assert.equal(backoffDelay(NaN), 1000);
});

test("bodyToBase64: round-trips small buffer without truncation", () => {
  const bytes = new Uint8Array([104, 101, 108, 108, 111]); // "hello"
  const { base64, truncated } = bodyToBase64(bytes, 1000);
  assert.equal(truncated, false);
  assert.equal(Buffer.from(base64, "base64").toString("utf8"), "hello");
});

test("bodyToBase64: truncates when exceeding maxBytes", () => {
  const bytes = new Uint8Array(10).fill(65); // "AAAAAAAAAA"
  const { base64, truncated } = bodyToBase64(bytes, 4);
  assert.equal(truncated, true);
  assert.equal(Buffer.from(base64, "base64").length, 4);
});

test("concatChunks: joins chunks respecting totalSize cap", () => {
  const chunks = [new Uint8Array([1, 2]), new Uint8Array([3, 4, 5])];
  const out = concatChunks(chunks, 4);
  assert.deepEqual(Array.from(out), [1, 2, 3, 4]);
});

test("serializeHeaders: handles array-of-pairs and plain object", () => {
  assert.deepEqual(serializeHeaders([["a", "1"], ["b", "2"]]), { a: "1", b: "2" });
  assert.deepEqual(serializeHeaders({ x: "y" }), { x: "y" });
  assert.deepEqual(serializeHeaders(null), {});
});

test("hashString: deterministic and sensitive to content", () => {
  const h1 = hashString("hello");
  const h2 = hashString("hello");
  const h3 = hashString("world");
  assert.equal(h1, h2);
  assert.notEqual(h1, h3);
});

test("toStorageJson/fromStorageJson: round-trips objects", () => {
  const event = { url: "https://example.com", statusCode: 200, requestBody: null };
  const str = toStorageJson(event);
  const back = fromStorageJson(str);
  assert.deepEqual(back, event);
});

test("fromStorageJson: returns null on invalid JSON instead of throwing", () => {
  assert.equal(fromStorageJson("{not json"), null);
});

test("encodingForContentType: text/json/xml/form => utf8, binary => base64", () => {
  assert.equal(encodingForContentType("application/json"), "utf8");
  assert.equal(encodingForContentType("text/html; charset=utf-8"), "utf8");
  assert.equal(encodingForContentType("application/x-www-form-urlencoded"), "utf8");
  assert.equal(encodingForContentType(""), "utf8");
  assert.equal(encodingForContentType("image/png"), "base64");
  assert.equal(encodingForContentType("application/octet-stream"), "base64");
  assert.equal(encodingForContentType("application/pdf"), "base64");
});

console.log(`\n${passed}/${passed + (process.exitCode ? 1 : 0)} test groups passed (see FAIL lines above for failures).`);
