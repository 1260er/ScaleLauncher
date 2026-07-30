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
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.SystemClock;

import java.util.Collections;

public class ScaleScanService extends Service {
    private static final String CHANNEL = "scale_monitor";
    private static final int NOTIFICATION_ID = 10;
    private static final long COOLDOWN_MS = 45_000;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private long lastLaunch;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, monitoringNotification("Warte auf die Waage …"));
        startScan();
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            stopSelf(); return;
        }
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
        String mac = getSharedPreferences("prefs", MODE_PRIVATE).getString("mac", "");
        if (scanner == null || mac.isEmpty()) { stopSelf(); return; }

        callback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice() != null && mac.equalsIgnoreCase(result.getDevice().getAddress())) {
                    onScaleSeen();
                }
            }
        };
        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();
        scanner.startScan(Collections.singletonList(filter), settings, callback);
    }

    private void onScaleSeen() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < COOLDOWN_MS) return;
        lastLaunch = now;
        Intent launch = openScaleIntent();
        if (launch == null) {
            notifyUser("openScale wurde nicht gefunden.", null);
            return;
        }
        try {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(launch);
        } catch (Exception blocked) {
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
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ScaleLauncher aktiv")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void notifyUser(String text, Intent launch) {
        PendingIntent pi = launch == null ? null : PendingIntent.getActivity(this, 2, launch,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Xiaomi S400 erkannt")
                .setContentText(text)
                .setAutoCancel(true);
        if (pi != null) b.setContentIntent(pi);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(11, b.build());
    }

    private void createChannel() {
        NotificationChannel c = new NotificationChannel(CHANNEL, "Waagenüberwachung", NotificationManager.IMPORTANCE_DEFAULT);
        c.setDescription("Zeigt an, dass ScaleLauncher nach der Waage sucht.");
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
    }

    @Override public void onDestroy() {
        if (scanner != null && callback != null && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            scanner.stopScan(callback);
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
