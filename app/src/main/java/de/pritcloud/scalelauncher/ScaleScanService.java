package de.pritcloud.scalelauncher;

import android.Manifest;
import android.app.KeyguardManager;
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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Collections;

public class ScaleScanService extends Service {
    private static final String CHANNEL_MONITOR = "scale_monitor";
    private static final String CHANNEL_DETECTED = "scale_detected";
    private static final int NOTIFICATION_ID = 10;
    private static final long STARTUP_GRACE_MS = 10_000;
    private static final long ABSENT_REARM_MS = 8_000;
    private static final long MIN_TRIGGER_GAP_MS = 15_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private long serviceStartedAt;
    private long lastSeenAt;
    private long lastTriggeredAt;
    private boolean encounterActive;
    private boolean armed;

    private final Runnable rearmCheck = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (encounterActive && now - lastSeenAt >= ABSENT_REARM_MS) {
                encounterActive = false;
                armed = true;
                EventLog.add(ScaleScanService.this, "Waage nicht mehr sichtbar – erneut bereit");
            }
            handler.postDelayed(this, 2_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        serviceStartedAt = SystemClock.elapsedRealtime();
        createChannels();
        startForeground(NOTIFICATION_ID, monitoringNotification("Warte auf die Waage …"));
        EventLog.add(this, "Überwachung gestartet");
        startScan();
        handler.post(rearmCheck);
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "Bluetooth-Scan-Berechtigung fehlt");
            stopSelf();
            return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        String mac = getSharedPreferences("prefs", MODE_PRIVATE).getString("mac", "");
        if (scanner == null || mac.isEmpty()) {
            EventLog.add(this, scanner == null ? "Bluetooth-Scanner nicht verfügbar" : "Keine Waage ausgewählt");
            stopSelf();
            return;
        }

        callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice() != null && mac.equalsIgnoreCase(result.getDevice().getAddress())) {
                    onScaleSeen(result.getRssi());
                }
            }

            @Override public void onScanFailed(int errorCode) {
                EventLog.add(ScaleScanService.this, "BLE-Scan fehlgeschlagen: " + errorCode);
            }
        };

        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        scanner.startScan(Collections.singletonList(filter), settings, callback);
        EventLog.add(this, "BLE-Scan aktiv für " + mac);
    }

    private void onScaleSeen(int rssi) {
        long now = SystemClock.elapsedRealtime();
        lastSeenAt = now;

        if (now - serviceStartedAt < STARTUP_GRACE_MS) {
            encounterActive = true;
            EventLog.add(this, "Waage während Startphase gesehen (RSSI " + rssi + ") – ignoriert");
            return;
        }

        if (encounterActive || !armed) return;
        if (now - lastTriggeredAt < MIN_TRIGGER_GAP_MS) return;

        encounterActive = true;
        armed = false;
        lastTriggeredAt = now;
        EventLog.add(this, "Waage erkannt (RSSI " + rssi + ")");
        launchOrNotify();
    }

    private void launchOrNotify() {
        Intent launch = openScaleIntent();
        if (launch == null) {
            EventLog.add(this, "openScale nicht gefunden");
            notifyUser("openScale wurde nicht gefunden.", null);
            return;
        }

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        if (locked) {
            EventLog.add(this, "Telefon gesperrt – Android erlaubt keinen zuverlässigen Direktstart");
            notifyUser("Telefon entsperren und tippen, um openScale zu öffnen.", launch);
            return;
        }

        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(launch);
            EventLog.add(this, "openScale-Start angefordert");
        } catch (Exception blocked) {
            EventLog.add(this, "Direktstart blockiert: " + blocked.getClass().getSimpleName());
            notifyUser("Waage erkannt – tippen, um openScale zu öffnen.", launch);
        }
    }

    private Intent openScaleIntent() {
        String[] packages = {"com.health.openscale.oss", "com.health.openscale.beta", "com.health.openscale"};
        for (String pkg : packages) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) return i;
        }
        return null;
    }

    private Notification monitoringNotification(String text) {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ScaleLauncher aktiv")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void notifyUser(String text, Intent launch) {
        PendingIntent pi = launch == null ? null : PendingIntent.getActivity(this, 2, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL_DETECTED)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Xiaomi-Waage erkannt")
                .setContentText(text)
                .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(11, b.build());
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel monitor = new NotificationChannel(CHANNEL_MONITOR, "Waagenüberwachung", NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Dauerhafte, stille Anzeige des Hintergrunddienstes.");
        monitor.setShowBadge(false);
        nm.createNotificationChannel(monitor);

        NotificationChannel detected = new NotificationChannel(CHANNEL_DETECTED, "Waage erkannt", NotificationManager.IMPORTANCE_DEFAULT);
        detected.setDescription("Hinweis, wenn openScale nicht direkt geöffnet werden kann.");
        nm.createNotificationChannel(detected);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (scanner != null && callback != null && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.stopScan(callback);
        }
        EventLog.add(this, "Überwachung gestoppt");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
