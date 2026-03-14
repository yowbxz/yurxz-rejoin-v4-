package com.yurxz.rejoin;

import android.app.AlertDialog;
import android.content.*;
import android.os.*;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerInstances;
    private InstanceAdapter adapter;
    private List<RobloxInstance> instances;
    private View emptyState;
    private MaterialButton btnStartStop, btnConnectAdb, btnAddAccount;
    private TextInputEditText etAdbIp, etAdbPort;
    private TextView tvAdbStatus, tvServiceStatus, tvStatAccounts, tvStatRejoin, tvStatUptime;
    private View dotAdb;
    private Timer uptimeTimer;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent i) {
            updateUI();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();
        loadInstances();
        updateUI();

        // Restore ADB IP
        String savedIp = AppConfig.getAdbIp(this);
        if (!savedIp.isEmpty()) etAdbIp.setText(savedIp);
        etAdbPort.setText(String.valueOf(AppConfig.getAdbPort(this)));
    }

    private void initViews() {
        recyclerInstances = findViewById(R.id.recyclerInstances);
        emptyState = findViewById(R.id.emptyState);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnConnectAdb = findViewById(R.id.btnConnectAdb);
        btnAddAccount = findViewById(R.id.btnAddAccount);
        etAdbIp = findViewById(R.id.etAdbIp);
        etAdbPort = findViewById(R.id.etAdbPort);
        tvAdbStatus = findViewById(R.id.tvAdbStatus);
        tvServiceStatus = findViewById(R.id.tvServiceStatus);
        tvStatAccounts = findViewById(R.id.tvStatAccounts);
        tvStatRejoin = findViewById(R.id.tvStatRejoin);
        tvStatUptime = findViewById(R.id.tvStatUptime);
        dotAdb = findViewById(R.id.dotAdb);

        recyclerInstances.setLayoutManager(new LinearLayoutManager(this));
        recyclerInstances.setNestedScrollingEnabled(false);

        btnStartStop.setOnClickListener(v -> toggleService());
        btnConnectAdb.setOnClickListener(v -> connectAdb());
        btnAddAccount.setOnClickListener(v -> showAddDialog());
        findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));
    }

    private void loadInstances() {
        instances = AppConfig.getInstances(this);
        adapter = new InstanceAdapter(instances, new InstanceAdapter.Listener() {
            @Override public void onDelete(int pos) { deleteInstance(pos); }
            @Override public void onManualRejoin(int pos) { manualRejoin(pos); }
            @Override public void onClone(int pos) { cloneInstance(pos); }
        });
        recyclerInstances.setAdapter(adapter);
        updateEmptyState();
        tvStatAccounts.setText(String.valueOf(instances.size()));
    }

    private void updateEmptyState() {
        boolean empty = instances.isEmpty();
        emptyState.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerInstances.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void updateUI() {
        runOnUiThread(() -> {
            boolean running = RejoinService.running;
            boolean adbOk = RejoinService.adbConnected;

            // Start/stop button
            btnStartStop.setText(running ? "■  STOP REJOIN" : "▶  START REJOIN");
            if (running) {
                btnStartStop.setBackgroundColor(0xFFEF4444);
            } else {
                btnStartStop.setBackgroundColor(0xFF7C3AED);
            }

            // Service status
            tvServiceStatus.setText(running ? "AKTIF" : "TIDAK AKTIF");
            tvServiceStatus.setTextColor(running ? 0xFF22C55E : 0xFF6B6888);

            // ADB dot
            dotAdb.setBackgroundResource(adbOk ? R.drawable.dot_green : R.drawable.dot_grey);
            tvAdbStatus.setText(adbOk ? "ADB ✓" : "ADB");
            tvAdbStatus.setTextColor(adbOk ? 0xFF22C55E : 0xFF6B6888);

            // Stats
            tvStatRejoin.setText(String.valueOf(RejoinService.rejoinCount));

            // Update status di adapter
            for (RobloxInstance inst : instances) {
                String status = RejoinService.instanceStatuses.get(inst.name);
                if (status != null) inst.status = status;
            }
            adapter.notifyDataSetChanged();
        });
    }

    private void toggleService() {
        if (RejoinService.running) {
            Intent i = new Intent(this, RejoinService.class);
            i.setAction(RejoinService.ACTION_STOP);
            startService(i);
        } else {
            if (instances.isEmpty()) {
                Toast.makeText(this, "Tambahkan akun dulu!", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent i = new Intent(this, RejoinService.class);
            i.setAction(RejoinService.ACTION_START);
            startService(i);
            startUptimeTimer();
        }
        updateUI();
    }

    private void connectAdb() {
        String ip = etAdbIp.getText() != null ? etAdbIp.getText().toString().trim() : "";
        String portStr = etAdbPort.getText() != null ? etAdbPort.getText().toString().trim() : "5555";
        if (ip.isEmpty()) {
            Toast.makeText(this, "Masukkan IP Address!", Toast.LENGTH_SHORT).show();
            return;
        }
        int port = 5555;
        try { port = Integer.parseInt(portStr); } catch (Exception ignored) {}

        btnConnectAdb.setText("⏳ Connecting...");
        btnConnectAdb.setEnabled(false);

        final int finalPort = port;
        new Thread(() -> {
            // Start service dulu kalau belum
            Intent i = new Intent(this, RejoinService.class);
            i.setAction(RejoinService.ACTION_START);
            // Tidak start service, hanya connect ADB

            boolean ok = connectAdbDirect(ip, finalPort);
            runOnUiThread(() -> {
                btnConnectAdb.setText(ok ? "✓ ADB Connected!" : "✗ ADB Failed");
                btnConnectAdb.setEnabled(true);
                new Handler().postDelayed(() -> {
                    btnConnectAdb.setText("🔗  Connect ADB");
                }, 2000);
                updateUI();
            });
        }).start();
    }

    private boolean connectAdbDirect(String ip, int port) {
        try {
            AppConfig.setAdbIp(this, ip);
            AppConfig.setAdbPort(this, port);
            RejoinService.addLog("🔌 Menghubungkan ke " + ip + ":" + port);
            // Test shell
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "echo connected"});
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            RejoinService.adbConnected = true;
            RejoinService.addLog("✅ Shell aktif - ADB target: " + ip + ":" + port);
            return true;
        } catch (Exception e) {
            RejoinService.addLog("❌ Error: " + e.getMessage());
            RejoinService.adbConnected = false;
            return false;
        }
    }

    private void showAddDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_add_account, null);
        TextInputEditText etName = dialogView.findViewById(R.id.etName);
        TextInputEditText etPsLink = dialogView.findViewById(R.id.etPsLink);
        TextInputEditText etPackage = dialogView.findViewById(R.id.etPackage);

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(dialogView)
            .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        dialogView.findViewById(R.id.btnCancel).setOnClickListener(v -> dialog.dismiss());
        dialogView.findViewById(R.id.btnSave).setOnClickListener(v -> {
            String name = etName.getText() != null ? etName.getText().toString().trim() : "";
            String link = etPsLink.getText() != null ? etPsLink.getText().toString().trim() : "";
            String pkg  = etPackage.getText() != null ? etPackage.getText().toString().trim() : "com.roblox.client";

            if (name.isEmpty() || link.isEmpty()) {
                Toast.makeText(this, "Nama dan PS Link wajib diisi!", Toast.LENGTH_SHORT).show();
                return;
            }
            instances.add(new RobloxInstance(name, link, pkg));
            AppConfig.saveInstances(this, instances);
            adapter.notifyItemInserted(instances.size() - 1);
            tvStatAccounts.setText(String.valueOf(instances.size()));
            updateEmptyState();
            dialog.dismiss();
            Toast.makeText(this, "✅ Akun " + name + " ditambahkan!", Toast.LENGTH_SHORT).show();
        });
        dialog.show();
    }

    private void deleteInstance(int pos) {
        new AlertDialog.Builder(this)
            .setTitle("Hapus Akun?")
            .setMessage("Yakin hapus " + instances.get(pos).name + "?")
            .setPositiveButton("Hapus", (d, w) -> {
                instances.remove(pos);
                AppConfig.saveInstances(this, instances);
                adapter.notifyItemRemoved(pos);
                tvStatAccounts.setText(String.valueOf(instances.size()));
                updateEmptyState();
            })
            .setNegativeButton("Batal", null)
            .show();
    }

    private void manualRejoin(int pos) {
        RobloxInstance inst = instances.get(pos);
        Toast.makeText(this, "🔄 Manual rejoin: " + inst.name, Toast.LENGTH_SHORT).show();
        Intent i = new Intent(this, RejoinService.class);
        i.setAction(RejoinService.ACTION_MANUAL_REJOIN);
        i.putExtra("index", pos);
        startService(i);
    }

    private void cloneInstance(int pos) {
        RobloxInstance original = instances.get(pos);
        RobloxInstance clone = new RobloxInstance(
            original.name + " (Copy)", original.psLink, original.packageName);
        instances.add(clone);
        AppConfig.saveInstances(this, instances);
        adapter.notifyItemInserted(instances.size() - 1);
        tvStatAccounts.setText(String.valueOf(instances.size()));
        updateEmptyState();
        Toast.makeText(this, "📋 Cloned: " + clone.name, Toast.LENGTH_SHORT).show();
    }

    private void startUptimeTimer() {
        if (uptimeTimer != null) uptimeTimer.cancel();
        uptimeTimer = new Timer();
        uptimeTimer.scheduleAtFixedRate(new TimerTask() {
            @Override public void run() {
                if (!RejoinService.running) { cancel(); return; }
                long sec = (System.currentTimeMillis() - RejoinService.startTimeMs) / 1000;
                String uptime = String.format("%02d:%02d:%02d", sec/3600, (sec%3600)/60, sec%60);
                runOnUiThread(() -> tvStatUptime.setText(uptime));
            }
        }, 1000, 1000);
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(statusReceiver,
            new IntentFilter(RejoinService.ACTION_STATUS_UPDATE));
        loadInstances();
        updateUI();
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(statusReceiver); } catch (Exception ignored) {}
    }
}
