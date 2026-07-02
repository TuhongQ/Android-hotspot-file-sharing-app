const http = require("http");
const fs = require("fs");
const path = require("path");
const { URL } = require("url");
const crypto = require("crypto");

const clients = new Map();
const offers = new Map();

class FileHubServer {
  constructor({ folders, port }) {
    this.folders = folders;
    this.port = port;
    this.server = null;
  }

  start() {
    this.server = http.createServer((req, res) => this.handle(req, res));
    this.server.listen(this.port, "0.0.0.0");
  }

  stop() {
    if (this.server) this.server.close();
    this.server = null;
  }

  async handle(req, res) {
    try {
      const url = new URL(req.url, `http://${req.headers.host}`);
      if (req.method === "GET" && url.pathname === "/") return this.home(res);
      if (req.method === "GET" && url.pathname === "/events") return this.events(req, url, res);
      if (req.method === "GET" && url.pathname === "/browse") return this.browse(url, res);
      if (req.method === "GET" && url.pathname === "/download") return this.download(url, res, true);
      if (req.method === "GET" && url.pathname === "/raw") return this.download(url, res, false);
      if (req.method === "GET" && url.pathname === "/shared") return this.shared(url, res);
      if (req.method === "POST" && url.pathname === "/upload") return this.upload(req, url, res);
      if (req.method === "POST" && url.pathname === "/delete") return this.delete(url, res);
      this.text(res, 404, "Not Found");
    } catch (error) {
      this.text(res, 500, error.message || "Server Error");
    }
  }

  events(req, url, res) {
    const id = url.searchParams.get("id") || crypto.randomUUID();
    const name = url.searchParams.get("name") || `Phone ${id.slice(-4)}`;
    res.writeHead(200, {
      "Content-Type": "text/event-stream; charset=utf-8",
      "Cache-Control": "no-cache",
      "Connection": "keep-alive"
    });
    const client = {
      id,
      name,
      lastSeen: Date.now(),
      send(event, data) {
        try {
          res.write(`event: ${event}\n`);
          res.write(`data: ${JSON.stringify(data)}\n\n`);
          client.lastSeen = Date.now();
          return true;
        } catch {
          clients.delete(id);
          return false;
        }
      }
    };
    clients.set(id, client);
    client.send("hello", { id });
    const timer = setInterval(() => client.send("ping", { time: Date.now() }), 15000);
    req.on("close", () => {
      clearInterval(timer);
      clients.delete(id);
    });
  }

  home(res) {
    let html = pageStart("Bridge Share");
    html += `<section class="hero"><p>WINDOWS FILE HUB</p><h1>Bridge Share</h1><span>Choose a shared folder to browse, upload, or download files.</span></section>`;
    html += `<section class="card"><h2>Shared folders</h2>`;
    if (!this.folders.length) html += `<p class="muted">No folders are shared on the Windows computer.</p>`;
    this.folders.forEach((folder, index) => {
      html += `<a class="item link" href="/browse?r=${index}&path="><b>${escapeHtml(path.basename(folder) || folder)}</b><small>Open</small></a>`;
    });
    html += `</section>${pushDialog()}${clientScript()}</main></body></html>`;
    this.html(res, html);
  }

