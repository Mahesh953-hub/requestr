// Unit tests for the pure logic in src/sdk/queue.mjs (no chrome.* dependency required
// for these three functions -- they're the parts genuinely testable with plain node).
// Run with: node test/unit/queue.test.mjs
import assert from "node:assert/strict";
import { overflowDropCount, partitionByRetries, nextRetryDelayMs } from "../../src/sdk/queue.mjs";

let failures = 0;
function test(name, fn) {
  try {
    fn();
    console.log(`PASS: ${name}`);
  } catch (e) {
    failures++;
    console.error(`FAIL: ${name}`);
    console.error(e);
  }
}

test("overflowDropCount: returns 0 when under the limit", () => {
  assert.equal(overflowDropCount(10, 500), 0);
  assert.equal(overflowDropCount(500, 500), 0);
});

test("overflowDropCount: returns exact overflow amount", () => {
  assert.equal(overflowDropCount(510, 500), 10);
  assert.equal(overflowDropCount(1000, 500), 500);
});

test("partitionByRetries: keeps events at or below maxRetries, dead-letters the rest", () => {
  const events = [
    { url: "a", retries: 0 },
    { url: "b", retries: 5 },
    { url: "c", retries: 6 },
    { url: "d", retries: 10 },
  ];
  const { keep, dead } = partitionByRetries(events, 5);
  assert.deepEqual(keep.map((e) => e.url), ["a", "b"]);
  assert.deepEqual(dead.map((e) => e.url), ["c", "d"]);
});

test("partitionByRetries: events with no retries field default to 0 (kept)", () => {
  const { keep, dead } = partitionByRetries([{ url: "x" }], 5);
  assert.equal(keep.length, 1);
  assert.equal(dead.length, 0);
});

test("nextRetryDelayMs: matches backoffDelay semantics per event's retry count", () => {
  assert.equal(nextRetryDelayMs({ retries: 0 }), 1000);
  assert.equal(nextRetryDelayMs({ retries: 1 }), 2000);
  assert.equal(nextRetryDelayMs({ retries: 10 }), 30000);
  assert.equal(nextRetryDelayMs({}), 1000); // missing retries defaults to attempt 0
});

if (failures > 0) {
  console.error(`\n${failures} test(s) FAILED`);
  process.exitCode = 1;
} else {
  console.log("\nAll queue tests passed.");
}
