const { app, BrowserWindow, dialog, ipcMain, shell } = require("electron");
const path = require("path");
const fs = require("fs");
const os = require("os");
const QRCode = require("qrcode");
const { FileHubServer } = require("./server");

let mainWindow;
let server;
let folders = [];
const port = 8088;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1040,
    height: 760,
    minWidth: 880,
    minHeight: 640,
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
  folders = loadFolders();
  createWindow();
});

app.on("window-all-closed", () => {
  if (server) server.stop();
  if (process.platform !== "darwin") app.quit();
});

ipcMain.handle("state", async () => buildState());

ipcMain.handle("chooseFolders", async () => {
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
  folders = folders.filter((item) => item !== folder);
  saveFolders(folders);
  if (server) restartServer();
  return buildState();
});

ipcMain.handle("clearFolders", async () => {
  folders = [];
  saveFolders(folders);
  if (server) stopServer();
  return buildState();
});

ipcMain.handle("startServer", async () => {
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

function startServer() {
  if (server || folders.length === 0) return;
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
    folders: folders.map((folder) => ({ path: folder, name: path.basename(folder) || folder })),
    urls,
    qr
  };
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