  browse(url, res) {
    const rootIndex = Number(url.searchParams.get("r") || 0);
    const relative = safeRelative(url.searchParams.get("path") || "");
    const directory = this.resolve(rootIndex, relative);
    if (!directory || !fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) return this.text(res, 404, "Folder not found");

    let html = pageStart(path.basename(this.folders[rootIndex]) || "Shared folder");
    html += `<section class="hero"><p>SHARED FOLDER</p><h1>${escapeHtml(path.basename(this.folders[rootIndex]) || "Shared folder")}</h1><span>/${escapeHtml(relative)}</span></section>`;
    html += `<section class="uploadCard"><form id="uploadForm" method="post" enctype="multipart/form-data" action="/upload?r=${rootIndex}&path=${encodeURIComponent(relative)}"><strong>Upload here</strong><span>Select photos, videos, or files from your phone.</span><label class="filePick"><input name="files" type="file" multiple><em>Choose files</em></label><button>Upload files</button></form></section>`;
    html += `<section class="card"><h2>Files</h2>`;
    html += `<a class="item link" href="/"><b>All shared folders</b><small>Home</small></a>`;
    const parent = parentPath(relative);
    if (parent !== null) html += `<a class="item link" href="/browse?r=${rootIndex}&path=${encodeURIComponent(parent)}"><b>Parent folder</b><small>..</small></a>`;

    const entries = fs.readdirSync(directory, { withFileTypes: true });
    if (!entries.length) html += `<p class="muted">This folder is empty.</p>`;
    for (const entry of entries) {
      const childRelative = relative ? `${relative}/${entry.name}` : entry.name;
      const encoded = encodeURIComponent(childRelative);
      if (entry.isDirectory()) {
        html += `<div class="swipe"><button class="deleteBtn" data-delete="/delete?r=${rootIndex}&path=${encoded}">Delete</button><a class="item link swipeContent" href="/browse?r=${rootIndex}&path=${encoded}"><span class="folderIcon">DIR</span><b>${escapeHtml(entry.name)}</b><small>Folder</small></a></div>`;
      } else {
        const full = path.join(directory, entry.name);
        const stat = fs.statSync(full);
        html += `<div class="swipe"><button class="deleteBtn" data-delete="/delete?r=${rootIndex}&path=${encoded}">Delete</button><div class="item swipeContent">`;
        if (isImage(entry.name)) html += `<img class="thumb" src="/raw?r=${rootIndex}&path=${encoded}" data-full="/raw?r=${rootIndex}&path=${encoded}" alt="">`;
        html += `<b>${escapeHtml(entry.name)}</b><span>${formatBytes(stat.size)}</span><a href="/download?r=${rootIndex}&path=${encoded}">Download</a></div></div>`;
      }
    }
    html += `</section>${progressDialog()}${imagePreview()}${pushDialog()}${pageScript()}${clientScript()}</main></body></html>`;
    this.html(res, html);
  }

  download(url, res, attachment) {
    const file = this.resolve(Number(url.searchParams.get("r") || 0), safeRelative(url.searchParams.get("path") || ""));
    if (!file || !fs.existsSync(file) || !fs.statSync(file).isFile()) return this.text(res, 404, "File not found");
    const name = path.basename(file);
    res.writeHead(200, {
      "Content-Type": mimeFromName(name),
      "Content-Length": fs.statSync(file).size,
      "Content-Disposition": attachment ? `attachment; filename*=UTF-8''${encodeURIComponent(name)}` : "inline",
      "Cache-Control": "no-store"
    });
    fs.createReadStream(file).pipe(res);
  }

  async upload(req, url, res) {
    const directory = this.resolve(Number(url.searchParams.get("r") || 0), safeRelative(url.searchParams.get("path") || ""));
    if (!directory || !fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) return this.text(res, 403, "Folder is not writable");
    const type = req.headers["content-type"] || "";
    const boundaryMatch = type.match(/boundary=(.+)$/);
    if (!boundaryMatch) return this.text(res, 400, "Missing multipart boundary");
    const body = await readAll(req);
    const count = saveMultipart(directory, body, `--${boundaryMatch[1]}`);
    const redirect = `/browse?r=${url.searchParams.get("r") || 0}&path=${encodeURIComponent(safeRelative(url.searchParams.get("path") || ""))}`;
    this.json(res, { ok: true, saved: count, redirect });
  }

  delete(url, res) {
    const target = this.resolve(Number(url.searchParams.get("r") || 0), safeRelative(url.searchParams.get("path") || ""));
    if (!target || !fs.existsSync(target)) return this.json(res, { ok: false, error: "not_found" }, 404);
    fs.rmSync(target, { recursive: true, force: true });
    this.json(res, { ok: true });
  }

  shared(url, res) {
    const offer = offers.get(url.searchParams.get("id"));
    if (!offer || !fs.existsSync(offer.filePath)) return this.text(res, 404, "Shared file not found");
    const stat = fs.statSync(offer.filePath);
    offer.started = true;
    offer.sent = 0;
    offer.completed = false;
    res.writeHead(200, {
      "Content-Type": mimeFromName(offer.name),
      "Content-Length": stat.size,
      "Content-Disposition": `attachment; filename*=UTF-8''${encodeURIComponent(offer.name)}`,
      "Cache-Control": "no-store"
    });
    const stream = fs.createReadStream(offer.filePath);
    stream.on("data", (chunk) => {
      offer.sent += chunk.length;
    });
    stream.on("end", () => {
      offer.completed = true;
    });
    stream.pipe(res);
  }

  resolve(rootIndex, relative) {
    const root = this.folders[rootIndex];
    if (!root) return null;
    const resolved = path.resolve(root, relative);
    const safeRoot = path.resolve(root);
    return resolved === safeRoot || resolved.startsWith(safeRoot + path.sep) ? resolved : null;
  }

