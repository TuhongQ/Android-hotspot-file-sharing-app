const { app, BrowserWindow, clipboard, dialog, ipcMain, shell, screen } = require("electron");
const path = require("path");
const fs = require("fs");
const os = require("os");
const QRCode = require("qrcode");
const { FileHubServer } = require("./server");
const { LicenseManager } = require("./license");

let mainWindow;
let server;
let folders = [];
let licenseManager;
const port = 8088;

function createWindow() {
  const { width: screenWidth, height: screenHeight } = screen.getPrimaryDisplay().workAreaSize;
  const windowWidth = Math.min(Math.max(Math.round(screenWidth * 0.82), 980), 1240);
  const windowHeight = Math.min(Math.max(Math.round(screenHeight * 0.78), 620), 760);

  mainWindow = new BrowserWindow({
    width: windowWidth,
    height: windowHeight,
    minWidth: 820,
    minHeight: 560,
    backgroundColor: "#070a12",
    title: "Bridge Share",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));
}

app.whenReady().then(() => {
  licenseManager = new LicenseManager(app.getPath("userData"));
  folders = loadFolders();
  createWindow();
});

app.on("window-all-closed", () => {
  if (server) server.stop();
  if (process.platform !== "darwin") app.quit();
});

ipcMain.handle("state", async () => buildState());

ipcMain.handle("chooseFolders", async () => {
  if (!requireLicense()) return buildState();
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Choose shared folders",
    properties: ["openDirectory", "multiSelections"]
  });
  if (!result.canceled) {
    for (const folder of result.filePaths) {
      if (!folders.includes(folder)) folders.push(folder);
    }
    saveFolders(folders);
    if (server) restartServer();
  }
  return buildState();
});

ipcMain.handle("removeFolder", async (_event, folder) => {
  if (!requireLicense()) return buildState();
  folders = folders.filter((item) => item !== folder);
  saveFolders(folders);
  if (server) restartServer();
  return buildState();
});

ipcMain.handle("clearFolders", async () => {
  if (!requireLicense()) return buildState();
  folders = [];
  saveFolders(folders);
  if (server) stopServer();
  return buildState();
});

ipcMain.handle("startServer", async () => {
  if (!requireLicense()) return buildState();
  startServer();
  return buildState();
});

ipcMain.handle("stopServer", async () => {
  stopServer();
  return buildState();
});

ipcMain.handle("openExternal", async (_event, url) => {
  await shell.openExternal(url);
});

ipcMain.handle("clients", async () => FileHubServer.connectedClients());

ipcMain.handle("chooseSendFiles", async () => {
  if (!requireLicense()) return [];
  const result = await dialog.showOpenDialog(mainWindow, {
    title: "Choose files to send",
    properties: ["openFile", "multiSelections"]
  });
  return result.canceled ? [] : result.filePaths;
});

ipcMain.handle("pushFile", async (_event, clientId, filePath) => {
  if (!requireLicense()) return null;
  return FileHubServer.pushFileToClient(clientId, filePath);
});

ipcMain.handle("offerProgress", async (_event, offerId) => FileHubServer.offerProgress(offerId));

ipcMain.handle("licenseState", async () => licenseManager.state());

ipcMain.handle("activateLicense", async (_event, licenseKey) => licenseManager.activate(licenseKey));

ipcMain.handle("copyMachineCode", async () => {
  clipboard.writeText(licenseManager.state().machineCode);
  return true;
});

function startServer() {
  if (server || folders.length === 0 || !requireLicense()) return;
  server = new FileHubServer({ folders, port });
  server.start();
}

function stopServer() {
  if (!server) return;
  server.stop();
  server = null;
}

function restartServer() {
  stopServer();
  startServer();
}

async function buildState() {
  const urls = lanUrls().map((ip) => `http://${ip}:${port}/`);
  const qr = urls[0] ? await QRCode.toDataURL(urls[0], { margin: 1, width: 280 }) : "";
  return {
    running: Boolean(server),
    license: licenseManager.state(),
    folders: folders.map((folder) => ({ path: folder, name: path.basename(folder) || folder })),
    urls,
    qr
  };
}

function requireLicense() {
  if (licenseManager?.isValid()) return true;
  if (server) stopServer();
  return false;
}

function lanUrls() {
  const ips = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) ips.push(entry.address);
    }
  }
  return ips;
}

function configPath() {
  return path.join(app.getPath("userData"), "folders.json");
}

function loadFolders() {
  try {
    const parsed = JSON.parse(fs.readFileSync(configPath(), "utf8"));
    return Array.isArray(parsed) ? parsed.filter((folder) => fs.existsSync(folder)) : [];
  } catch {
    return [];
  }
}

function saveFolders(nextFolders) {
  fs.mkdirSync(path.dirname(configPath()), { recursive: true });
  fs.writeFileSync(configPath(), JSON.stringify(nextFolders, null, 2));
}
