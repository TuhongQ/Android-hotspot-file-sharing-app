# Bridge Share for Windows

Windows desktop version of Bridge Share. It turns a Windows PC into a LAN file hub. Phones on the same Wi-Fi can scan the QR code or open the LAN URL to browse shared folders, upload files, download files, preview images, and delete files.

## Run in Development

```bash
cd windows
npm install
npm start
```

## Usage

1. Open the Windows app.
2. If this is the first launch, copy the shown machine code and send it to the software provider.
3. Paste the registration code and click `Activate`.
4. Add one or more shared folders.
5. Click `Start service`.
6. Make sure the phone and Windows PC are on the same LAN.
7. Scan the QR code or open the shown LAN URL on the phone.

## Offline License Codes

Bridge Share uses offline one-machine-one-code activation:

- The app shows a hashed machine code on the customer's computer.
- You generate a registration code with your private key.
- The app contains only the public key, so it can verify codes but cannot generate them.
- The private key is stored locally at `windows/tools/license-private-key.pem` and is ignored by git. Keep it private and back it up.

Generate a permanent registration code:

```bash
cd windows
node tools/generate-license.js ABCDEF-123456-789ABC-DEF123-456789 "Customer Name" permanent
```

Generate a time-limited registration code:

```bash
cd windows
node tools/generate-license.js ABCDEF-123456-789ABC-DEF123-456789 "Customer Name" 2027-12-31
```

## Build a Windows Installer

This repo keeps the development app lightweight. To package an `.exe`, install `electron-builder` on a Windows machine:

```bash
cd windows
npm install
npm install --save-dev electron-builder
npx electron-builder --win nsis portable
```

The output will be under `windows/dist/`.

## Notes

- The built-in server listens on port `8088`.
- Windows Firewall may ask for permission the first time the service starts. Allow private network access.
- This is intended for trusted LAN/hotspot use. Add password protection before exposing it to untrusted networks.
