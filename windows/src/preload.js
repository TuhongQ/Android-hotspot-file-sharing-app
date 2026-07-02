const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("bridgeShare", {
  state: () => ipcRenderer.invoke("state"),
  chooseFolders: () => ipcRenderer.invoke("chooseFolders"),
  removeFolder: (folder) => ipcRenderer.invoke("removeFolder", folder),
  clearFolders: () => ipcRenderer.invoke("clearFolders"),
  startServer: () => ipcRenderer.invoke("startServer"),
  stopServer: () => ipcRenderer.invoke("stopServer"),
  openExternal: (url) => ipcRenderer.invoke("openExternal", url)
});
