// How the dashboard behaves. It's plain fetch and DOM, with no framework and
// nothing vendored. The page has one polled endpoint and four buttons, and a
// build step for that would cost more than it saves.

const POLL_MILLIS = 3000;

const el = (id) => document.getElementById(id);

function formatDuration(totalSeconds) {
  const seconds = Math.max(0, Math.floor(totalSeconds));
  const days = Math.floor(seconds / 86400);
  const hours = Math.floor((seconds % 86400) / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m ${seconds % 60}s`;
  return `${seconds}s`;
}

function formatAltitude(metres) {
  if (!isFinite(metres)) return "-";
  if (Math.abs(metres) >= 1000) return `${(metres / 1000).toFixed(1)} km`;
  return `${Math.round(metres)} m`;
}

function formatAgo(epochMillis) {
  if (!epochMillis) return "never";
  return `${formatDuration((Date.now() - epochMillis) / 1000)} ago`;
}

function setActionResult(text, good) {
  const node = el("action-result");
  node.textContent = text || "";
  node.className = good ? "hint ok" : "error";
}

async function post(path, body) {
  const response = await fetch(path, { method: "POST", body });
  const result = await response.json().catch(() => ({ ok: false, error: "No reply" }));
  setActionResult(result.ok ? "Done." : result.error || "Refused.", result.ok);
  await refresh();
  return result;
}

function renderPlayers(players) {
  const body = document.querySelector("#players tbody");
  body.innerHTML = "";
  el("nobody").hidden = players.length > 0;

  for (const player of players) {
    const row = document.createElement("tr");

    const name = document.createElement("td");
    name.textContent = player.name;

    const craft = document.createElement("td");
    craft.textContent = player.vessel || "-";

    const altitude = document.createElement("td");
    altitude.className = "numeric";
    altitude.textContent = formatAltitude(player.altitude);

    const action = document.createElement("td");
    action.className = "act";
    const kick = document.createElement("button");
    kick.textContent = "Kick";
    kick.className = "quiet";
    kick.onclick = () => {
      const form = new FormData();
      form.append("name", player.name);
      post("/api/kick", form);
    };
    action.appendChild(kick);

    row.append(name, craft, altitude, action);
    body.appendChild(row);
  }
}

async function refresh() {
  let state;
  try {
    const response = await fetch("/api/state");
    if (response.status === 401) {
      window.location = "/login";
      return;
    }
    state = await response.json();
  } catch (error) {
    // The admin container itself can't be reached, so keep the last view and
    // say so instead of blanking the page.
    el("offline-reason").textContent = "The admin page lost contact with itself.";
    el("offline").hidden = false;
    return;
  }

  if (!state.running) {
    el("offline-reason").textContent = state.reason || "Not running.";
    el("offline").hidden = false;
    el("online").hidden = true;
    return;
  }

  el("offline").hidden = true;
  el("online").hidden = false;

  const status = state.status;
  el("server-name").textContent = status.serverName || "Server";
  el("s-players").textContent = status.players;
  el("s-vessels").textContent = status.vessels;
  el("s-time").textContent = formatDuration(status.universeTime);
  el("s-uptime").textContent = formatDuration(status.uptimeSeconds);
  el("s-save").textContent = formatAgo(status.lastSaveEpochMillis);
  el("s-port").textContent = status.port;
  el("s-catalog").textContent = status.catalogHash;

  renderPlayers(state.players || []);

  // Only scroll the log if the operator was already at the bottom. Yanking it
  // down while they're reading something further up is maddening.
  const log = el("log");
  const wasAtBottom = log.scrollHeight - log.scrollTop - log.clientHeight < 40;
  log.textContent = (state.log || []).join("\n");
  if (wasAtBottom) log.scrollTop = log.scrollHeight;
}

el("save").onclick = () => post("/api/save");

el("stop").onclick = () => {
  if (!confirm("Stop the server? It saves the world on the way down.")) return;
  post("/api/stop");
};

el("announce-form").onsubmit = async (event) => {
  event.preventDefault();
  const input = el("announce");
  if (!input.value.trim()) return;
  const form = new FormData();
  form.append("message", input.value);
  const result = await post("/api/announce", form);
  if (result.ok) input.value = "";
};

refresh();
setInterval(refresh, POLL_MILLIS);
