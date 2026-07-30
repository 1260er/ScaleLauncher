package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Collections;

public final class ScaleScanService extends Service {
    public static final String ACTION_STOP = "de.pritcloud.scalelauncher.STOP";
    public static final String ACTION_TEST_OPEN = "de.pritcloud.scalelauncher.TEST_OPEN";
    private static final String CHANNEL_MONITOR = "scale_monitor_v3";
    private static final String CHANNEL_DETECTED = "scale_detected_v3";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final int NOTIFICATION_DETECTED = 11;
    private static final long BASELINE_LEARNING_MS = 8_000L;
    private static final long TRIGGER_COOLDOWN_MS = 25_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanRunning;
    private long startedAt;
    private long lastTriggerAt = -1L;
    private String baselineSignature;
    private String lastLoggedSignature;
    private int baselineCount;

    @Override public void onCreate() {
        super.onCreate();
        startedAt = SystemClock.elapsedRealtime();
        createChannels();
        startForeground(NOTIFICATION_MONITOR, monitorNotification("BLE-Analyse wird gestartet …"));
        EventLog.add(this, "Dienst gestartet – BLE-Paketanalyse 2.0");
        EventLog.add(this, "Bitte zunächst 10 Sekunden nicht auf die Waage stellen, danach einmal wiegen");
        startScan();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if (intent != null && ACTION_TEST_OPEN.equals(intent.getAction())) launchOpenScale(false);
        return START_STICKY;
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "Bluetooth-Scan-Berechtigung fehlt"); stopSelf(); return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) { EventLog.add(this, "Bluetooth nicht verfügbar oder ausgeschaltet"); stopSelf(); return; }
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        if (mac == null || mac.isBlank()) { EventLog.add(this, "Keine Waage ausgewählt"); stopSelf(); return; }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) { EventLog.add(this, "Bluetooth-Scanner nicht verfügbar"); stopSelf(); return; }

        callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                if (result.getDevice() != null && mac.equalsIgnoreCase(result.getDevice().getAddress())) analyze(result);
            }
            @Override public void onScanFailed(int code) { EventLog.add(ScaleScanService.this, "BLE-Scan fehlgeschlagen: " + code); }
        };
        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanRunning = true;
            EventLog.add(this, "BLE-Scan aktiv für " + mac);
            updateMonitor("Analysiere BLE-Werbepakete");
        } catch (RuntimeException e) { EventLog.add(this, "Scanstart fehlgeschlagen: " + e.getClass().getSimpleName()); stopSelf(); }
    }

    private void analyze(ScanResult result) {
        long now = SystemClock.elapsedRealtime();
        BlePacket packet = BlePacket.from(result);

        if (baselineSignature == null) {
            baselineSignature = packet.signature;
            baselineCount = 1;
            lastLoggedSignature = packet.signature;
            EventLog.add(this, "Erstes BLE-Muster " + packet.signature + "\n" + packet.details);
            return;
        }

        if (packet.signature.equals(baselineSignature)) baselineCount++;

        if (!packet.signature.equals(lastLoggedSignature)) {
            EventLog.add(this, "BLE-Muster geändert: " + lastLoggedSignature + " → " + packet.signature + "\n" + packet.details);
            lastLoggedSignature = packet.signature;
        }

        boolean learningDone = now - startedAt >= BASELINE_LEARNING_MS;
        if (!learningDone) return;

        if (!packet.signature.equals(baselineSignature)
                && (lastTriggerAt < 0 || now - lastTriggerAt >= TRIGGER_COOLDOWN_MS)) {
            lastTriggerAt = now;
            EventLog.add(this, "Aktivitätsmuster erkannt – openScale wird angefordert");
            updateMonitor("Waagenaktivität erkannt");
            launchOpenScale(true);
        } else if (baselineCount == 1 && packet.signature.equals(baselineSignature)) {
            // no-op; baseline remains the first observed idle pattern
        }
    }

    private void launchOpenScale(boolean trigger) {
        Intent launch = findOpenScaleLaunchIntent();
        if (launch == null) { EventLog.add(this, "openScale wurde nicht gefunden"); return; }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try {
            startActivity(launch);
            EventLog.add(this, trigger ? "openScale-Start angefordert" : "Teststart von openScale angefordert");
            if (trigger) handler.postDelayed(() -> showDetected("Falls openScale nicht geöffnet wurde: hier tippen.", launch), 1500L);
        } catch (RuntimeException e) {
            EventLog.add(this, "Direktstart blockiert: " + e.getClass().getSimpleName());
            showDetected("Waage erkannt – tippen, um openScale zu öffnen.", launch);
        }
    }

    private Intent findOpenScaleLaunchIntent() {
        for (String p : new String[]{"com.health.openscale.oss", "com.health.openscale.beta", "com.health.openscale"}) {
            Intent i = getPackageManager().getLaunchIntentForPackage(p); if (i != null) return i;
        }
        return null;
    }

    private Notification monitorNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ScaleScanService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_MONITOR).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ScaleLauncher aktiv").setContentText(text).setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stop).build()).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    private void updateMonitor(String text) { ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_MONITOR, monitorNotification(text)); }
    private void showDetected(String text, Intent launch) {
        PendingIntent pi = PendingIntent.getActivity(this, 3, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL_DETECTED).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Xiaomi-Waage erkannt").setContentText(text).setContentIntent(pi).setAutoCancel(true).build();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_DETECTED, n);
    }
    private void createChannels() {
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel monitor = new NotificationChannel(CHANNEL_MONITOR, "Waagenüberwachung", NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null); monitor.enableVibration(false); monitor.setShowBadge(false); nm.createNotificationChannel(monitor);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DETECTED, "Waage erkannt", NotificationManager.IMPORTANCE_DEFAULT));
    }
    private void stopScan() {
        if (scanner != null && callback != null && scanRunning && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try { scanner.stopScan(callback); } catch (RuntimeException ignored) {}
        }
        scanRunning = false;
    }
    @Override public void onDestroy() { handler.removeCallbacksAndMessages(null); stopScan(); EventLog.add(this, "Dienst gestoppt"); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
