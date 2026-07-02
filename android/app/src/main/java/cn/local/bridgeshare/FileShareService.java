package cn.local.bridgeshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.documentfile.provider.DocumentFile;

import java.util.ArrayList;
import java.util.List;

public class FileShareService extends Service {
    public static final String ACTION_START = "cn.local.bridgeshare.START";
    public static final String ACTION_STOP = "cn.local.bridgeshare.STOP";
    public static final String ACTION_STATE = "cn.local.bridgeshare.STATE";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_ERROR = "error";
    public static final String PREFS_NAME = "MainActivity";
    public static final String PREF_FOLDERS = "folder_uris";
    private static final String CHANNEL_ID = "share_service";
    private static final int NOTIFICATION_ID = 88;
    private static final int PORT = 8080;

    private FileShareServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSharing();
            stopSelf();
            return START_NOT_STICKY;
        }
        startSharing();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopSharing();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startSharing() {
        if (server != null) {
            broadcastState(true, null);
            return;
        }
        try {
            createChannel();
            startForeground(NOTIFICATION_ID, buildNotification("文件服务运行中"));
            acquireLocks();
            List<FileShareServer.SharedRoot> roots = loadRoots();
            if (roots.isEmpty()) throw new IllegalStateException("没有可共享的文件夹");
            server = new FileShareServer(this, roots, PORT);
            server.start();
            broadcastState(true, null);
        } catch (Exception e) {
            stopSharing();
            broadcastState(false, e.getMessage() == null ? "启动失败" : e.getMessage());
            stopSelf();
        }
    }

    private void stopSharing() {
        if (server != null) {
            server.stop();
            server = null;
        }
        releaseLocks();
        stopForeground(true);
        broadcastState(false, null);
    }

    private List<FileShareServer.SharedRoot> loadRoots() {
        List<FileShareServer.SharedRoot> roots = new ArrayList<>();
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String value = preferences.getString(PREF_FOLDERS, "");
        if (value == null || value.trim().isEmpty()) return roots;
        for (String line : value.split("\n")) {
            if (line.trim().isEmpty()) continue;
            Uri uri = Uri.parse(line.trim());
            DocumentFile folder = DocumentFile.fromTreeUri(this, uri);
            if (folder != null && folder.canRead() && folder.canWrite()) {
                String name = folder.getName() == null ? "共享文件夹 " + (roots.size() + 1) : folder.getName();
                roots.add(new FileShareServer.SharedRoot(name, folder));
            }
        }
        return roots;
    }

    private void acquireLocks() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BridgeShare:TransferWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BridgeShare:WifiLock");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        wifiLock = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, FileShareService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("闪桥共享")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", stopPending)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "文件共享服务",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持局域网文件共享在息屏时继续运行");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private void broadcastState(boolean running, String error) {
        Intent intent = new Intent(ACTION_STATE);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, running);
        if (error != null) intent.putExtra(EXTRA_ERROR, error);
        sendBroadcast(intent);
    }
}
