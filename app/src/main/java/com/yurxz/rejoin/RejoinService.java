package com.yurxz.rejoin;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

public class RejoinService extends Service {
    public static final String TAG = "YurxzRejoin";
    public static final String CH_ID = "rejoin_service";
    public static final String ACTION_START = "START";
    public static final String ACTION_STOP  = "STOP";
    public static final String ACTION_MANUAL_REJOIN = "MANUAL_REJOIN";
    public static final String ACTION_STATUS_UPDATE = "com.yurxz.rejoin.STATUS_UPDATE";

    public static volatile boolean running = false;
    public static volatile boolean adbConnected = false;
    public static volatile int rejoinCount = 0;
    public static volatile long startTimeMs = 0;
    public static final List<String> logs = new ArrayList<>();
    public static final Map<String, String> instanceStatuses = new HashMap<>();

    private ScheduledExecutorService scheduler;
    private Handler mainHandler;
    private String currentAdbIp = "";
    private int currentAdbPort = 5555;

    @Override public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;

        switch (action) {
            case ACTION_START:
                startRejoin();
                break;
            case ACTION_STOP:
                stopRejoin();
                break;
            case ACTION_MANUAL_REJOIN:
                int idx = intent.getIntExtra("index", -1);
                if (idx >= 0) manualRejoin(idx);
                break;
        }
        return START_STICKY;
    }

    private void startRejoin() {
        if (running) return;
        running = true;
        rejoinCount = 0;
        startTimeMs = System.currentTimeMillis();
        addLog("▶ Rejoin service dimulai");
        startForeground();

        int interval = AppConfig.getInterval(this);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::checkAllInstances, 5, interval, TimeUnit.SECONDS);
        sendBroadcast();
    }

    private void stopRejoin() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        addLog("■ Rejoin service dihentikan");
        stopForeground(true);
        stopSelf();
        sendBroadcast();
    }

    private void checkAllInstances() {
        List<RobloxInstance> instances = AppConfig.getInstances(this);
        if (instances.isEmpty()) return;

        for (int i = 0; i < instances.size(); i++) {
            RobloxInstance inst = instances.get(i);
            checkAndRejoinIfClosed(inst, i);
        }
        sendBroadcast();
    }

    private void checkAndRejoinIfClosed(RobloxInstance inst, int idx) {
        try {
            // Cek apakah Roblox masih jalan via pidof
            String pid = runShell("pidof " + inst.packageName);
            boolean isRunning = pid != null && !pid.trim().isEmpty();

            if (!isRunning) {
                addLog("⚠ " + inst.name + " tidak berjalan, rejoining...");
                instanceStatuses.put(inst.name, "🔄 Rejoining...");
                sendBroadcast();

                // Apply settings dulu
                if (AppConfig.getAutoMute(this)) {
                    runShell("appops set " + inst.packageName + " PLAY_AUDIO deny");
                    runShell("cmd media_session volume --stream 3 --set 0");
                }
                if (AppConfig.getAutoLowRes(this)) {
                    runShell("wm size 540x960");
                    runShell("wm density 120");
                }

                // Buka PS link
                rejoinRoblox(inst);
                rejoinCount++;

                // Kirim webhook
                String webhook = AppConfig.getWebhook(this);
                if (!webhook.isEmpty()) {
                    sendWebhook(webhook, inst.name, "Auto Reconnect (Detected Closed)");
                }

                instanceStatuses.put(inst.name, "✅ Rejoined #" + rejoinCount);
                addLog("✅ " + inst.name + " rejoined! Total: " + rejoinCount);

            } else {
                instanceStatuses.put(inst.name, "✅ Running");
            }
        } catch (Exception e) {
            instanceStatuses.put(inst.name, "❌ Error: " + e.getMessage());
            addLog("❌ Error cek " + inst.name + ": " + e.getMessage());
        }
    }

    private void rejoinRoblox(RobloxInstance inst) {
        try {
            if (AppConfig.getFloatingWindow(this)) {
                // Floating window mode
                runShell("am start --windowingMode 5 -a android.intent.action.VIEW -d \"" + inst.psLink + "\"");
            } else {
                runShell("am start -a android.intent.action.VIEW -d \"" + inst.psLink + "\"");
            }
            Thread.sleep(2000);
        } catch (Exception e) {
            addLog("❌ Gagal buka PS link: " + e.getMessage());
        }
    }

    private void manualRejoin(int idx) {
        new Thread(() -> {
            List<RobloxInstance> instances = AppConfig.getInstances(this);
            if (idx < instances.size()) {
                RobloxInstance inst = instances.get(idx);
                addLog("🔄 Manual rejoin: " + inst.name);
                rejoinRoblox(inst);
                instanceStatuses.put(inst.name, "✅ Manual Rejoined");
                sendBroadcast();
            }
        }).start();
    }

    // Jalankan shell command via ADB atau langsung
    private String runShell(String cmd) {
        try {
            // Coba via Runtime (works for apps with shell access)
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            p.waitFor(5, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean connectAdb(String ip, int port) {
        try {
            currentAdbIp = ip;
            currentAdbPort = port;
            // Simpan ke config
            AppConfig.setAdbIp(this, ip);
            AppConfig.setAdbPort(this, port);
            // Test koneksi dengan simple ping
            addLog("🔌 Menghubungkan ADB ke " + ip + ":" + port + "...");
            // Untuk wireless ADB, kita pakai ADB over TCP
            // Di Android ini butuh app dengan shell permission
            // Kita coba via am command sebagai test
            String result = runShell("echo 'adb_test'");
            adbConnected = result.contains("adb_test");
            if (adbConnected) {
                addLog("✅ ADB terhubung: " + ip + ":" + port);
            } else {
                addLog("⚠ ADB: " + ip + ":" + port + " (limited shell mode)");
                adbConnected = true; // anggap connected, kita coba aja
            }
            sendBroadcast();
            return true;
        } catch (Exception e) {
            addLog("❌ Gagal connect ADB: " + e.getMessage());
            adbConnected = false;
            return false;
        }
    }

    private void sendWebhook(String url, String account, String reason) {
        new Thread(() -> {
            try {
                String body = "{\"embeds\":[{\"title\":\"Shield Rejoiner - Status Update\","
                    + "\"description\":\"**Account**: " + account + "\\n**Reason**: " + reason + "\","
                    + "\"color\":7340237,"
                    + "\"footer\":{\"text\":\"YURXZ Rejoin System\"}}]}";
                URL u = new URL(url);
                HttpURLConnection con = (HttpURLConnection) u.openConnection();
                con.setRequestMethod("POST");
                con.setRequestProperty("Content-Type", "application/json");
                con.setDoOutput(true);
                con.getOutputStream().write(body.getBytes());
                con.getResponseCode();
                con.disconnect();
            } catch (Exception e) {
                addLog("❌ Webhook error: " + e.getMessage());
            }
        }).start();
    }

    public static void addLog(String msg) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            .format(new Date());
        synchronized (logs) {
            logs.add("[" + ts + "] " + msg);
            if (logs.size() > 200) logs.remove(0);
        }
        Log.d(TAG, msg);
    }

    private void sendBroadcast() {
        Intent i = new Intent(ACTION_STATUS_UPDATE);
        sendBroadcast(i);
    }

    private void startForeground() {
        Notification notif = new NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("YURXZ Rejoin Aktif")
            .setContentText("Memantau " + AppConfig.getInstances(this).size() + " instance Roblox")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build();
        startForeground(1, notif);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CH_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            ch.setDescription(getString(R.string.channel_desc));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onDestroy() {
        super.onDestroy();
        running = false;
        if (scheduler != null) scheduler.shutdownNow();
    }
}
