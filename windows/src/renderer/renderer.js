let state = null;
let licenseState = null;

const licenseGate = document.getElementById("licenseGate");
const appShell = document.getElementById("appShell");
const machineCodeEl = document.getElementById("machineCode");
const licenseInput = document.getElementById("licenseInput");
const activateLicense = document.getElementById("activateLicense");
const copyMachineCode = document.getElementById("copyMachineCode");
const licenseMessage = document.getElementById("licenseMessage");
const licenseSummary = document.getElementById("licenseSummary");
const foldersEl = document.getElementById("folders");
const statusEl = document.getElementById("status");
const urlsEl = document.getElementById("urls");
const qrEl = document.getElementById("qr");
const toggleServer = document.getElementById("toggleServer");
const quickStart = document.getElementById("quickStart");
const clientsEl = document.getElementById("clients");
const dropZone = document.getElementById("dropZone");
const chooseSendFiles = document.getElementById("chooseSendFiles");
const sendProgress = document.getElementById("sendProgress");
let clients = [];

activateLicense.addEventListener("click", activate);
copyMachineCode.addEventListener("click", async () => {
  await window.bridgeShare.copyMachineCode();
  licenseMessage.textContent = "Machine code copied.";
  licenseMessage.classList.add("ok");
});
window.bridgeShare.onShowLicenseGate(async ({ license, message } = {}) => {
  licenseInput.value = "";
  renderLicense(license || await window.bridgeShare.licenseState(), { forceGate: true, message });
  render(await window.bridgeShare.state());
});
document.getElementById("addFolders").addEventListener("click", async () => render(await window.bridgeShare.chooseFolders()));
document.getElementById("clearFolders").addEventListener("click", async () => render(await window.bridgeShare.clearFolders()));
toggleServer.addEventListener("click", toggle);
quickStart.addEventListener("click", async () => {
  if (!state.folders.length) render(await window.bridgeShare.chooseFolders());
  if (state.folders.length && !state.running) render(await window.bridgeShare.startServer());
});
chooseSendFiles.addEventListener("click", async () => {
  const files = await window.bridgeShare.chooseSendFiles();
  if (files.length) sendFiles(files);
});
dropZone.addEventListener("dragover", (event) => {
  event.preventDefault();
  dropZone.classList.add("drag");
});
dropZone.addEventListener("dragleave", () => dropZone.classList.remove("drag"));
dropZone.addEventListener("drop", (event) => {
  event.preventDefault();
  dropZone.classList.remove("drag");
  const files = Array.from(event.dataTransfer.files || []).map((file) => file.path).filter(Boolean);
  if (files.length) sendFiles(files);
});

init();
setInterval(refreshClients, 2000);

async function init() {
  licenseState = await window.bridgeShare.licenseState();
  renderLicense(licenseState);
  if (!licenseState.valid) return;
  render(await window.bridgeShare.state());
  refreshClients();
}

async function activate() {
  licenseMessage.textContent = "Checking registration code...";
  licenseMessage.classList.remove("ok");
  const result = await window.bridgeShare.activateLicense(licenseInput.value);
  licenseState = result;
  renderLicense(result);
  if (result.valid) {
    render(await window.bridgeShare.state());
    refreshClients();
  }
}

function renderLicense(nextLicense, options = {}) {
  licenseState = nextLicense;
  machineCodeEl.textContent = licenseState.machineCode;
  if (licenseState.valid && !options.forceGate) {
    licenseGate.classList.add("hidden");
    appShell.classList.remove("app-hidden");
    const expiry = licenseState.expiresAt ? `Expires ${new Date(licenseState.expiresAt).toLocaleDateString()}` : "Permanent license";
    licenseSummary.textContent = `${licenseState.customer || "Licensed user"} · ${expiry}`;
    licenseMessage.textContent = "Activated successfully.";
    licenseMessage.classList.add("ok");
    return;
  }
  licenseGate.classList.remove("hidden");
  appShell.classList.add("app-hidden");
  licenseMessage.textContent = options.message || licenseState.reason || "";
  licenseMessage.classList.remove("ok");
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

async function refreshClients() {
  if (!licenseState?.valid) return;
  clients = await window.bridgeShare.clients();
  clientsEl.innerHTML = "";
  if (!clients.length) {
    clientsEl.innerHTML = `<p class="muted">No phone connected yet. Open the QR page on the phone first.</p>`;
    return;
  }
  for (const client of clients) {
    const pill = document.createElement("span");
    pill.className = "client";
    pill.textContent = client.name;
    clientsEl.appendChild(pill);
  }
}

async function sendFiles(files) {
  await refreshClients();
  if (!clients.length) {
    alert("No phone connected. Open the QR page on the phone first.");
    return;
  }
  const client = clients.length === 1 ? clients[0] : chooseClient();
  if (!client) return;
  for (const file of files) {
    const row = createProgressRow(file, client.name);
    sendProgress.prepend(row.element);
    const offerId = await window.bridgeShare.pushFile(client.id, file);
    if (!offerId) {
      row.status.textContent = "Failed to notify phone";
      continue;
    }
    row.status.textContent = "Waiting for phone to receive";
    pollOffer(offerId, row);
  }
}

function chooseClient() {
  const list = clients.map((client, index) => `${index + 1}. ${client.name}`).join("\n");
  const answer = prompt(`Choose target phone:\n${list}`, "1");
  const index = Number(answer) - 1;
  return clients[index] || null;
}

function createProgressRow(file, clientName) {
  const element = document.createElement("div");
  element.className = "progress-row";
  element.innerHTML = `<div><strong>${escapeHtml(file.split(/[\\/]/).pop())}</strong><span>to ${escapeHtml(clientName)}</span></div><div class="bar"><div class="fill"></div></div><small>Preparing</small>`;
  return {
    element,
    fill: element.querySelector(".fill"),
    status: element.querySelector("small")
  };
}

async function pollOffer(offerId, row) {
  const timer = setInterval(async () => {
    const progress = await window.bridgeShare.offerProgress(offerId);
    if (!progress.started) {
      row.status.textContent = "Waiting for phone to receive";
      return;
    }
    const percent = progress.total > 0 ? Math.min(100, Math.round(progress.sent * 100 / progress.total)) : (progress.completed ? 100 : 50);
    row.fill.style.width = `${percent}%`;
    row.status.textContent = progress.completed ? "Completed" : `Sending ${percent}%`;
    if (progress.completed) clearInterval(timer);
  }, 350);
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
