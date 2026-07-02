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
2. Add one or more shared folders.
3. Click `Start service`.
4. Make sure the phone and Windows PC are on the same LAN.
5. Scan the QR code or open the shown LAN URL on the phone.

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