  html(res, html) {
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(html);
  }

  text(res, status, body) {
    res.writeHead(status, { "Content-Type": "text/plain; charset=utf-8" });
    res.end(body);
  }

  json(res, body, status = 200) {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify(body));
  }
}

FileHubServer.connectedClients = function connectedClients() {
  return Array.from(clients.values()).map((client) => ({
    id: client.id,
    name: client.name,
    lastSeen: client.lastSeen
  }));
};

FileHubServer.pushFileToClient = function pushFileToClient(clientId, filePath) {
  const client = clients.get(clientId);
  if (!client || !fs.existsSync(filePath) || !fs.statSync(filePath).isFile()) return null;
  const id = crypto.randomUUID();
  const stat = fs.statSync(filePath);
  const offer = {
    id,
    filePath,
    name: path.basename(filePath),
    size: stat.size,
    sent: 0,
    started: false,
    completed: false
  };
  offers.set(id, offer);
  const ok = client.send("push", {
    id,
    name: offer.name,
    size: offer.size,
    sizeText: formatBytes(offer.size),
    url: `/shared?id=${id}`
  });
  return ok ? id : null;
};

FileHubServer.offerProgress = function offerProgress(id) {
  const offer = offers.get(id);
  if (!offer) return { total: -1, sent: 0, started: false, completed: false };
  return { total: offer.size, sent: offer.sent, started: offer.started, completed: offer.completed };
};

function pageStart(title) {
  return `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${escapeHtml(title)}</title><style>
:root{color:#e5e7eb;background:#070a12;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif}body{margin:0;background:#070a12}main{max-width:820px;margin:0 auto;padding:16px 14px 34px}.hero{background:linear-gradient(135deg,#111827,#0f766e,#7c2d12);color:white;border-radius:22px;padding:24px;margin-bottom:14px;box-shadow:0 18px 42px rgba(20,184,166,.24)}.hero p{margin:0 0 8px;color:#99f6e4;font-size:12px;font-weight:900;letter-spacing:.08em}.hero h1{margin:0;font-size:34px}.hero span{display:block;margin-top:10px;color:#e5e7eb;overflow-wrap:anywhere}.card,.uploadCard{background:#101826;border:1px solid #1f2937;border-radius:18px;padding:16px;margin:12px 0;box-shadow:0 12px 34px rgba(0,0,0,.2)}.uploadCard{border-color:#22d3ee;background:linear-gradient(180deg,#101826,#0f172a)}h2{font-size:18px;margin:0 0 12px;color:#f9fafb}.muted{color:#94a3b8;line-height:1.55}.item{display:flex;align-items:center;gap:10px;background:#111827;border:1px solid #334155;border-radius:14px;padding:15px;margin:10px 0;color:#e5e7eb;text-decoration:none}.item b{flex:1;min-width:0;overflow-wrap:anywhere}.item span,.item small{color:#94a3b8}.item a,a{color:#22d3ee;font-weight:900;text-decoration:none}.swipe{position:relative;overflow:hidden;border-radius:14px;margin:10px 0}.swipe .item{margin:0;transition:transform .18s ease}.deleteBtn{position:absolute;right:0;top:0;bottom:0;width:86px;height:auto;border:0;border-radius:0 14px 14px 0;background:#ef4444;color:white}.swipeContent{position:relative;z-index:1}.folderIcon{display:grid;place-items:center;width:58px;height:58px;border-radius:13px;background:#0f766e;color:#ccfbf1;font-size:12px;font-weight:900}.thumb{width:68px;height:68px;border-radius:14px;object-fit:cover;background:#020617;border:1px solid #334155;flex:0 0 auto}form{display:grid;gap:13px;min-width:0}form strong{font-size:19px;color:#f9fafb}form span{color:#94a3b8}.filePick{display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;padding:14px;border:1px dashed #22d3ee;border-radius:16px;background:#020617;overflow:hidden}.filePick input{display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;color:#e5e7eb;font-size:15px}.filePick input::file-selector-button{height:44px;margin-right:10px;border:0;border-radius:12px;background:#22d3ee;color:#061018;font-weight:900;padding:0 14px}button{height:58px;border:0;border-radius:16px;background:#22d3ee;color:#061018;font-size:17px;font-weight:900}.overlay{position:fixed;inset:0;display:none;align-items:center;justify-content:center;background:rgba(2,6,23,.72);backdrop-filter:blur(8px);padding:20px;z-index:9}.dialog{width:min(360px,100%);background:#101826;border:1px solid #22d3ee;border-radius:20px;padding:20px;box-shadow:0 24px 70px rgba(0,0,0,.45)}.bar{height:14px;background:#020617;border-radius:999px;overflow:hidden;border:1px solid #164e63}.fill{height:100%;width:0;background:linear-gradient(90deg,#22d3ee,#14b8a6)}.pct{margin-top:10px;color:#67e8f9;font-weight:900;text-align:right}.preview{position:fixed;inset:0;display:none;align-items:center;justify-content:center;background:rgba(2,6,23,.9);z-index:10;padding:18px}.preview img{max-width:100%;max-height:88vh;border-radius:18px}.preview button{position:absolute;top:18px;right:18px;width:48px;height:48px;border-radius:50%;background:#111827;color:#e5e7eb}.pushActions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.pushActions a,.pushActions button{display:grid;place-items:center;height:50px;border-radius:14px;font-size:16px}.pushActions .cancel{background:#1f2937;color:#e5e7eb}
</style></head><body><main>`;
}

