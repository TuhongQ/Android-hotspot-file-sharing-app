package cn.local.bridgeshare;

import android.app.Activity;
import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQUEST_TREE = 4001;
    private static final int REQUEST_PUSH_FILE = 4002;
    private static final int PORT = 8080;
    private static final String PREF_FOLDERS = "folder_uris";

    private final List<Uri> folderUris = new ArrayList<>();
    private AndroidLicenseManager licenseManager;
    private LinearLayout folderListView;
    private Button licenseButton;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView addressView;
    private TextView clientView;
    private LinearLayout uploadProgressCard;
    private TextView uploadProgressTitle;
    private TextView uploadProgressDetail;
    private ProgressBar uploadProgressBar;
    private ImageView qrView;
    private Button serviceButton;
    private boolean serviceRunning;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable clientPoller = new Runnable() {
        @Override
        public void run() {
            if (serviceRunning && !licenseManager.canUse()) {
                stopServer();
                renderLicense();
            }
            renderClients();
            renderUploadProgress();
            handler.postDelayed(this, 2000);
        }
    };
    private final BroadcastReceiver serviceStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(FileShareService.EXTRA_RUNNING, false);
            String error = intent.getStringExtra(FileShareService.EXTRA_ERROR);
            serviceRunning = running;
            if (running) {
                renderRunning();
            } else if (error != null && !error.isEmpty()) {
                renderError(error);
            } else {
                renderStopped();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(color("#070A12"));
        licenseManager = new AndroidLicenseManager(this);
        loadFolders();
        buildUi();
        renderFolders();
        renderStopped();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(FileShareService.ACTION_STATE);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(serviceStateReceiver, filter, RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(serviceStateReceiver, filter);
        }
        handler.post(clientPoller);
    }

    @Override
    protected void onStop() {
        handler.removeCallbacks(clientPoller);
        unregisterReceiver(serviceStateReceiver);
        super.onStop();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(color("#070A12"));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), statusBarHeight() + dp(14), dp(16), dp(28));
        scroll.addView(page);

        LinearLayout hero = gradientCard("#111827", "#0F766E", "#7C2D12");
        hero.setPadding(dp(20), dp(22), dp(20), dp(22));
        page.addView(hero, matchWrap());

        TextView eyebrow = text("LOCAL TRANSFER COMMAND CENTER", 12, "#99F6E4", Typeface.BOLD);
        hero.addView(eyebrow, matchWrap());

        TextView title = text("闪桥共享", 34, "#FFFFFF", Typeface.BOLD);
        title.setPadding(0, dp(8), 0, 0);
        hero.addView(title, matchWrap());

        TextView intro = text("把安卓变成一台随身文件服务器。iPhone 连上热点后，扫码就能上传、下载和浏览目录。", 15, "#E5E7EB", Typeface.NORMAL);
        intro.setLineSpacing(dp(2), 1f);
        intro.setPadding(0, dp(10), 0, dp(18));
        hero.addView(intro, matchWrap());

        Button quickStart = pillButton("开始共享", "#22D3EE", "#061018");
        quickStart.setTextSize(17);
        quickStart.setOnClickListener(v -> {
            if (serviceRunning) {
                Toast.makeText(this, "服务已经启动，苹果手机可以扫码访问", Toast.LENGTH_SHORT).show();
            } else if (folderUris.isEmpty()) {
                openFolderPicker();
            } else {
                startServer();
            }
        });
        hero.addView(quickStart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout heroActions = row();
        heroActions.setGravity(Gravity.CENTER_VERTICAL);
        Button hotspotButton = pillButton("热点设置", "#FB923C", "#111827");
        hotspotButton.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)));
        heroActions.addView(hotspotButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        addGap(heroActions, 10, 1);
        serviceButton = pillButton("启动服务", "#14B8A6", "#06201C");
        serviceButton.setOnClickListener(v -> toggleServer());
        heroActions.addView(serviceButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        hero.addView(heroActions, topMargin(12));

        licenseButton = darkButton("Register");
        licenseButton.setOnClickListener(v -> showLicenseDialog());
        hero.addView(licenseButton, topMargin(10));
        renderLicense();

        LinearLayout statusCard = neonCard("#101826", "#1F2937");
        statusCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.addView(statusCard, topMargin(14));
        statusTitle = text("", 20, "#F9FAFB", Typeface.BOLD);
        statusDetail = text("", 14, "#A7F3D0", Typeface.NORMAL);
        statusDetail.setPadding(0, dp(8), 0, 0);
        statusCard.addView(statusTitle, matchWrap());
        statusCard.addView(statusDetail, matchWrap());

        uploadProgressCard = neonCard("#0B1120", "#164E63");
        uploadProgressCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        uploadProgressCard.setVisibility(View.GONE);
        page.addView(uploadProgressCard, topMargin(14));
        uploadProgressTitle = text("Receiving files", 18, "#F9FAFB", Typeface.BOLD);
        uploadProgressDetail = text("Waiting for upload...", 14, "#BAE6FD", Typeface.NORMAL);
        uploadProgressDetail.setPadding(0, dp(8), 0, dp(10));
        uploadProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        uploadProgressBar.setMax(100);
        uploadProgressCard.addView(uploadProgressTitle, matchWrap());
        uploadProgressCard.addView(uploadProgressDetail, matchWrap());
        uploadProgressCard.addView(uploadProgressBar, matchWrap());

        LinearLayout folderCard = neonCard("#0F172A", "#1E293B");
        folderCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.addView(folderCard, topMargin(14));
        folderCard.addView(sectionTitle("共享文件夹"), matchWrap());
        TextView folderHint = text("可以连续添加多个目录；启动后，网页端会显示所有共享入口。", 14, "#94A3B8", Typeface.NORMAL);
        folderHint.setLineSpacing(dp(2), 1f);
        folderHint.setPadding(0, dp(8), 0, dp(12));
        folderCard.addView(folderHint, matchWrap());

        folderListView = new LinearLayout(this);
        folderListView.setOrientation(LinearLayout.VERTICAL);
        folderCard.addView(folderListView, matchWrap());

        LinearLayout folderActions = row();
        Button addFolder = outlineButton("添加文件夹");
        addFolder.setOnClickListener(v -> openFolderPicker());
        folderActions.addView(addFolder, new LinearLayout.LayoutParams(0, dp(50), 1));
        addGap(folderActions, 10, 1);
        Button clearFolders = darkButton("清空列表");
        clearFolders.setOnClickListener(v -> {
            folderUris.clear();
            saveFolders();
            renderFolders();
            if (serviceRunning) stopServer();
        });
        folderActions.addView(clearFolders, new LinearLayout.LayoutParams(0, dp(50), 1));
        folderCard.addView(folderActions, topMargin(12));

        LinearLayout accessCard = neonCard("#101826", "#1F2937");
        accessCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.addView(accessCard, topMargin(14));
        accessCard.addView(sectionTitle("苹果手机访问"), matchWrap());
        addressView = text("服务启动后会显示访问地址。", 14, "#BAE6FD", Typeface.BOLD);
        addressView.setPadding(0, dp(8), 0, dp(12));
        accessCard.addView(addressView, matchWrap());
        qrView = new ImageView(this);
        qrView.setAdjustViewBounds(true);
        qrView.setPadding(dp(8), dp(8), dp(8), dp(8));
        qrView.setBackground(cardBackground("#F8FAFC", 16, "#22D3EE"));
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(238), dp(238));
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        accessCard.addView(qrView, qrParams);

        LinearLayout pushCard = neonCard("#0B1120", "#164E63");
        pushCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.addView(pushCard, topMargin(14));
        pushCard.addView(sectionTitle("主动发送"), matchWrap());
        clientView = text("在线设备：0", 14, "#BAE6FD", Typeface.BOLD);
        clientView.setPadding(0, dp(8), 0, dp(12));
        pushCard.addView(clientView, matchWrap());
        Button pushButton = pillButton("选择文件并发送", "#22D3EE", "#061018");
        pushButton.setTextSize(16);
        pushButton.setOnClickListener(v -> openPushFilePicker());
        pushCard.addView(pushButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout guideCard = neonCard("#13201D", "#134E4A");
        guideCard.setPadding(dp(18), dp(18), dp(18), dp(18));
        page.addView(guideCard, topMargin(14));
        guideCard.addView(sectionTitle("使用顺序"), matchWrap());
        guideCard.addView(text("添加文件夹 -> 开热点 -> 启动服务 -> iPhone 扫码访问", 15, "#CCFBF1", Typeface.BOLD), topMargin(8));

        setContentView(scroll);
    }

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_TREE);
    }

    private void openPushFilePicker() {
        if (!ensureUsable()) return;
        if (!serviceRunning) {
            Toast.makeText(this, "请先启动服务，并让对方打开网页", Toast.LENGTH_SHORT).show();
            return;
        }
        if (FileShareServer.connectedClients().isEmpty()) {
            Toast.makeText(this, "还没有在线设备，请先让苹果手机打开网页", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PUSH_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            getContentResolver().takePersistableUriPermission(uri, flags);
            if (!containsUri(uri)) folderUris.add(uri);
            saveFolders();
            renderFolders();
            Toast.makeText(this, "已添加共享文件夹", Toast.LENGTH_SHORT).show();
            if (serviceRunning) restartServer();
        }
        if (requestCode == REQUEST_PUSH_FILE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            } catch (Exception ignored) {
            }
            handler.postDelayed(() -> chooseClientAndPush(uri), 250);
        }
    }

    private void chooseClientAndPush(Uri uri) {
        try {
            List<FileShareServer.ConnectedClient> clients = FileShareServer.connectedClients();
            if (clients.isEmpty()) {
                Toast.makeText(this, "在线设备已断开", Toast.LENGTH_SHORT).show();
                return;
            }
            String[] names = new String[clients.size()];
            for (int i = 0; i < clients.size(); i++) {
                names[i] = clients.get(i).name;
            }
            new AlertDialog.Builder(this)
                    .setTitle("选择接收设备")
                    .setItems(names, (dialog, which) -> {
                        try {
                            FileShareServer.ConnectedClient client = clients.get(which);
                            Toast.makeText(this, "正在发送接收提示...", Toast.LENGTH_SHORT).show();
                            showPushProgress(client, uri);
                        } catch (Exception e) {
                            Toast.makeText(this, "发送失败：" + safeMessage(e), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
        } catch (Exception e) {
            Toast.makeText(this, "无法发送：" + safeMessage(e), Toast.LENGTH_LONG).show();
        }
    }

    private void showPushProgress(FileShareServer.ConnectedClient client, Uri uri) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, pad);
        TextView label = text("正在通知 " + client.name + " 接收文件...", 15, "#111827", Typeface.BOLD);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        TextView percent = text("等待对方点击接收", 13, "#475569", Typeface.NORMAL);
        percent.setPadding(0, dp(10), 0, 0);
        box.addView(label, matchWrap());
        box.addView(progress, topMargin(14));
        box.addView(percent, matchWrap());
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("发送进度")
                .setView(box)
                .setNegativeButton("后台等待", null)
                .create();
        progressDialog.setOnShowListener(dialog -> {
            progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> progressDialog.dismiss());
        });
        progressDialog.show();

        new Thread(() -> {
            String offerId = FileShareServer.pushFileToClient(this, client.id, uri);
            if (offerId == null) {
                handler.post(() -> {
                    percent.setText("发送失败，设备可能已断开");
                    Toast.makeText(this, "发送失败，设备可能已断开", Toast.LENGTH_SHORT).show();
                });
                return;
            }
            handler.post(() -> {
                label.setText("已通知 " + client.name);
                percent.setText("等待对方点击接收");
                renderClients();
            });
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 10 * 60 * 1000) {
                FileShareServer.OfferProgress p = FileShareServer.offerProgress(offerId);
                int value;
                String textValue;
                if (!p.started) {
                    value = 0;
                    textValue = "等待对方点击接收";
                } else if (p.total > 0) {
                    value = Math.min(100, Math.round(p.sent * 100f / p.total));
                    textValue = p.completed ? "发送完成" : "正在发送 " + value + "%";
                } else {
                    value = p.completed ? 100 : 50;
                    textValue = p.completed ? "发送完成" : "正在发送...";
                }
                int finalValue = value;
                String finalTextValue = textValue;
                handler.post(() -> {
                    progress.setProgress(finalValue);
                    percent.setText(finalTextValue);
                });
                if (p.completed) {
                    handler.postDelayed(progressDialog::dismiss, 700);
                    break;
                }
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }).start();
    }

    private void toggleServer() {
        if (serviceRunning) {
            stopServer();
        } else {
            startServer();
        }
    }

    private void restartServer() {
        stopServer();
        startServer();
    }

    private void startServer() {
        if (!ensureUsable()) return;
        List<FileShareServer.SharedRoot> roots = resolveSharedRoots();
        if (roots.isEmpty()) {
            Toast.makeText(this, "请先添加可读写的共享文件夹", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, FileShareService.class);
        intent.setAction(FileShareService.ACTION_START);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        serviceRunning = true;
        renderRunning();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 5002);
        }
    }

    private void stopServer() {
        Intent intent = new Intent(this, FileShareService.class);
        intent.setAction(FileShareService.ACTION_STOP);
        startService(intent);
        serviceRunning = false;
        if (statusTitle != null) renderStopped();
    }

    private List<FileShareServer.SharedRoot> resolveSharedRoots() {
        List<FileShareServer.SharedRoot> roots = new ArrayList<>();
        for (Uri uri : folderUris) {
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            if (folder != null && folder.canRead() && folder.canWrite()) {
                String name = folder.getName() == null ? "共享文件夹 " + (roots.size() + 1) : folder.getName();
                roots.add(new FileShareServer.SharedRoot(name, folder));
            }
        }
        return roots;
    }

    private void renderRunning() {
        serviceRunning = true;
        serviceButton.setText("停止服务");
        statusTitle.setText("服务运行中");
        List<String> urls = lanUrls();
        if (urls.isEmpty()) {
            statusDetail.setText("暂未检测到局域网 IP。请确认热点或 Wi-Fi 已开启。");
            addressView.setText("开启热点后，这里会出现访问地址。");
            qrView.setImageDrawable(null);
            return;
        }
        StringBuilder builder = new StringBuilder();
        for (String url : urls) builder.append(url).append('\n');
        statusDetail.setText("iPhone 连接安卓热点后，打开下方地址。");
        addressView.setText(builder.toString().trim());
        try {
            qrView.setImageBitmap(makeQr(urls.get(0)));
        } catch (WriterException e) {
            qrView.setImageDrawable(null);
        }
    }

    private void renderStopped() {
        serviceRunning = false;
        serviceButton.setText("启动服务");
        statusTitle.setText("服务未启动");
        statusDetail.setText("添加共享文件夹后启动服务，苹果设备才能访问。");
        addressView.setText("服务启动后会显示访问地址。");
        qrView.setImageDrawable(null);
    }

    private void renderError(String error) {
        serviceRunning = false;
        serviceButton.setText("启动服务");
        statusTitle.setText("启动失败");
        statusDetail.setText(error);
        addressView.setText("请检查共享文件夹、热点或端口占用。");
        qrView.setImageDrawable(null);
    }

    private void renderClients() {
        if (clientView == null) return;
        List<FileShareServer.ConnectedClient> clients = FileShareServer.connectedClients();
        if (clients.isEmpty()) {
            clientView.setText("在线设备：0\n让苹果手机打开网页后，会出现在这里。");
            return;
        }
        StringBuilder builder = new StringBuilder("在线设备：").append(clients.size());
        for (FileShareServer.ConnectedClient client : clients) {
            builder.append("\n").append(client.name);
        }
        clientView.setText(builder.toString());
    }

    private void renderUploadProgress() {
        if (uploadProgressCard == null) return;
        FileShareServer.UploadSnapshot snapshot = FileShareServer.uploadProgress();
        if (!snapshot.active && !snapshot.completed) {
            uploadProgressCard.setVisibility(View.GONE);
            return;
        }
        uploadProgressCard.setVisibility(View.VISIBLE);
        if (snapshot.completed) {
            uploadProgressBar.setProgress(100);
            uploadProgressTitle.setText("Upload completed");
            uploadProgressDetail.setText("Saved " + snapshot.saved + " file" + (snapshot.saved == 1 ? "" : "s") + " from the web page.");
            return;
        }
        int percent = snapshot.total > 0 ? Math.min(100, Math.round(snapshot.received * 100f / snapshot.total)) : 0;
        uploadProgressBar.setProgress(percent);
        uploadProgressTitle.setText("Receiving upload");
        uploadProgressDetail.setText(percent + "% · " + formatBytes(snapshot.received) + " / " + formatBytes(snapshot.total));
    }

    private boolean ensureUsable() {
        if (licenseManager.canUse()) return true;
        Toast.makeText(this, licenseManager.trialExpired() ? "Trial expired. Please activate Bridge Share." : "Start a trial or activate Bridge Share first.", Toast.LENGTH_LONG).show();
        renderLicense();
        return false;
    }

    private void renderLicense() {
        if (licenseButton == null) return;
        licenseButton.setText(licenseManager.compactStatusText());
    }

    private void showLicenseDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        box.setPadding(pad, pad, pad, pad);
        TextView status = text(licenseManager.statusText(), 14, "#111827", Typeface.BOLD);
        TextView machine = text("Machine code\n" + licenseManager.machineCode(), 13, "#334155", Typeface.BOLD);
        machine.setPadding(0, dp(12), 0, dp(8));
        EditText input = new EditText(this);
        input.setHint("Paste registration code");
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setVisibility(licenseManager.isLicensed() ? View.GONE : View.VISIBLE);
        box.addView(status, matchWrap());
        box.addView(machine, matchWrap());
        box.addView(input, matchWrap());

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("License")
                .setView(box)
                .setPositiveButton("Activate", null)
                .setNeutralButton("Copy code", null)
                .setNegativeButton("Close", null)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (activateLicense(input.getText().toString())) dialog.dismiss();
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("Bridge Share machine code", licenseManager.machineCode()));
                Toast.makeText(this, "Machine code copied", Toast.LENGTH_SHORT).show();
            });
            if (licenseManager.isLicensed()) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("Remove");
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    licenseManager.clearLicense();
                    Toast.makeText(this, "Registration removed", Toast.LENGTH_SHORT).show();
                    renderLicense();
                    dialog.dismiss();
                });
            } else if (licenseManager.canStartTrial()) {
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("Start trial");
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                    startTrial();
                    dialog.dismiss();
                });
            }
        });
        dialog.show();
    }

    private boolean activateLicense(String code) {
        if (licenseManager.activate(code)) {
            Toast.makeText(this, "Activated", Toast.LENGTH_SHORT).show();
            renderLicense();
            return true;
        } else {
            Toast.makeText(this, licenseManager.lastReason(), Toast.LENGTH_LONG).show();
            renderLicense();
            return false;
        }
    }

    private void startTrial() {
        if (!licenseManager.canStartTrial()) {
            Toast.makeText(this, "Trial has already been used on this device.", Toast.LENGTH_LONG).show();
            renderLicense();
            return;
        }
        licenseManager.startTrial();
        Toast.makeText(this, "7-day trial started", Toast.LENGTH_SHORT).show();
        renderLicense();
    }

    private void renderFolders() {
        if (folderListView == null) return;
        folderListView.removeAllViews();
        if (folderUris.isEmpty()) {
            TextView empty = text("还没有共享文件夹，点击上方“开始共享”或“添加文件夹”。", 14, "#94A3B8", Typeface.NORMAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(12), dp(18), dp(12), dp(18));
            empty.setBackground(cardBackground("#111827", 14, "#334155"));
            folderListView.addView(empty, matchWrap());
            return;
        }
        for (int i = 0; i < folderUris.size(); i++) {
            Uri uri = folderUris.get(i);
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            String name = folder != null && folder.getName() != null ? folder.getName() : "共享文件夹 " + (i + 1);
            LinearLayout item = row();
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(12), dp(10), dp(12));
            item.setBackground(cardBackground("#111827", 14, "#334155"));
            TextView info = text(name + "\n" + uri.toString(), 13, "#E5E7EB", Typeface.NORMAL);
            info.setMaxLines(3);
            item.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button remove = miniButton("移除");
            final int index = i;
            remove.setOnClickListener(v -> {
                folderUris.remove(index);
                saveFolders();
                renderFolders();
                if (serviceRunning) restartServer();
            });
            item.addView(remove, new LinearLayout.LayoutParams(dp(72), dp(38)));
            LinearLayout.LayoutParams params = matchWrap();
            params.setMargins(0, i == 0 ? 0 : dp(8), 0, 0);
            folderListView.addView(item, params);
        }
    }

    private void loadFolders() {
        SharedPreferences preferences = getPreferences(MODE_PRIVATE);
        String value = preferences.getString(PREF_FOLDERS, "");
        if (value == null || value.trim().isEmpty()) return;
        for (String line : value.split("\n")) {
            if (!line.trim().isEmpty()) folderUris.add(Uri.parse(line.trim()));
        }
    }

    private void saveFolders() {
        StringBuilder builder = new StringBuilder();
        for (Uri uri : folderUris) builder.append(uri.toString()).append('\n');
        getPreferences(MODE_PRIVATE).edit().putString(PREF_FOLDERS, builder.toString()).apply();
    }

    private boolean containsUri(Uri uri) {
        for (Uri saved : folderUris) {
            if (saved.toString().equals(uri.toString())) return true;
        }
        return false;
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? "请重新选择文件" : message;
    }

    private String formatBytes(long bytes) {
        if (bytes < 0) return "unknown";
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int index = 0;
        while (value >= 1024 && index < units.length - 1) {
            value /= 1024;
            index++;
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[index]);
    }

    private List<String> lanUrls() {
        List<String> urls = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback()) continue;
                for (java.net.InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        urls.add("http://" + address.getHostAddress() + ":" + PORT + "/");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return urls;
    }

    private Bitmap makeQr(String value) throws WriterException {
        int size = dp(222);
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private LinearLayout card(String color) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(cardBackground(color, 18, "transparent"));
        return layout;
    }

    private LinearLayout neonCard(String fill, String stroke) {
        LinearLayout layout = card(fill);
        layout.setBackground(cardBackground(fill, 18, stroke));
        return layout;
    }

    private LinearLayout gradientCard(String start, String center, String end) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{color(start), color(center), color(end)});
        drawable.setCornerRadius(dp(22));
        layout.setBackground(drawable);
        return layout;
    }

    private GradientDrawable cardBackground(String fill, int radiusDp, String stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color(fill));
        drawable.setCornerRadius(dp(radiusDp));
        if (!"transparent".equals(stroke)) drawable.setStroke(dp(1), color(stroke));
        return drawable;
    }

    private TextView sectionTitle(String value) {
        return text(value, 18, "#F9FAFB", Typeface.BOLD);
    }

    private TextView text(String value, int sp, String color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color(color));
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private Button pillButton(String text, String fill, String textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(color(textColor));
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setBackground(cardBackground(fill, 14, "transparent"));
        return button;
    }

    private Button outlineButton(String text) {
        Button button = pillButton(text, "#22D3EE", "#061018");
        button.setBackground(cardBackground("#22D3EE", 14, "transparent"));
        return button;
    }

    private Button darkButton(String text) {
        Button button = pillButton(text, "#111827", "#E5E7EB");
        button.setBackground(cardBackground("#111827", 14, "#334155"));
        return button;
    }

    private Button miniButton(String text) {
        Button button = pillButton(text, "#EEF2FF", "#3730A3");
        button.setTextSize(13);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private void addGap(LinearLayout row, int widthDp, int heightDp) {
        View gap = new View(this);
        row.addView(gap, new LinearLayout.LayoutParams(dp(widthDp), dp(heightDp)));
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams topMargin(int marginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(marginDp), 0, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int statusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) return getResources().getDimensionPixelSize(resourceId);
        return dp(24);
    }

    private int color(String value) {
        return Color.parseColor(value);
    }
}
