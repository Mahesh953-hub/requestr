// requestr Browser Bridge -- options page script.

import { SDK_VERSION, SDK_BUILD } from "../sdk/version.mjs";
import {
  getConfig,
  setApiBase,
  setToken,
  setMaxBodyBytes,
  setFlushIntervalMs,
  setEnforcementEnabled,
} from "../sdk/config.mjs";
import { unpair, fetchPairingInfo } from "../sdk/pairing.mjs";
import { getCachedRules, healthCheck, getConnectionStatus } from "../sdk/channel.mjs";

const apiBaseInput = document.getElementById("apiBase");
const tokenInput = document.getElementById("tokenInput");
const saveConnBtn = document.getElementById("saveConnBtn");
const autoPairBtn = document.getElementById("autoPairBtn");
const unpairBtn = document.getElementById("unpairBtn");
const pairStatus = document.getElementById("pairStatus");
const healthValue = document.getElementById("healthValue");

const ruleCounts = document.getElementById("ruleCounts");
const rulesTableBody = document.getElementById("rulesTableBody");

const logFilter = document.getElementById("logFilter");
const clearLogBtn = document.getElementById("clearLogBtn");
const logTableBody = document.getElementById("logTableBody");
const bodyPreview = document.getElementById("bodyPreview");

const maxBodySlider = document.getElementById("maxBodySlider");
const maxBodyValue = document.getElementById("maxBodyValue");
const flushIntervalSelect = document.getElementById("flushIntervalSelect");
const enforcementDefaultToggle = document.getElementById("enforcementDefaultToggle");

document.getElementById("sdkVersion").textContent = SDK_VERSION;
document.getElementById("sdkBuild").textContent = SDK_BUILD;

async function loadConnection() {
  const config = await getConfig();
  apiBaseInput.value = config.apiBase;
  tokenInput.value = config.token ? "•".repeat(Math.min(config.token.length, 24)) : "";
  maxBodySlider.value = Math.round(config.maxBodyBytes / 1024);
  maxBodyValue.textContent = maxBodySlider.value;
  const intervalOptions = Array.from(flushIntervalSelect.options).map((o) => o.value);
  flushIntervalSelect.value = intervalOptions.includes(String(config.flushIntervalMs))
    ? String(config.flushIntervalMs)
    : "2000";
  enforcementDefaultToggle.checked = config.enforcementEnabled;

  const conn = await getConnectionStatus();
  pairStatus.textContent = conn.paired ? "A token is configured." : "No token configured yet.";
}

async function refreshHealth() {
  await healthCheck();
  const conn = await getConnectionStatus();
  healthValue.textContent = conn.lastHealthOk === true
    ? `OK (last checked ${new Date(conn.lastHealthAt).toLocaleTimeString()})`
    : conn.lastHealthOk === false
    ? `Unreachable (last checked ${new Date(conn.lastHealthAt).toLocaleTimeString()})`
    : "checking...";
}

saveConnBtn.addEventListener("click", async () => {
  await setApiBase(apiBaseInput.value.trim() || "http://localhost:9876");
  const tokenValue = tokenInput.value.trim();
  // Only overwrite the stored token if the field doesn't just contain the masked bullets
  // we rendered on load -- otherwise saving without touching the field would wipe it.
  if (tokenValue && !/^•+$/.test(tokenValue)) {
    await setToken(tokenValue);
  }
  saveConnBtn.textContent = "Saved";
  setTimeout(() => (saveConnBtn.textContent = "Save"), 1000);
  await loadConnection();
  refreshHealth();
});

autoPairBtn.addEventListener("click", async () => {
  autoPairBtn.textContent = "Pairing...";
  const config = await getConfig();
  const result = await fetchPairingInfo(config.apiBase);
  if (result.ok) {
    pairStatus.textContent = "Paired automatically.";
    await loadConnection();
  } else if (result.reason === "hidden") {
    pairStatus.textContent = 'Enable "Show Pairing Token" in requestr Settings first, then retry.';
  } else if (result.reason === "unreachable") {
    pairStatus.textContent = "Could not reach the requestr app at the configured address.";
  } else {
    pairStatus.textContent = "Server response did not include a usable token.";
  }
  autoPairBtn.textContent = "Pair automatically";
});

