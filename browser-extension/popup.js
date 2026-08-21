// requestr Browser Bridge -- popup script.
// Separate file (not inline in popup.html) because MV3's default extension CSP
// disallows inline <script> blocks.

const apiBaseInput = document.getElementById("apiBase");
const saveBtn = document.getElementById("saveBtn");
const statusDot = document.getElementById("statusDot");
const statusText = document.getElementById("statusText");
const relayedCountEl = document.getElementById("relayedCount");
const queuedCountEl = document.getElementById("queuedCount");
const errorBox = document.getElementById("errorBox");

function render(status) {
  if (!status) return;
  apiBaseInput.value = status.apiBase || "";
  relayedCountEl.textContent = String(status.relayedCount || 0);
  queuedCountEl.textContent = String(status.queued || 0);

  statusDot.className = "dot " + (status.lastPingOk === true ? "ok" : status.lastPingOk === false ? "fail" : "unknown");
  statusText.textContent = status.lastPingOk === true ? "Connected" : status.lastPingOk === false ? "Unreachable" : "Checking...";

  if (status.lastError) {
    errorBox.style.display = "block";
    errorBox.textContent = "Last relay error: " + status.lastError;
  } else {
    errorBox.style.display = "none";
  }
}

function refresh() {
  chrome.runtime.sendMessage({ type: "get-status" }, (res) => {
    if (chrome.runtime.lastError) return;
    render(res);
  });
}

chrome.storage.local.get(["apiBase"], (res) => {
  if (res && res.apiBase) apiBaseInput.value = res.apiBase;
});

saveBtn.addEventListener("click", () => {
  const value = apiBaseInput.value.trim() || "http://localhost:9876";
  chrome.storage.local.set({ apiBase: value }, () => {
    saveBtn.textContent = "Saved";
    setTimeout(() => (saveBtn.textContent = "Save"), 1000);
    refresh();
  });
});

refresh();
setInterval(refresh, 2000);
