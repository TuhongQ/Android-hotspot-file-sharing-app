const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("bridgeShare", {
  state: () => ipcRenderer.invoke("state"),
  chooseFolders: () => ipcRenderer.invoke("chooseFolders"),
  removeFolder: (folder) => ipcRenderer.invoke("removeFolder", folder),
  clearFolders: () => ipcRenderer.invoke("clearFolders"),
  startServer: () => ipcRenderer.invoke("startServer"),
  stopServer: () => ipcRenderer.invoke("stopServer"),
  openExternal: (url) => ipcRenderer.invoke("openExternal", url),
  clients: () => ipcRenderer.invoke("clients"),
  chooseSendFiles: () => ipcRenderer.invoke("chooseSendFiles"),
  pushFile: (clientId, filePath) => ipcRenderer.invoke("pushFile", clientId, filePath),
  offerProgress: (offerId) => ipcRenderer.invoke("offerProgress", offerId)
});
