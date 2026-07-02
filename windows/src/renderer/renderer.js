let state = null;

const foldersEl = document.getElementById("folders");
const statusEl = document.getElementById("status");
const urlsEl = document.getElementById("urls");
const qrEl = document.getElementById("qr");
const toggleServer = document.getElementById("toggleServer");
const quickStart = document.getElementById("quickStart");

document.getElementById("addFolders").addEventListener("click", async () => render(await window.bridgeShare.chooseFolders()));
document.getElementById("clearFolders").addEventListener("click", async () => render(await window.bridgeShare.clearFolders()));
toggleServer.addEventListener("click", toggle);
quickStart.addEventListener("click", async () => {
  if (!state.folders.length) render(await window.bridgeShare.chooseFolders());
  if (state.folders.length && !state.running) render(await window.bridgeShare.startServer());
});

init();

async function init() {
  render(await window.bridgeShare.state());
}

async function toggle() {
  render(state.running ? await window.bridgeShare.stopServer() : await window.bridgeShare.startServer());
}

function render(nextState) {
  state = nextState;
  statusEl.textContent = state.running ? "Service running. Phones can scan or open the address below." : "Service stopped";
  toggleServer.textContent = state.running ? "Stop service" : "Start service";
  quickStart.textContent = state.running ? "Sharing is active" : "Start sharing";

  foldersEl.innerHTML = "";
  if (!state.folders.length) {
    foldersEl.innerHTML = `<p class="muted">No shared folders yet.</p>`;
  }
  for (const folder of state.folders) {
    const row = document.createElement("div");
    row.className = "folder";
    row.innerHTML = `<div><strong>${escapeHtml(folder.name)}</strong><small>${escapeHtml(folder.path)}</small></div>`;
    const remove = document.createElement("button");
    remove.className = "ghost";
    remove.textContent = "Remove";
    remove.addEventListener("click", async () => render(await window.bridgeShare.removeFolder(folder.path)));
    row.appendChild(remove);
    foldersEl.appendChild(row);
  }

  urlsEl.innerHTML = "";
  for (const url of state.urls) {
    const link = document.createElement("a");
    link.href = url;
    link.textContent = url;
    link.addEventListener("click", (event) => {
      event.preventDefault();
      window.bridgeShare.openExternal(url);
    });
    urlsEl.appendChild(link);
  }
  qrEl.style.display = state.running && state.qr ? "block" : "none";
  qrEl.src = state.qr || "";
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[char]);
}
