package cn.local.bridgeshare;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileShareServer {
    private static final Map<String, ClientConnection> CLIENTS = new ConcurrentHashMap<>();
    private static final Map<String, SharedOffer> OFFERS = new ConcurrentHashMap<>();
    private final Context context;
    private final List<SharedRoot> roots;
    private final int port;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket serverSocket;

    public FileShareServer(Context context, List<SharedRoot> roots, int port) {
        this.context = context.getApplicationContext();
        this.roots = new ArrayList<>(roots);
        this.port = port;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        running = true;
        workers.execute(() -> {
            while (running) {
                try {
                    Socket socket = serverSocket.accept();
                    workers.execute(() -> handle(socket));
                } catch (IOException ignored) {
                }
            }
        });
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        workers.shutdownNow();
    }

    private void handle(Socket socket) {
        try (Socket closeable = socket) {
            closeable.setSoTimeout(30_000);
            BufferedInputStream input = new BufferedInputStream(closeable.getInputStream());
            OutputStream output = closeable.getOutputStream();
            Request request = readRequest(input);
            if (request == null) return;

            if ("GET".equals(request.method)) {
                handleGet(request, output);
            } else if ("POST".equals(request.method) && request.path.equals("/upload")) {
                handleUpload(request, input, output);
            } else if ("POST".equals(request.method) && request.path.equals("/delete")) {
                handleDelete(request, output);
            } else {
                writeText(output, 405, "text/plain; charset=utf-8", "Method Not Allowed");
            }
        } catch (Exception ignored) {
        }
    }

    private Request readRequest(BufferedInputStream input) throws IOException {
        String requestLine = readLine(input);
        if (requestLine == null || requestLine.isEmpty()) return null;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) return null;
        Request request = new Request();
        request.method = parts[0].trim().toUpperCase(Locale.ROOT);
        String target = parts[1].trim();
        int queryIndex = target.indexOf('?');
        request.path = queryIndex >= 0 ? target.substring(0, queryIndex) : target;
        request.query = parseQuery(queryIndex >= 0 ? target.substring(queryIndex + 1) : "");

        String line;
        while ((line = readLine(input)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                request.headers.put(
                        line.substring(0, colon).trim().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
        }
        return request;
    }

    private void handleGet(Request request, OutputStream output) throws IOException {
        if (request.path.equals("/events")) {
            handleEvents(request, output);
            return;
        }

        if (request.path.equals("/")) {
            writeText(output, 200, "text/html; charset=utf-8", renderHome());
            return;
        }

        if (request.path.equals("/browse")) {
            int rootIndex = parseInt(request.query.get("r"), 0);
            String relativePath = safePath(request.query.get("path"));
            DocumentFile directory = resolve(rootIndex, relativePath);
            if (directory == null || !directory.isDirectory()) {
                writeText(output, 404, "text/plain; charset=utf-8", "Folder not found");
                return;
            }
            writeText(output, 200, "text/html; charset=utf-8", renderDirectory(rootIndex, relativePath, directory));
            return;
        }

        if (request.path.equals("/download")) {
            int rootIndex = parseInt(request.query.get("r"), 0);
            String relativePath = safePath(request.query.get("path"));
            DocumentFile file = resolve(rootIndex, relativePath);
            if (file == null || !file.isFile()) {
                writeText(output, 404, "text/plain; charset=utf-8", "File not found");
                return;
            }
            writeFile(output, file);
            return;
        }

        if (request.path.equals("/raw")) {
            int rootIndex = parseInt(request.query.get("r"), 0);
            String relativePath = safePath(request.query.get("path"));
            DocumentFile file = resolve(rootIndex, relativePath);
            if (file == null || !file.isFile()) {
                writeText(output, 404, "text/plain; charset=utf-8", "File not found");
                return;
            }
            writeRawFile(output, file);
            return;
        }

        if (request.path.equals("/shared")) {
            SharedOffer offer = OFFERS.get(request.query.get("id"));
            if (offer == null) {
                writeText(output, 404, "text/plain; charset=utf-8", "Shared file not found");
                return;
            }
            writeSharedOffer(output, offer);
            return;
        }

        writeText(output, 404, "text/plain; charset=utf-8", "Not Found");
    }

    private void handleEvents(Request request, OutputStream output) throws IOException {
        String id = request.query.get("id");
        if (id == null || id.trim().isEmpty()) id = UUID.randomUUID().toString();
        String name = request.query.get("name");
        if (name == null || name.trim().isEmpty()) name = "苹果设备";
        ClientConnection connection = new ClientConnection(id, name, output);
        CLIENTS.put(id, connection);
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: keep-alive\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        connection.send("hello", "{\"id\":\"" + escapeJson(id) + "\"}");
        try {
            while (running && !Thread.currentThread().isInterrupted()) {
                connection.send("ping", "{\"time\":" + System.currentTimeMillis() + "}");
                Thread.sleep(15000);
            }
        } catch (Exception ignored) {
        } finally {
            CLIENTS.remove(id, connection);
        }
    }

    private void handleUpload(Request request, InputStream input, OutputStream output) throws IOException {
        int rootIndex = parseInt(request.query.get("r"), 0);
        String relativePath = safePath(request.query.get("path"));
        DocumentFile directory = resolve(rootIndex, relativePath);
        if (directory == null || !directory.isDirectory() || !directory.canWrite()) {
            writeText(output, 403, "text/plain; charset=utf-8", "Folder is not writable");
            return;
        }

        int contentLength = parseInt(request.headers.get("content-length"));
        String contentType = request.headers.get("content-type");
        if (contentLength <= 0 || contentType == null || !contentType.contains("multipart/form-data")) {
            writeText(output, 400, "text/plain; charset=utf-8", "Invalid upload");
            return;
        }
        String boundary = "--" + valueAfter(contentType, "boundary=");
        if (boundary.length() <= 2) {
            writeText(output, 400, "text/plain; charset=utf-8", "Missing multipart boundary");
            return;
        }

        byte[] body = readBytes(input, contentLength);
        int saved = saveMultipartFiles(directory, body, boundary);
        String location = "/browse?r=" + rootIndex + "&path=" + encode(relativePath);
        if ("XMLHttpRequest".equalsIgnoreCase(request.headers.get("x-requested-with"))) {
            writeText(output, 200, "application/json; charset=utf-8", "{\"ok\":true,\"saved\":" + saved + ",\"redirect\":\"" + escapeJson(location) + "\"}");
        } else {
            redirect(output, location, "已上传 " + saved + " 个文件");
        }
    }

    private void handleDelete(Request request, OutputStream output) throws IOException {
        int rootIndex = parseInt(request.query.get("r"), 0);
        String relativePath = safePath(request.query.get("path"));
        DocumentFile target = resolve(rootIndex, relativePath);
        if (target == null || relativePath.isEmpty()) {
            writeText(output, 404, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"not_found\"}");
            return;
        }
        boolean deleted = target.delete();
        if (!deleted) {
            writeText(output, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"delete_failed\"}");
            return;
        }
        writeText(output, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private int saveMultipartFiles(DocumentFile directory, byte[] body, String boundary) throws IOException {
        byte[] boundaryBytes = boundary.getBytes(StandardCharsets.ISO_8859_1);
        int count = 0;
        int cursor = 0;
        while (true) {
            int partStart = indexOf(body, boundaryBytes, cursor);
            if (partStart < 0) break;
            partStart += boundaryBytes.length;
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') break;
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') partStart += 2;

            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), partStart);
            if (headerEnd < 0) break;
            String headers = new String(body, partStart, headerEnd - partStart, StandardCharsets.UTF_8);
            String filename = multipartFilename(headers);
            int dataStart = headerEnd + 4;
            int nextBoundary = indexOf(body, boundaryBytes, dataStart);
            if (nextBoundary < 0) break;
            int dataEnd = nextBoundary;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') dataEnd -= 2;

            if (filename != null && dataEnd > dataStart) {
                saveFile(directory, filename, body, dataStart, dataEnd - dataStart);
                count++;
            }
            cursor = nextBoundary;
        }
        return count;
    }

    private void saveFile(DocumentFile directory, String filename, byte[] body, int offset, int length) throws IOException {
        String cleanName = sanitizeName(filename);
        DocumentFile existing = directory.findFile(cleanName);
        if (existing != null && existing.isFile()) existing.delete();
        DocumentFile file = directory.createFile("application/octet-stream", cleanName);
        if (file == null) throw new IOException("Cannot create file");
        try (OutputStream output = context.getContentResolver().openOutputStream(file.getUri(), "w")) {
            if (output == null) throw new IOException("Cannot open file");
            output.write(body, offset, length);
        }
    }

    private String renderHome() {
        StringBuilder html = new StringBuilder();
        appendHtmlStart(html, "闪桥共享");
        html.append("<section class=\"hero\"><p>ANDROID HOTSPOT FILE HUB</p><h1>闪桥共享</h1><span>选择一个共享文件夹开始浏览、上传或下载。</span></section>");
        html.append("<section class=\"card\"><h2>共享文件夹</h2>");
        if (roots.isEmpty()) {
            html.append("<p class=\"muted\">安卓端还没有添加共享文件夹。</p>");
        }
        for (int i = 0; i < roots.size(); i++) {
            SharedRoot root = roots.get(i);
            html.append("<a class=\"item link\" href=\"/browse?r=").append(i).append("&path=\">")
                    .append("<b>").append(escape(root.name)).append("</b>")
                    .append("<small>打开</small></a>");
        }
        appendProgressDialog(html);
        appendPushDialog(html);
        appendClientScript(html);
        html.append("</section></main></body></html>");
        return html.toString();
    }

    private String renderDirectory(int rootIndex, String relativePath, DocumentFile directory) {
        StringBuilder html = new StringBuilder();
        appendHtmlStart(html, rootName(rootIndex));
        html.append("<section class=\"hero\"><p>共享目录</p><h1>")
                .append(escape(rootName(rootIndex)))
                .append("</h1><span>/")
                .append(escape(relativePath))
                .append("</span></section>")
                .append("<section class=\"uploadCard\">")
                .append("<form id=\"uploadForm\" method=\"post\" enctype=\"multipart/form-data\" action=\"/upload?r=").append(rootIndex).append("&path=").append(encode(relativePath)).append("\">")
                .append("<strong>上传到当前目录</strong><span>从 iPhone 选择照片、视频或文件</span><label class=\"filePick\"><input name=\"files\" type=\"file\" multiple><em>选择文件</em></label><button>上传文件</button></form></section>")
                .append("<section class=\"card\"><h2>文件</h2>");

        html.append("<a class=\"item link\" href=\"/\"><b>全部共享文件夹</b><small>首页</small></a>");
        String parent = parentPath(relativePath);
        if (parent != null) {
            html.append("<a class=\"item link\" href=\"/browse?r=").append(rootIndex).append("&path=").append(encode(parent)).append("\"><b>返回上级</b><small>..</small></a>");
        }

        DocumentFile[] files = directory.listFiles();
        if (files.length == 0) {
            html.append("<p class=\"muted\">当前目录为空。</p>");
        }
        for (DocumentFile file : files) {
            String fileName = file.getName() == null ? "未命名" : file.getName();
            String childPath = relativePath.isEmpty() ? fileName : relativePath + "/" + fileName;
            String encodedChild = encode(childPath);
            if (file.isDirectory()) {
                html.append("<div class=\"swipe\" data-path=\"").append(escape(childPath)).append("\"><button class=\"deleteBtn\" data-delete=\"/delete?r=").append(rootIndex).append("&path=").append(encodedChild).append("\">删除</button>")
                        .append("<a class=\"item link swipeContent\" href=\"/browse?r=").append(rootIndex).append("&path=").append(encodedChild).append("\">")
                        .append("<span class=\"folderIcon\">DIR</span><b>").append(escape(fileName)).append("</b><small>文件夹</small></a></div>");
            } else {
                html.append("<div class=\"swipe\" data-path=\"").append(escape(childPath)).append("\"><button class=\"deleteBtn\" data-delete=\"/delete?r=").append(rootIndex).append("&path=").append(encodedChild).append("\">删除</button>")
                        .append("<div class=\"item swipeContent\">");
                if (isImage(file)) {
                    String raw = "/raw?r=" + rootIndex + "&path=" + encodedChild;
                    html.append("<img class=\"thumb\" src=\"").append(raw).append("\" data-full=\"").append(raw).append("\" alt=\"\">");
                }
                html.append("<b>")
                        .append(escape(fileName))
                        .append("</b><span>")
                        .append(formatBytes(file.length()))
                        .append("</span><a href=\"/download?r=")
                        .append(rootIndex)
                        .append("&path=")
                        .append(encodedChild)
                        .append("\">下载</a></div></div>");
            }
        }

        appendProgressDialog(html);
        appendImagePreview(html);
        appendPushDialog(html);
        appendPageScript(html);
        appendClientScript(html);
        html.append("</section></main></body></html>");
        return html.toString();
    }

    private void appendHtmlStart(StringBuilder html, String title) {
        html.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(escape(title)).append("</title>")
                .append("<style>")
                .append(":root{color:#e5e7eb;background:#070a12;font-family:-apple-system,BlinkMacSystemFont,Segoe UI,sans-serif}")
                .append("body{margin:0;background:#070a12}main{max-width:820px;margin:0 auto;padding:16px 14px 34px}")
                .append(".hero{background:linear-gradient(135deg,#111827,#0f766e,#7c2d12);color:white;border-radius:22px;padding:24px;margin-bottom:14px;box-shadow:0 18px 42px rgba(20,184,166,.24)}")
                .append(".hero p{margin:0 0 8px;color:#99f6e4;font-size:12px;font-weight:900;letter-spacing:.08em}.hero h1{margin:0;font-size:34px}.hero span{display:block;margin-top:10px;color:#e5e7eb;overflow-wrap:anywhere}")
                .append(".card,.uploadCard{background:#101826;border:1px solid #1f2937;border-radius:18px;padding:16px;margin:12px 0;box-shadow:0 12px 34px rgba(0,0,0,.2)}")
                .append(".uploadCard{border-color:#22d3ee;background:linear-gradient(180deg,#101826,#0f172a)}")
                .append("h2{font-size:18px;margin:0 0 12px;color:#f9fafb}.muted{color:#94a3b8;line-height:1.55}")
                .append(".item{display:flex;align-items:center;gap:10px;background:#111827;border:1px solid #334155;border-radius:14px;padding:15px;margin:10px 0;color:#e5e7eb;text-decoration:none}")
                .append(".item b{flex:1;min-width:0;overflow-wrap:anywhere}.item span,.item small{color:#94a3b8}.item a,a{color:#22d3ee;font-weight:900;text-decoration:none}.link:active{background:#0f766e}")
                .append(".swipe{position:relative;overflow:hidden;border-radius:14px;margin:10px 0}.swipe .item{margin:0;transition:transform .18s ease;will-change:transform}.deleteBtn{position:absolute;right:0;top:0;bottom:0;width:86px;height:auto;border-radius:0 14px 14px 0;background:#ef4444;color:white;z-index:0}.swipeContent{position:relative;z-index:1}.folderIcon{display:grid;place-items:center;width:58px;height:58px;border-radius:13px;background:#0f766e;color:#ccfbf1;font-size:12px;font-weight:900}")
                .append(".thumb{width:68px;height:68px;border-radius:14px;object-fit:cover;background:#020617;border:1px solid #334155;flex:0 0 auto}")
                .append("form{display:grid;gap:13px;min-width:0}form strong{font-size:19px;color:#f9fafb}form span{color:#94a3b8}")
                .append(".filePick{display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;padding:14px;border:1px dashed #22d3ee;border-radius:16px;background:#020617;overflow:hidden}")
                .append(".filePick input{display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;color:#e5e7eb;font-size:15px}")
                .append(".filePick input::file-selector-button{height:44px;margin-right:10px;border:0;border-radius:12px;background:#22d3ee;color:#061018;font-weight:900;padding:0 14px}")
                .append(".filePick em{display:block;margin-top:8px;color:#67e8f9;font-style:normal;font-size:13px}")
                .append("button{height:58px;border:0;border-radius:16px;background:#22d3ee;color:#061018;font-size:17px;font-weight:900}")
                .append(".overlay{position:fixed;inset:0;display:none;align-items:center;justify-content:center;background:rgba(2,6,23,.72);backdrop-filter:blur(8px);padding:20px;z-index:9}")
                .append(".dialog{width:min(360px,100%);background:#101826;border:1px solid #22d3ee;border-radius:20px;padding:20px;box-shadow:0 24px 70px rgba(0,0,0,.45)}")
                .append(".dialog h3{margin:0 0 8px;color:#f9fafb}.dialog p{margin:0 0 14px;color:#94a3b8}.bar{height:14px;background:#020617;border-radius:999px;overflow:hidden;border:1px solid #164e63}.fill{height:100%;width:0;background:linear-gradient(90deg,#22d3ee,#14b8a6)}.pct{margin-top:10px;color:#67e8f9;font-weight:900;text-align:right}")
                .append(".preview{position:fixed;inset:0;display:none;align-items:center;justify-content:center;background:rgba(2,6,23,.9);z-index:10;padding:18px}.preview img{max-width:100%;max-height:88vh;border-radius:18px;box-shadow:0 24px 80px rgba(0,0,0,.55)}.preview button{position:absolute;top:18px;right:18px;width:48px;height:48px;border-radius:50%;background:#111827;color:#e5e7eb}")
                .append(".pushActions{display:grid;grid-template-columns:1fr 1fr;gap:10px}.pushActions a,.pushActions button{display:grid;place-items:center;height:50px;border-radius:14px;font-size:16px}.pushActions .cancel{background:#1f2937;color:#e5e7eb}")
                .append("</style></head><body><main>");
    }

    private void appendProgressDialog(StringBuilder html) {
        html.append("<div class=\"overlay\" id=\"progressOverlay\"><div class=\"dialog\"><h3>正在上传</h3><p id=\"progressText\">准备传输文件...</p><div class=\"bar\"><div class=\"fill\" id=\"progressFill\"></div></div><div class=\"pct\" id=\"progressPct\">0%</div></div></div>");
    }

    private void appendImagePreview(StringBuilder html) {
        html.append("<div class=\"preview\" id=\"imagePreview\"><button id=\"closePreview\">X</button><img id=\"previewImage\" alt=\"\"></div>");
    }

    private void appendPushDialog(StringBuilder html) {
        html.append("<div class=\"overlay\" id=\"pushOverlay\"><div class=\"dialog\"><h3>安卓发来文件</h3><p id=\"pushText\">有一个文件等待接收。</p><div class=\"bar\"><div class=\"fill\" id=\"pushFill\"></div></div><div class=\"pct\" id=\"pushPct\">等待接收</div><div class=\"pushActions\"><button class=\"cancel\" id=\"pushCancel\">稍后</button><a id=\"pushDownload\" href=\"#\">接收</a></div></div></div>");
    }

    private void appendPageScript(StringBuilder html) {
        html.append("<script>")
                .append("(function(){var form=document.getElementById('uploadForm');if(!form)return;")
                .append("var overlay=document.getElementById('progressOverlay'),fill=document.getElementById('progressFill'),pct=document.getElementById('progressPct'),text=document.getElementById('progressText');")
                .append("form.addEventListener('submit',function(e){e.preventDefault();var input=form.querySelector('input[type=file]');if(!input.files.length){alert('请先选择文件');return;}")
                .append("overlay.style.display='flex';fill.style.width='0%';pct.textContent='0%';text.textContent='正在连接安卓设备...';")
                .append("var xhr=new XMLHttpRequest();xhr.open('POST',form.action,true);")
                .append("xhr.setRequestHeader('X-Requested-With','XMLHttpRequest');")
                .append("xhr.upload.onprogress=function(ev){if(ev.lengthComputable){var p=Math.round(ev.loaded*100/ev.total);fill.style.width=p+'%';pct.textContent=p+'%';text.textContent='正在上传 '+input.files.length+' 个文件';}};")
                .append("xhr.onload=function(){fill.style.width='100%';pct.textContent='100%';if(xhr.status>=200&&xhr.status<300){text.textContent='上传完成，正在刷新目录...';var url='';try{url=JSON.parse(xhr.responseText).redirect||'';}catch(e){}setTimeout(function(){if(url){location.href=url;}else{location.reload();}},350);}else{text.textContent='上传失败：'+xhr.status;pct.textContent='失败';setTimeout(function(){overlay.style.display='none';},1200);}};")
                .append("xhr.onerror=function(){text.textContent='上传失败，请重新连接热点后再试';pct.textContent='失败';};")
                .append("xhr.ontimeout=function(){text.textContent='上传超时，请重试';pct.textContent='超时';setTimeout(function(){overlay.style.display='none';},1200);};xhr.timeout=120000;")
                .append("xhr.send(new FormData(form));});})();")
                .append("(function(){document.querySelectorAll('.swipe').forEach(function(row){var content=row.querySelector('.swipeContent'),del=row.querySelector('.deleteBtn'),startX=0,current=0;")
                .append("row.addEventListener('touchstart',function(e){startX=e.touches[0].clientX;current=0;},{passive:true});")
                .append("row.addEventListener('touchmove',function(e){current=e.touches[0].clientX-startX;if(current<0){content.style.transform='translateX('+Math.max(current,-86)+'px)';}},{passive:true});")
                .append("row.addEventListener('touchend',function(){content.style.transform=current<-42?'translateX(-86px)':'translateX(0)';});")
                .append("del.addEventListener('click',function(){if(!confirm('确定删除这个项目吗？'))return;fetch(del.dataset.delete,{method:'POST',headers:{'X-Requested-With':'XMLHttpRequest'}}).then(function(r){if(!r.ok)throw new Error();row.remove();}).catch(function(){alert('删除失败');content.style.transform='translateX(0)';});});});})();")
                .append("(function(){var modal=document.getElementById('imagePreview'),img=document.getElementById('previewImage'),close=document.getElementById('closePreview');document.querySelectorAll('.thumb').forEach(function(t){t.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();img.src=t.dataset.full;modal.style.display='flex';});});close&&close.addEventListener('click',function(){modal.style.display='none';img.src='';});modal&&modal.addEventListener('click',function(e){if(e.target===modal){modal.style.display='none';img.src='';}});})();")
                .append("</script>");
    }

    private void appendClientScript(StringBuilder html) {
        html.append("<script>")
                .append("(function(){if(!window.EventSource)return;var id=localStorage.getItem('bridgeClientId');if(!id){id='c_'+Math.random().toString(36).slice(2)+Date.now();localStorage.setItem('bridgeClientId',id);}")
                .append("var name=localStorage.getItem('bridgeClientName');if(!name){name=(navigator.platform||'iPhone')+' '+id.slice(-4);localStorage.setItem('bridgeClientName',name);}")
                .append("var source=new EventSource('/events?id='+encodeURIComponent(id)+'&name='+encodeURIComponent(name));")
                .append("source.addEventListener('push',function(ev){var data={};try{data=JSON.parse(ev.data||'{}');}catch(e){}var overlay=document.getElementById('pushOverlay'),text=document.getElementById('pushText'),link=document.getElementById('pushDownload'),cancel=document.getElementById('pushCancel'),fill=document.getElementById('pushFill'),pct=document.getElementById('pushPct');if(!overlay||!link)return;text.textContent='文件：'+(data.name||'未命名')+'，大小：'+(data.sizeText||'未知');fill.style.width='0%';pct.textContent='等待接收';overlay.style.display='flex';cancel.onclick=function(){overlay.style.display='none';};link.onclick=function(e){e.preventDefault();link.style.pointerEvents='none';pct.textContent='0%';var xhr=new XMLHttpRequest();xhr.open('GET',data.url,true);xhr.responseType='blob';xhr.onprogress=function(ev){if(ev.lengthComputable){var p=Math.round(ev.loaded*100/ev.total);fill.style.width=p+'%';pct.textContent=p+'%';}else{pct.textContent='正在接收...';}};xhr.onload=function(){link.style.pointerEvents='auto';if(xhr.status>=200&&xhr.status<300){fill.style.width='100%';pct.textContent='100%';var blob=xhr.response;var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=data.name||'download';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();overlay.style.display='none';},650);}else{pct.textContent='接收失败';}};xhr.onerror=function(){link.style.pointerEvents='auto';pct.textContent='接收失败';};xhr.send();};});")
                .append("})();")
                .append("</script>");
    }

    private void writeFile(OutputStream output, DocumentFile file) throws IOException {
        String name = file.getName() == null ? "download" : file.getName();
        String type = file.getType() == null ? "application/octet-stream" : file.getType();
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + file.length() + "\r\n"
                + "Content-Disposition: attachment; filename*=UTF-8''" + encode(name) + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
            if (input == null) return;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void writeRawFile(OutputStream output, DocumentFile file) throws IOException {
        String type = file.getType() == null ? "application/octet-stream" : file.getType();
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + file.length() + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        try (InputStream input = context.getContentResolver().openInputStream(file.getUri())) {
            if (input == null) return;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void writeSharedOffer(OutputStream output, SharedOffer offer) throws IOException {
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: " + offer.mime + "\r\n"
                + (offer.size >= 0 ? "Content-Length: " + offer.size + "\r\n" : "")
                + "Content-Disposition: attachment; filename*=UTF-8''" + encode(offer.name) + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        offer.started = true;
        offer.sent = 0;
        offer.completed = false;
        try (InputStream input = context.getContentResolver().openInputStream(offer.uri)) {
            if (input == null) return;
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                offer.sent += read;
            }
            offer.completed = true;
        }
    }

    public static List<ConnectedClient> connectedClients() {
        List<ConnectedClient> clients = new ArrayList<>();
        for (ClientConnection connection : CLIENTS.values()) {
            clients.add(new ConnectedClient(connection.id, connection.name, connection.lastSeen));
        }
        return clients;
    }

    public static String pushFileToClient(Context context, String clientId, Uri uri) {
        try {
            ClientConnection client = CLIENTS.get(clientId);
            if (client == null) return null;
            String id = UUID.randomUUID().toString();
            String name = displayName(context, uri);
            String mime;
            try {
                mime = context.getContentResolver().getType(uri);
            } catch (Exception ignored) {
                mime = null;
            }
            if (mime == null) mime = "application/octet-stream";
            long size = displaySize(context, uri);
            OFFERS.put(id, new SharedOffer(uri, name, mime, size));
            String url = "/shared?id=" + id;
            String data = "{\"id\":\"" + escapeJsonStatic(id)
                    + "\",\"name\":\"" + escapeJsonStatic(name)
                    + "\",\"size\":" + size
                    + ",\"sizeText\":\"" + escapeJsonStatic(formatBytesStatic(size))
                    + "\",\"url\":\"" + escapeJsonStatic(url) + "\"}";
            return client.send("push", data) ? id : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static OfferProgress offerProgress(String id) {
        SharedOffer offer = OFFERS.get(id);
        if (offer == null) return new OfferProgress(-1, 0, false, false);
        return new OfferProgress(offer.size, offer.sent, offer.started, offer.completed);
    }

    private void writeText(OutputStream output, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
    }

    private void redirect(OutputStream output, String location, String message) throws IOException {
        String body = "<html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"0;url="
                + escape(location) + "\"></head><body>" + escape(message) + "</body></html>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 303 See Other\r\n"
                + "Location: " + location + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
    }

    private DocumentFile resolve(int rootIndex, String relativePath) {
        if (rootIndex < 0 || rootIndex >= roots.size()) return null;
        DocumentFile current = roots.get(rootIndex).folder;
        if (relativePath == null || relativePath.isEmpty()) return current;
        String[] parts = relativePath.split("/");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            DocumentFile next = current.findFile(part);
            if (next == null) return null;
            current = next;
        }
        return current;
    }

    private String safePath(String path) {
        if (path == null) return "";
        String decoded = decode(path).replace('\\', '/');
        String[] parts = decoded.split("/");
        StringBuilder clean = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) continue;
            if (clean.length() > 0) clean.append('/');
            clean.append(part);
        }
        return clean.toString();
    }

    private String parentPath(String path) {
        if (path == null || path.isEmpty()) return null;
        int index = path.lastIndexOf('/');
        return index < 0 ? "" : path.substring(0, index);
    }

    private Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        if (query == null || query.isEmpty()) return values;
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals >= 0) {
                values.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
            } else {
                values.put(decode(pair), "");
            }
        }
        return values;
    }

    private String multipartFilename(String headers) {
        for (String line : headers.split("\r\n")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("content-disposition:")) continue;
            int index = lower.indexOf("filename=");
            if (index < 0) return null;
            String value = line.substring(index + 9).trim();
            if (value.startsWith("\"")) {
                int end = value.indexOf('"', 1);
                return end > 1 ? value.substring(1, end) : null;
            }
            int semicolon = value.indexOf(';');
            return semicolon >= 0 ? value.substring(0, semicolon).trim() : value;
        }
        return null;
    }

    private String sanitizeName(String name) {
        String clean = name.replace('\\', '_').replace('/', '_').trim();
        return clean.isEmpty() ? "upload.bin" : clean;
    }

    private boolean isImage(DocumentFile file) {
        String type = file.getType();
        if (type != null && type.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
        String name = file.getName();
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".png")
                || lower.endsWith(".gif")
                || lower.endsWith(".webp")
                || lower.endsWith(".bmp");
    }

    private byte[] readBytes(InputStream input, int length) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[64 * 1024];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toByteArray();
    }

    private String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        int current;
        while ((current = input.read()) != -1) {
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.UTF_8);
            }
            line.write(current);
            previous = current;
        }
        return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8.name());
    }

    private int indexOf(byte[] data, byte[] pattern, int start) {
        outer:
        for (int i = Math.max(0, start); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private int parseInt(String value) {
        return parseInt(value, -1);
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String valueAfter(String value, String marker) {
        int index = value.indexOf(marker);
        if (index < 0) return "";
        return value.substring(index + marker.length()).trim();
    }

    private String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return "";
        }
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String reason(int status) {
        if (status == 200) return "OK";
        if (status == 303) return "See Other";
        if (status == 400) return "Bad Request";
        if (status == 403) return "Forbidden";
        if (status == 404) return "Not Found";
        if (status == 405) return "Method Not Allowed";
        return "Error";
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }

    private static String formatBytesStatic(long bytes) {
        if (bytes < 0) return "未知大小";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }

    private static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "shared-file" : last;
    }

    private static long displaySize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0) return cursor.getLong(index);
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private static String escapeJsonStatic(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static class Request {
        String method;
        String path;
        Map<String, String> query = new HashMap<>();
        Map<String, String> headers = new HashMap<>();
    }

    public static class SharedRoot {
        final String name;
        final DocumentFile folder;

        public SharedRoot(String name, DocumentFile folder) {
            this.name = name;
            this.folder = folder;
        }
    }

    public static class ConnectedClient {
        public final String id;
        public final String name;
        public final long lastSeen;

        ConnectedClient(String id, String name, long lastSeen) {
            this.id = id;
            this.name = name;
            this.lastSeen = lastSeen;
        }
    }

    public static class OfferProgress {
        public final long total;
        public final long sent;
        public final boolean started;
        public final boolean completed;

        OfferProgress(long total, long sent, boolean started, boolean completed) {
            this.total = total;
            this.sent = sent;
            this.started = started;
            this.completed = completed;
        }
    }

    private static class ClientConnection {
        final String id;
        final String name;
        final OutputStream output;
        volatile long lastSeen;

        ClientConnection(String id, String name, OutputStream output) {
            this.id = id;
            this.name = name;
            this.output = output;
            this.lastSeen = System.currentTimeMillis();
        }

        synchronized boolean send(String event, String data) {
            try {
                output.write(("event: " + event + "\n").getBytes(StandardCharsets.UTF_8));
                output.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                lastSeen = System.currentTimeMillis();
                return true;
            } catch (IOException e) {
                CLIENTS.remove(id, this);
                return false;
            }
        }
    }

    private static class SharedOffer {
        final Uri uri;
        final String name;
        final String mime;
        final long size;
        volatile long sent;
        volatile boolean started;
        volatile boolean completed;

        SharedOffer(Uri uri, String name, String mime, long size) {
            this.uri = uri;
            this.name = name;
            this.mime = mime;
            this.size = size;
            this.sent = 0;
            this.started = false;
            this.completed = false;
        }
    }

    private String rootName(int rootIndex) {
        if (rootIndex < 0 || rootIndex >= roots.size()) return "共享文件夹";
        return roots.get(rootIndex).name;
    }
}