unpairBtn.addEventListener("click", async () => {
  await unpair();
  await loadConnection();
  pairStatus.textContent = "Token cleared.";
});

maxBodySlider.addEventListener("input", () => {
  maxBodyValue.textContent = maxBodySlider.value;
});
maxBodySlider.addEventListener("change", async () => {
  await setMaxBodyBytes(Number(maxBodySlider.value) * 1024);
});
flushIntervalSelect.addEventListener("change", async () => {
  await setFlushIntervalMs(Number(flushIntervalSelect.value));
});
enforcementDefaultToggle.addEventListener("change", async () => {
  await setEnforcementEnabled(enforcementDefaultToggle.checked);
});

async function loadRules() {
  const rules = await getCachedRules();
  const enabledCount = rules.filter((r) => r.enabled !== false).length;
  ruleCounts.textContent = `${rules.length} total, ${enabledCount} enabled`;
  rulesTableBody.innerHTML = "";
  for (const rule of rules) {
    const tr = document.createElement("tr");
    tr.innerHTML = `
      <td>${rule.id}</td>
      <td>${escapeHtml(rule.name || "")}</td>
      <td>${escapeHtml(rule.action || "")}</td>
      <td class="truncate">${escapeHtml(rule.pattern || "")}</td>
      <td class="truncate">${escapeHtml(rule.domain || "")}</td>
      <td>${rule.enabled === false ? "no" : "yes"}</td>
      <td class="truncate">${escapeHtml(rule.replacementValue || "")}</td>
    `;
    rulesTableBody.appendChild(tr);
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

let allEvents = [];

async function loadLog() {
  const res = await chrome.storage.session.get(["recentEvents"]);
  allEvents = Array.isArray(res.recentEvents) ? res.recentEvents.slice().reverse() : [];
  renderLog();
}

function renderLog() {
  const filterText = logFilter.value.trim().toLowerCase();
  const filtered = filterText ? allEvents.filter((e) => (e.url || "").toLowerCase().includes(filterText)) : allEvents;
  logTableBody.innerHTML = "";
  filtered.slice(0, 200).forEach((event, idx) => {
    const tr = document.createElement("tr");
    tr.className = "clickable";
    const reqSize = event.requestBody ? event.requestBody.length : 0;
    const respSize = event.responseBody ? event.responseBody.length : 0;
    tr.innerHTML = `
      <td>${escapeHtml(event.source || "")}</td>
      <td>${escapeHtml(event.method || "")}</td>
      <td class="truncate">${escapeHtml(event.url || "")}</td>
      <td>${event.statusCode === null || event.statusCode === undefined ? "-" : event.statusCode}</td>
      <td>${reqSize ? reqSize + "b" : "-"}</td>
      <td>${respSize ? respSize + "b" : "-"}</td>
      <td>${event.ruleAction && event.ruleAction !== "LOG_ONLY" ? escapeHtml(event.ruleAction) : "-"}</td>
    `;
    tr.addEventListener("click", () => {
      bodyPreview.style.display = "block";
      bodyPreview.textContent =
        `URL: ${event.url}\n\nRequest body (${event.requestBodyEncoding || "none"}):\n${event.requestBody || "(none)"}\n\n` +
        `Response body (${event.responseBodyEncoding || "none"}):\n${event.responseBody || "(none)"}`;
    });
    logTableBody.appendChild(tr);
  });
}

logFilter.addEventListener("input", renderLog);
clearLogBtn.addEventListener("click", async () => {
  await chrome.storage.session.set({ recentEvents: [] });
  loadLog();
});

loadConnection();
refreshHealth();
loadRules();
loadLog();

setInterval(() => {
  loadRules();
  loadLog();
  refreshHealth();
}, 3000);
