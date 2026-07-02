const crypto = require("crypto");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { execFileSync } = require("child_process");

const LICENSE_VERSION = "BS1";
const PUBLIC_KEY = `-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAzF0kVcRC4TowVjCpA1Qp
k2Vj1C44NOpVCQVBPCYTdEyxFE0kLNQ4fD9AHxcrPaFa040qnPeRedlB2f/Pjuwd
OVtAGxBvJKmo2sBG/onAD7uI3qbh7kvxq58d78WZB0hdTYZL/Ucne6eTXtwMnwKX
ETIdDLeO0fixOorybBrrF4V935HIBQTXKJRFcidkfpgAxtqI8sCAe0QnXhT5vAVi
StmACV9cCHQUJ9oo90Hgu5tXMrVoH6PRtnj2NVIULHRWw503ttngLYSXh374GxNF
m5B4bYJGCo04RtXhASjs1GlkbGmOLtkVEG6WWAxsd6KamnO56OJqENjRMHhGnAZ1
jwIDAQAB
-----END PUBLIC KEY-----`;

class LicenseManager {
  constructor(userDataPath) {
    this.licensePath = path.join(userDataPath, "license.json");
    this.machineCode = createMachineCode();
  }

  state() {
    const savedKey = this.readSavedKey();
    const result = savedKey ? verifyLicenseKey(savedKey, this.machineCode) : { valid: false, reason: "Not activated" };
    return {
      machineCode: this.machineCode,
      valid: result.valid,
      reason: result.reason || "",
      customer: result.payload?.customer || "",
      expiresAt: result.payload?.expiresAt || "",
      activatedAt: result.payload?.issuedAt || "",
      licenseKey: savedKey || ""
    };
  }

  activate(licenseKey) {
    const cleanKey = normalizeLicenseKey(licenseKey);
    const result = verifyLicenseKey(cleanKey, this.machineCode);
    if (!result.valid) {
      return this.stateWithResult(result);
    }
    fs.mkdirSync(path.dirname(this.licensePath), { recursive: true });
    fs.writeFileSync(this.licensePath, JSON.stringify({ licenseKey: cleanKey }, null, 2));
    return this.state();
  }

  clear() {
    try {
      fs.rmSync(this.licensePath, { force: true });
    } catch {
      // Missing or locked license files are treated as not activated.
    }
    return this.state();
  }

  isValid() {
    return this.state().valid;
  }

  readSavedKey() {
    try {
      const parsed = JSON.parse(fs.readFileSync(this.licensePath, "utf8"));
      return typeof parsed.licenseKey === "string" ? parsed.licenseKey : "";
    } catch {
      return "";
    }
  }

  stateWithResult(result) {
    return {
      machineCode: this.machineCode,
      valid: false,
      reason: result.reason || "Invalid license",
      customer: "",
      expiresAt: "",
      activatedAt: "",
      licenseKey: ""
    };
  }
}

function verifyLicenseKey(licenseKey, machineCode) {
  try {
    const cleanKey = normalizeLicenseKey(licenseKey);
    const parts = cleanKey.split(".");
    if (parts.length !== 3 || parts[0] !== LICENSE_VERSION) {
      return { valid: false, reason: "注册码格式不正确" };
    }

    const payloadText = Buffer.from(parts[1], "base64url").toString("utf8");
    const payload = JSON.parse(payloadText);
    const signature = Buffer.from(parts[2], "base64url");
    const verifier = crypto.createVerify("RSA-SHA256");
    verifier.update(parts[1]);
    verifier.end();

    if (!verifier.verify(PUBLIC_KEY, signature)) {
      return { valid: false, reason: "注册码签名无效" };
    }
    if (payload.machineCode !== machineCode) {
      return { valid: false, reason: "注册码不属于这台电脑" };
    }
    if (payload.expiresAt && new Date(payload.expiresAt).getTime() < Date.now()) {
      return { valid: false, reason: "注册码已过期" };
    }
    return { valid: true, payload };
  } catch {
    return { valid: false, reason: "注册码无法解析" };
  }
}

function createMachineCode() {
  const values = [
    "bridge-share-windows-v1",
    os.hostname(),
    os.platform(),
    os.arch(),
    ...windowsHardwareIds()
  ].filter(Boolean);
  const digest = crypto.createHash("sha256").update(values.join("|")).digest("hex").toUpperCase();
  return digest.match(/.{1,6}/g).slice(0, 5).join("-");
}

function windowsHardwareIds() {
  if (process.platform !== "win32") return [];
  const commands = [
    ["Get-CimInstance Win32_ComputerSystemProduct | Select-Object -ExpandProperty UUID"],
    ["Get-CimInstance Win32_BIOS | Select-Object -ExpandProperty SerialNumber"],
    ["Get-CimInstance Win32_BaseBoard | Select-Object -ExpandProperty SerialNumber"],
    ["Get-CimInstance Win32_Processor | Select-Object -ExpandProperty ProcessorId"]
  ];
  return commands.map(([command]) => runPowerShell(command)).filter(Boolean);
}

function runPowerShell(command) {
  try {
    return execFileSync("powershell.exe", ["-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command], {
      encoding: "utf8",
      timeout: 5000,
      windowsHide: true
    }).trim();
  } catch {
    return "";
  }
}

function normalizeLicenseKey(value) {
  return String(value || "").trim().replace(/\s+/g, "");
}

module.exports = {
  LICENSE_VERSION,
  LicenseManager,
  normalizeLicenseKey
};