function progressDialog() {
  return `<div class="overlay" id="progressOverlay"><div class="dialog"><h3>Uploading</h3><p id="progressText">Preparing...</p><div class="bar"><div class="fill" id="progressFill"></div></div><div class="pct" id="progressPct">0%</div></div></div>`;
}

function imagePreview() {
  return `<div class="preview" id="imagePreview"><button id="closePreview">X</button><img id="previewImage" alt=""></div>`;
}

function pushDialog() {
  return `<div class="overlay" id="pushOverlay"><div class="dialog"><h3>Windows sent a file</h3><p id="pushText">A file is waiting.</p><div class="bar"><div class="fill" id="pushFill"></div></div><div class="pct" id="pushPct">Waiting</div><div class="pushActions"><button class="cancel" id="pushCancel">Later</button><a id="pushDownload" href="#">Receive</a></div></div></div>`;
}

function pageScript() {
  return `<script>
(function(){var form=document.getElementById('uploadForm');if(!form)return;var overlay=document.getElementById('progressOverlay'),fill=document.getElementById('progressFill'),pct=document.getElementById('progressPct'),text=document.getElementById('progressText');form.addEventListener('submit',function(e){e.preventDefault();var input=form.querySelector('input[type=file]');if(!input.files.length){alert('Choose files first');return;}overlay.style.display='flex';var xhr=new XMLHttpRequest();xhr.open('POST',form.action,true);xhr.upload.onprogress=function(ev){if(ev.lengthComputable){var p=Math.round(ev.loaded*100/ev.total);fill.style.width=p+'%';pct.textContent=p+'%';text.textContent='Uploading '+input.files.length+' file(s)';}};xhr.onload=function(){fill.style.width='100%';pct.textContent='100%';try{var data=JSON.parse(xhr.responseText);setTimeout(function(){location.href=data.redirect||location.href;},350);}catch(e){location.reload();}};xhr.onerror=function(){pct.textContent='Failed';};xhr.send(new FormData(form));});})();
(function(){document.querySelectorAll('.swipe').forEach(function(row){var content=row.querySelector('.swipeContent'),del=row.querySelector('.deleteBtn'),startX=0,current=0;row.addEventListener('touchstart',function(e){startX=e.touches[0].clientX;current=0;},{passive:true});row.addEventListener('touchmove',function(e){current=e.touches[0].clientX-startX;if(current<0)content.style.transform='translateX('+Math.max(current,-86)+'px)';},{passive:true});row.addEventListener('touchend',function(){content.style.transform=current<-42?'translateX(-86px)':'translateX(0)';});del.addEventListener('click',function(){if(!confirm('Delete this item?'))return;fetch(del.dataset.delete,{method:'POST'}).then(function(r){if(!r.ok)throw new Error();row.remove();}).catch(function(){alert('Delete failed');});});});})();
(function(){var modal=document.getElementById('imagePreview'),img=document.getElementById('previewImage'),close=document.getElementById('closePreview');document.querySelectorAll('.thumb').forEach(function(t){t.addEventListener('click',function(e){e.preventDefault();img.src=t.dataset.full;modal.style.display='flex';});});close&&close.addEventListener('click',function(){modal.style.display='none';img.src='';});modal&&modal.addEventListener('click',function(e){if(e.target===modal){modal.style.display='none';img.src='';}});})();
</script>`;
}

