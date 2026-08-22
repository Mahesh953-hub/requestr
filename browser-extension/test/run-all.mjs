#!/usr/bin/env node
// requestr Browser Bridge -- verification harness.
// Runs: (1) node --check on every .mjs file, (2) manifest.json JSON validation +
// referenced-file-exists checks, (3) all unit test files under test/unit/.
// Usage: node test/run-all.mjs   (run from browser-extension/ or anywhere; paths are
// resolved relative to this file).

import { execFileSync } from "node:child_process";
import { readFileSync, existsSync, readdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
let failures = 0;

function section(title) {
  console.log(`\n=== ${title} ===`);
}

function findMjsFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === "node_modules") continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...findMjsFiles(full));
    else if (entry.name.endsWith(".mjs")) out.push(full);
  }
  return out;
}

section("1. node --check on every .mjs module");
for (const file of findMjsFiles(path.join(ROOT, "src")).concat(findMjsFiles(path.join(ROOT, "test")))) {
  try {
    execFileSync(process.execPath, ["--check", file], { stdio: "pipe" });
    console.log(`OK    ${path.relative(ROOT, file)}`);
  } catch (e) {
    failures++;
    console.error(`FAIL  ${path.relative(ROOT, file)}`);
    console.error(e.stderr ? e.stderr.toString() : e.message);
  }
}

section("2. manifest.json validation");
const manifestPath = path.join(ROOT, "manifest.json");
let manifest;
try {
  manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  console.log("OK    manifest.json is valid JSON");
} catch (e) {
  failures++;
  console.error("FAIL  manifest.json failed to parse:", e.message);
}

if (manifest) {
  const referenced = [];
  if (manifest.background && manifest.background.service_worker) referenced.push(manifest.background.service_worker);
  for (const cs of manifest.content_scripts || []) {
    for (const js of cs.js || []) referenced.push(js);
  }
  if (manifest.action && manifest.action.default_popup) referenced.push(manifest.action.default_popup);
  if (manifest.options_ui && manifest.options_ui.page) referenced.push(manifest.options_ui.page);
  for (const key of Object.keys((manifest.icons || {}))) referenced.push(manifest.icons[key]);

  for (const rel of referenced) {
    const full = path.join(ROOT, rel);
    if (existsSync(full)) {
      console.log(`OK    referenced file exists: ${rel}`);
    } else {
      failures++;
      console.error(`FAIL  manifest references missing file: ${rel}`);
    }
  }

  // Permission coverage check (heuristic): every API family used across src/ must have a
  // matching permission declared.
  const allSrc = findMjsFiles(path.join(ROOT, "src")).map((f) => readFileSync(f, "utf8")).join("\n");
  const perms = new Set(manifest.permissions || []);
  const requiredIfUsed = {
    "chrome.declarativeNetRequest": "declarativeNetRequest",
    "chrome.alarms": "alarms",
    "chrome.storage": "storage",
  };
  for (const [apiPrefix, perm] of Object.entries(requiredIfUsed)) {
    if (allSrc.includes(apiPrefix) && !perms.has(perm)) {
      failures++;
      console.error(`FAIL  ${apiPrefix} is used but "${perm}" permission is not declared`);
    } else if (allSrc.includes(apiPrefix)) {
      console.log(`OK    ${apiPrefix} usage covered by "${perm}" permission`);
    }
  }
  const hostPerms = manifest.host_permissions || [];
  const hasLocalhost = hostPerms.some((h) => h.includes("localhost")) && hostPerms.some((h) => h.includes("127.0.0.1"));
  if (hasLocalhost) console.log("OK    explicit localhost/127.0.0.1 host_permissions present");
  else {
    failures++;
    console.error("FAIL  missing explicit localhost/127.0.0.1 host_permissions");
  }
}

section("3. unit tests");
const unitDir = path.join(ROOT, "test", "unit");
for (const file of readdirSync(unitDir).filter((f) => f.endsWith(".test.mjs"))) {
  const full = path.join(unitDir, file);
  try {
    execFileSync(process.execPath, [full], { stdio: "inherit" });
    console.log(`OK    ${file}`);
  } catch (e) {
    failures++;
    console.error(`FAIL  ${file}`);
  }
}

console.log(`\n${failures === 0 ? "ALL CHECKS PASSED" : failures + " CHECK(S) FAILED"}`);
process.exit(failures === 0 ? 0 : 1);
