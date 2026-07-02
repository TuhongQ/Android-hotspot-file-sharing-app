const { app, BrowserWindow, clipboard, ipcMain } = require("electron");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const LICENSE_VERSION = "BS1";
let mainWindow;

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 560,
    height: 650,
    minWidth: 520,
    minHeight: 620,
    backgroundColor: "#070a12",
    title: "Bridge Share Keygen",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  mainWindow.loadFile(path.join(__dirname, "index.html"));
}

app.whenReady().then(createWindow);

app.on("window-all-closed", () => {
  if (process.platform !== "darwin") app.quit();
});

ipcMain.handle("generateLicense", async (_event, input) => generateLicense(input));

ipcMain.handle("copyText", async (_event, text) => {
  clipboard.writeText(String(text || ""));
  return true;
});

function generateLicense(input) {
  const machineCode = normalizeMachineCode(input.machineCode);
  if (!machineCode) return { ok: false, message: "Enter the customer's machine code" };

  const privateKey = readPrivateKey();
  if (!privateKey.ok) return privateKey;

  const payload = {
    machineCode,
    customer: String(input.customer || "Customer").trim() || "Customer",
    issuedAt: new Date().toISOString(),
    expiresAt: normalizeExpiry(input.expiresAt)
  };

  if (payload.expiresAt === null) return { ok: false, message: "Invalid expiration date. Use YYYY-MM-DD or choose a permanent license." };

  const payloadPart = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(payloadPart);
  signer.end();
  const signaturePart = signer.sign(privateKey.value).toString("base64url");

  return {
    ok: true,
    licenseKey: `${LICENSE_VERSION}.${payloadPart}.${signaturePart}`,
    payload
  };
}

function normalizeMachineCode(value) {
  return String(value || "").trim().replace(/\s+/g, "").toUpperCase();
}

function normalizeExpiry(value) {
  const clean = String(value || "").trim();
  if (!clean) return "";
  const parsed = new Date(`${clean}T23:59:59.999`);
  if (Number.isNaN(parsed.getTime())) return null;
  return parsed.toISOString();
}

function readPrivateKey() {
  const candidates = [
    path.join(process.resourcesPath || "", "app.asar.unpacked", "tools", "license-private-key.pem"),
    path.join(process.resourcesPath || "", "app", "tools", "license-private-key.pem"),
    path.join(__dirname, "..", "license-private-key.pem"),
    path.join(process.cwd(), "license-private-key.pem")
  ];

  for (const candidate of candidates) {
    try {
      if (candidate && fs.existsSync(candidate)) {
        return { ok: true, value: fs.readFileSync(candidate, "utf8") };
      }
    } catch {
      // Try the next candidate.
    }
  }

  return {
    ok: false,
    message: "Private key file license-private-key.pem was not found. A registration code cannot be generated."
  };
}