function clientScript() {
  return `<script>
(function(){if(!window.EventSource)return;var id=localStorage.getItem('bridgeClientId');if(!id){id='c_'+Math.random().toString(36).slice(2)+Date.now();localStorage.setItem('bridgeClientId',id);}var name=localStorage.getItem('bridgeClientName');if(!name){name=(navigator.platform||'Phone')+' '+id.slice(-4);localStorage.setItem('bridgeClientName',name);}var source=new EventSource('/events?id='+encodeURIComponent(id)+'&name='+encodeURIComponent(name));source.addEventListener('push',function(ev){var data={};try{data=JSON.parse(ev.data||'{}');}catch(e){}var overlay=document.getElementById('pushOverlay'),text=document.getElementById('pushText'),link=document.getElementById('pushDownload'),cancel=document.getElementById('pushCancel'),fill=document.getElementById('pushFill'),pct=document.getElementById('pushPct');if(!overlay||!link)return;text.textContent='File: '+(data.name||'download')+', size: '+(data.sizeText||'unknown');fill.style.width='0%';pct.textContent='Waiting';overlay.style.display='flex';cancel.onclick=function(){overlay.style.display='none';};link.onclick=function(e){e.preventDefault();link.style.pointerEvents='none';var xhr=new XMLHttpRequest();xhr.open('GET',data.url,true);xhr.responseType='blob';xhr.onprogress=function(ev){if(ev.lengthComputable){var p=Math.round(ev.loaded*100/ev.total);fill.style.width=p+'%';pct.textContent=p+'%';}else{pct.textContent='Receiving...';}};xhr.onload=function(){link.style.pointerEvents='auto';if(xhr.status>=200&&xhr.status<300){fill.style.width='100%';pct.textContent='100%';var blob=xhr.response;var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=data.name||'download';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();overlay.style.display='none';},650);}else{pct.textContent='Failed';}};xhr.onerror=function(){link.style.pointerEvents='auto';pct.textContent='Failed';};xhr.send();};});})();
</script>`;
}

function readAll(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    req.on("data", (chunk) => chunks.push(chunk));
    req.on("end", () => resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function saveMultipart(directory, body, boundary) {
  const boundaryBuffer = Buffer.from(boundary, "latin1");
  let cursor = 0;
  let count = 0;
  while (true) {
    let start = body.indexOf(boundaryBuffer, cursor);
    if (start < 0) break;
    start += boundaryBuffer.length;
    if (body[start] === 45 && body[start + 1] === 45) break;
    if (body[start] === 13 && body[start + 1] === 10) start += 2;
    const headerEnd = body.indexOf(Buffer.from("\r\n\r\n", "latin1"), start);
    if (headerEnd < 0) break;
    const headers = body.slice(start, headerEnd).toString("utf8");
    const name = multipartFilename(headers);
    const dataStart = headerEnd + 4;
    let next = body.indexOf(boundaryBuffer, dataStart);
    if (next < 0) break;
    let dataEnd = next;
    if (body[dataEnd - 2] === 13 && body[dataEnd - 1] === 10) dataEnd -= 2;
    if (name && dataEnd > dataStart) {
      fs.writeFileSync(path.join(directory, sanitizeName(name)), body.slice(dataStart, dataEnd));
      count += 1;
    }
    cursor = next;
  }
  return count;
}

function multipartFilename(headers) {
  const match = headers.match(/filename="([^"]+)"/i) || headers.match(/filename=([^;\r\n]+)/i);
  return match ? match[1].trim() : "";
}

function safeRelative(value) {
  return value.replace(/\\/g, "/").split("/").filter((part) => part && part !== "." && part !== "..").join("/");
}

function parentPath(value) {
  if (!value) return null;
  const index = value.lastIndexOf("/");
  return index < 0 ? "" : value.slice(0, index);
}

function sanitizeName(name) {
  return name.replace(/[\\/:*?"<>|]/g, "_").trim() || "upload.bin";
}

function escapeHtml(value) {
  return String(value).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return `${value.toFixed(1)} ${units[index]}`;
}

function isImage(name) {
  return /\.(jpg|jpeg|png|gif|webp|bmp)$/i.test(name);
}

function mimeFromName(name) {
  const ext = path.extname(name).toLowerCase();
  return {
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".png": "image/png",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".bmp": "image/bmp",
    ".pdf": "application/pdf",
    ".txt": "text/plain; charset=utf-8",
    ".zip": "application/zip"
  }[ext] || "application/octet-stream";
}

module.exports = { FileHubServer };
