// requestr Browser Bridge -- popup script (ES module; MV3 popup HTML can load
// type="module" scripts directly, no bundler needed).

import { getConfig, setApiBase, setEnforcementEnabled } from "../sdk/config.mjs";
import { getCachedRules } from "../sdk/channel.mjs";

const apiBaseInput = document.getElementById("apiBase");
const saveBtn = document.getElementById("saveBtn");
const refreshBtn = document.getElementById("refreshBtn");
const statusDot = document.getElementById("statusDot");
const statusText = document.getElementById("statusText");
const pairText = document.getElementById("pairText");
const observedCountEl = document.getElementById("observedCount");
const bodiesCountEl = document.getElementById("bodiesCount");
const queuedCountEl = document.getElementById("queuedCount");
const deadCountEl = document.getElementById("deadCount");
const errorBox = document.getElementById("errorBox");
const enforcementToggle = document.getElementById("enforcementToggle");
const rulesHeader = document.getElementById("rulesHeader");
const rulesList = document.getElementById("rulesList");

function getStatus() {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage({ type: "get-status" }, (res) => {
      if (chrome.runtime.lastError) resolve(null);
      else resolve(res);
    });
  });
}

function forceSync() {
  return new Promise((resolve) => {
    chrome.runtime.sendMessage({ type: "force-sync" }, () => resolve());
  });
}

function render(status) {
  if (!status) return;
  apiBaseInput.value = status.apiBase || "";
  enforcementToggle.checked = status.enforcementEnabled !== false;
  observedCountEl.textContent = String(
    (status.observer && (status.observer.webRequestCount + status.observer.interceptorCount)) || 0
  );
  bodiesCountEl.textContent = String((status.observer && status.observer.bodyCapturedCount) || 0);
  queuedCountEl.textContent = String((status.queue && status.queue.queued) || 0);
  deadCountEl.textContent = String((status.queue && status.queue.dead) || 0);

  const conn = status.connection || {};
  const paired = !!conn.paired;
  const connected = conn.lastHealthOk === true;
  const unreachable = conn.lastHealthOk === false;

  if (!paired) {
    statusDot.className = "dot fail";
    statusText.textContent = "Not paired";
    pairText.textContent = "Not paired — paste token in Options";
  } else if (connected) {
    statusDot.className = "dot ok";
    statusText.textContent = "Connected";
    pairText.textContent = "Paired to requestr";
  } else if (unreachable) {
    statusDot.className = "dot amber";
    statusText.textContent = "Server unreachable";
    pairText.textContent = "Paired, server unreachable";
  } else {
    statusDot.className = "dot unknown";
    statusText.textContent = "Checking...";
    pairText.textContent = "Checking...";
  }

  if (status.queue && status.queue.lastError) {
    errorBox.style.display = "block";
    errorBox.textContent = "Last relay error: " + status.queue.lastError;
  } else {
    errorBox.style.display = "none";
  }
}

function renderRules(rules) {
  rulesHeader.textContent = `${rules.length} rule${rules.length === 1 ? "" : "s"} active`;
  rulesList.innerHTML = "";
  for (const rule of rules) {
    const item = document.createElement("div");
    item.className = "ruleItem";
    const name = document.createElement("span");
    name.className = "ruleName";
    name.textContent = rule.name || `Rule #${rule.id}`;
    const badge = document.createElement("span");
    badge.className = `badge ${rule.action}${rule.enabled === false ? " disabled" : ""}`;
    badge.textContent = rule.action;
    name.appendChild(badge);
    const pattern = document.createElement("div");
    pattern.className = "rulePattern";
    pattern.textContent = rule.pattern || rule.domain || "(no pattern)";
    const replacement = document.createElement("div");
    replacement.className = "ruleReplacement";
    replacement.textContent = rule.replacementValue || "(no replacementValue)";
    item.appendChild(name);
    item.appendChild(pattern);
    item.appendChild(replacement);
    item.addEventListener("click", () => item.classList.toggle("expanded"));
    rulesList.appendChild(item);
  }
}

async function refresh() {
  const status = await getStatus();
  render(status);
  const rules = await getCachedRules();
  renderRules(rules);
}

// tabs
document.querySelectorAll(".tab").forEach((tabBtn) => {
  tabBtn.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((b) => b.classList.remove("active"));
    document.querySelectorAll(".panel").forEach((p) => p.classList.remove("active"));
    tabBtn.classList.add("active");
    document.getElementById(`tab-${tabBtn.dataset.tab}`).classList.add("active");
  });
});

getConfig().then((config) => {
  apiBaseInput.value = config.apiBase;
  enforcementToggle.checked = config.enforcementEnabled;
});

saveBtn.addEventListener("click", async () => {
  const value = apiBaseInput.value.trim() || "http://localhost:9876";
  await setApiBase(value);
  saveBtn.textContent = "Saved";
  setTimeout(() => (saveBtn.textContent = "Save"), 1000);
  await forceSync();
  refresh();
});

enforcementToggle.addEventListener("change", async () => {
  await setEnforcementEnabled(enforcementToggle.checked);
});

refreshBtn.addEventListener("click", async () => {
  refreshBtn.textContent = "Refreshing...";
  await forceSync();
  refreshBtn.textContent = "Refresh Rules Now";
  refresh();
});

refresh();
setInterval(refresh, 2000);
