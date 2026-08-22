// Unit tests for src/sdk/enforcer.mjs -- run with: node test/unit/enforcer.test.mjs
import assert from "node:assert/strict";
import {
  checkRules,
  parseRequestModification,
  parseResponseModification,
  applyRequestModification,
  applyResponseModification,
} from "../../src/sdk/enforcer.mjs";

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

const rules = [
  { id: 1, name: "Block ads", domain: "doubleclick.net", pattern: "", action: "BLOCK", enabled: true },
  { id: 2, name: "Redirect CDN", domain: "", pattern: "cdn\\.example\\.com", action: "REDIRECT", enabled: true, replacementValue: "https://local-mirror.example.com" },
  { id: 3, name: "Inject header", domain: "", pattern: "api\\.example\\.com", action: "MODIFY_REQUEST", enabled: true, replacementValue: '{"addHeaders":{"X-Injected":"true"},"removeHeaders":["Cookie"]}' },
  { id: 4, name: "Mock response", domain: "", pattern: "api\\.example\\.com/data", action: "MODIFY_RESPONSE", enabled: true, replacementValue: '{"statusCode":200,"addHeaders":{"X-Mock":"true"},"bodyReplace":"{\\"mocked\\":true}"}' },
  { id: 5, name: "Disabled rule", domain: "shouldnotmatch.test", pattern: "", action: "BLOCK", enabled: false },
];

test("checkRules: domain substring match (case-insensitive)", () => {
  const rule = checkRules(rules, "https://ad.DOUBLECLICK.net/x", "ad.DOUBLECLICK.net");
  assert.equal(rule.id, 1);
});

test("checkRules: pattern regex match against full URL", () => {
  const rule = checkRules(rules, "https://cdn.example.com/asset.js", "cdn.example.com");
  assert.equal(rule.id, 2);
});

test("checkRules: first matching enabled rule wins (order preserved)", () => {
  const rule = checkRules(rules, "https://api.example.com/data?x=1", "api.example.com");
  // Rule 3's pattern "api\\.example\\.com" ALSO matches this URL; since it's earlier in
  // the array than rule 4, rule 3 must win -- mirrors RuleEnforcer.kt's "first match wins".
  assert.equal(rule.id, 3);
});

test("checkRules: disabled rules never match even if domain/pattern would otherwise hit", () => {
  const rule = checkRules(rules, "https://shouldnotmatch.test/x", "shouldnotmatch.test");
  assert.equal(rule, null);
});

test("checkRules: no match returns null", () => {
  const rule = checkRules(rules, "https://totally-unrelated.test/", "totally-unrelated.test");
  assert.equal(rule, null);
});

test("checkRules: invalid regex pattern from server does not throw", () => {
  const badRules = [{ id: 9, domain: "", pattern: "(unclosed", action: "BLOCK", enabled: true }];
  assert.doesNotThrow(() => checkRules(badRules, "https://x.test/", "x.test"));
});

test("parseRequestModification: exact JSON shape mirroring RuleEnforcer.kt", () => {
  const config = parseRequestModification('{"addHeaders":{"X-Custom":"value"},"removeHeaders":["Cookie"],"bodyReplace":"new body"}');
  assert.deepEqual(config, { addHeaders: { "X-Custom": "value" }, removeHeaders: ["Cookie"], bodyReplace: "new body" });
});

test("parseRequestModification: invalid JSON returns null", () => {
  assert.equal(parseRequestModification("not json"), null);
});

test("parseRequestModification: null/empty replacementValue returns null", () => {
  assert.equal(parseRequestModification(null), null);
  assert.equal(parseRequestModification(""), null);
});

test("parseResponseModification: exact JSON shape mirroring RuleEnforcer.kt", () => {
  const config = parseResponseModification('{"statusCode":200,"addHeaders":{"X-Mock":"true"},"bodyReplace":"{}"}');
  assert.deepEqual(config, { statusCode: 200, addHeaders: { "X-Mock": "true" }, bodyReplace: "{}" });
});

test("applyRequestModification: adds and removes headers, replaces body", () => {
  const config = { addHeaders: { "X-New": "1" }, removeHeaders: ["Cookie"], bodyReplace: "replaced" };
  const { headers, body } = applyRequestModification({ Cookie: "abc", Accept: "*/*" }, "original", config);
  assert.deepEqual(headers, { Accept: "*/*", "X-New": "1" });
  assert.equal(body, "replaced");
});

test("applyRequestModification: null config is a no-op", () => {
  const { headers, body } = applyRequestModification({ A: "1" }, "orig", null);
  assert.deepEqual(headers, { A: "1" });
  assert.equal(body, "orig");
});

test("applyResponseModification: overrides status/headers/body", () => {
  const config = { statusCode: 404, addHeaders: { "X-Mock": "true" }, bodyReplace: "mocked" };
  const { statusCode, headers, body } = applyResponseModification(200, { A: "1" }, "orig", config);
  assert.equal(statusCode, 404);
  assert.deepEqual(headers, { A: "1", "X-Mock": "true" });
  assert.equal(body, "mocked");
});

if (failures > 0) {
  console.error(`\n${failures} test(s) FAILED`);
  process.exitCode = 1;
} else {
  console.log("\nAll enforcer tests passed.");
}
