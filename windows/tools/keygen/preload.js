const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("bridgeKeygen", {
  generateLicense: (input) => ipcRenderer.invoke("generateLicense", input),
  copyText: (text) => ipcRenderer.invoke("copyText", text)
});
