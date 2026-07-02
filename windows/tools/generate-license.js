#!/usr/bin/env node
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const LICENSE_VERSION = "BS1";
const privateKeyPath = path.join(__dirname, "license-private-key.pem");

function main() {
  const [machineCode, customer = "Customer", expiresAt = ""] = process.argv.slice(2);
  if (!machineCode) {
    console.log("Usage: node tools/generate-license.js <machine-code> [customer] [expires-at]");
    console.log("Example: node tools/generate-license.js ABCDEF-123456-789ABC-DEF123-456789 \"ACME\" 2027-12-31");
    process.exit(1);
  }
  if (!fs.existsSync(privateKeyPath)) {
    console.error(`Missing private key: ${privateKeyPath}`);
    process.exit(1);
  }

  const payload = {
    machineCode: machineCode.trim().toUpperCase(),
    customer,
    issuedAt: new Date().toISOString(),
    expiresAt: normalizeExpiry(expiresAt)
  };
  const payloadPart = Buffer.from(JSON.stringify(payload), "utf8").toString("base64url");
  const signer = crypto.createSign("RSA-SHA256");
  signer.update(payloadPart);
  signer.end();
  const signaturePart = signer.sign(fs.readFileSync(privateKeyPath, "utf8")).toString("base64url");

  console.log(`${LICENSE_VERSION}.${payloadPart}.${signaturePart}`);
}

function normalizeExpiry(value) {
  if (!value || value.toLowerCase() === "permanent" || value.toLowerCase() === "forever") return "";
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) {
    console.error("Invalid expires-at value. Use YYYY-MM-DD or permanent.");
    process.exit(1);
  }
  parsed.setHours(23, 59, 59, 999);
  return parsed.toISOString();
}

main();
