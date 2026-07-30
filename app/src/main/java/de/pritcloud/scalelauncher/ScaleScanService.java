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

import java.util.Collections;
import java.util.Locale;

public final class ScaleScanService extends Service {
    public static final String ACTION_STOP = "de.pritcloud.scalelauncher.STOP";
    private static final String CHANNEL_MONITOR = "scale_monitor_v4";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final long FINALIZE_DELAY_MS = 1_800L;
    private static final long DUPLICATE_WINDOW_MS = 120_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finalizeRunnable = this::finalizeMeasurement;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanRunning;
    private String lastLoggedSignature;
    private S400Decryptor.Measurement pendingMeasurement;
    private long lastInsertedAt;
    private float lastInsertedWeight = -1f;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_MONITOR, monitorNotification("BLE-Überwachung wird gestartet …"));
        EventLog.add(this, "Dienst gestartet – S400-Direktübernahme 2.4");
        startScan();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "Bluetooth-Scan-Berechtigung fehlt");
            stopSelf();
            return;
        }
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        String bindKey = prefs.getString("bind_key", "");
        long userId = prefs.getLong("openscale_user_id", -1L);
        String authority = prefs.getString("openscale_authority", "");
        if (!S400Decryptor.isValidMacAddress(mac)) {
            EventLog.add(this, "Keine gültige Waagen-MAC gespeichert");
            stopSelf();
            return;
        }
        if (!S400Decryptor.isValidBindKey(bindKey)) {
            EventLog.add(this, "Kein gültiger S400 Bind-Key gespeichert");
            stopSelf();
            return;
        }
        if (userId < 0 || authority == null || authority.isBlank()) {
            EventLog.add(this, "Kein openScale-Benutzer ausgewählt");
            stopSelf();
            return;
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            EventLog.add(this, "Bluetooth nicht verfügbar oder ausgeschaltet");
            stopSelf();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            EventLog.add(this, "Bluetooth-Scanner nicht verfügbar");
            stopSelf();
            return;
        }

        callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                if (result.getDevice() != null && mac.equalsIgnoreCase(result.getDevice().getAddress())) analyze(result, mac, bindKey);
            }
            @Override public void onScanFailed(int code) {
                EventLog.add(ScaleScanService.this, "BLE-Scan fehlgeschlagen: " + code);
            }
        };
        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanRunning = true;
            EventLog.add(this, "BLE-Scan aktiv für " + mac);
            updateMonitor("Warte auf S400-Messung");
        } catch (RuntimeException e) {
            EventLog.add(this, "Scanstart fehlgeschlagen: " + e.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void analyze(ScanResult result, String mac, String bindKey) {
        BlePacket packet = BlePacket.from(result);
        if (lastLoggedSignature == null) {
            lastLoggedSignature = packet.signature;
            EventLog.add(this, "Erstes BLE-Muster " + packet.signature);
        } else if (!packet.signature.equals(lastLoggedSignature)) {
            lastLoggedSignature = packet.signature;
        }
        if (!packet.activityPacket || packet.activityData == null) return;

        S400Decryptor.Measurement measurement = S400Decryptor.decrypt(packet.activityData, mac, bindKey);
        if (measurement == null) return;
        pendingMeasurement = merge(pendingMeasurement, measurement);
        handler.removeCallbacks(finalizeRunnable);
        handler.postDelayed(finalizeRunnable, FINALIZE_DELAY_MS);
        EventLog.add(this, String.format(Locale.GERMANY,
                "S400 entschlüsselt: %.1f kg%s%s",
                measurement.weightKg,
                measurement.impedance == null ? "" : " | Impedanz " + measurement.impedance,
                measurement.heartRate == null ? "" : " | Puls " + measurement.heartRate));
        updateMonitor(String.format(Locale.GERMANY, "Messung erkannt: %.1f kg", measurement.weightKg));
    }

    private S400Decryptor.Measurement merge(S400Decryptor.Measurement oldValue, S400Decryptor.Measurement newValue) {
        if (oldValue == null || Math.abs(oldValue.weightKg - newValue.weightKg) > 0.3f) return newValue;
        Float impedance = newValue.impedance != null ? newValue.impedance : oldValue.impedance;
        Integer heartRate = newValue.heartRate != null ? newValue.heartRate : oldValue.heartRate;
        return new S400Decryptor.Measurement(newValue.weightKg, impedance, heartRate);
    }

    private void finalizeMeasurement() {
        S400Decryptor.Measurement measurement = pendingMeasurement;
        pendingMeasurement = null;
        if (measurement == null) return;

        long now = System.currentTimeMillis();
        if (lastInsertedAt > 0 && now - lastInsertedAt < DUPLICATE_WINDOW_MS
                && Math.abs(lastInsertedWeight - measurement.weightKg) < 0.05f) {
            EventLog.add(this, "Doppelte Messung verworfen");
            updateMonitor("Warte auf nächste Messung");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String authority = prefs.getString("openscale_authority", "");
        long userId = prefs.getLong("openscale_user_id", -1L);
        Float fat = null, water = null, muscle = null;
        if (measurement.impedance != null && measurement.impedance > 0f) {
            int age = prefs.getInt("age", 0);
            float height = prefs.getFloat("height_cm", 0f);
            int sex = prefs.getInt("sex", 0);
            if (age >= 10 && height >= 100f) {
                MiScaleCalculator calculator = new MiScaleCalculator(sex, age, height);
                fat = calculator.bodyFat(measurement.weightKg, measurement.impedance);
                water = calculator.water(measurement.weightKg, measurement.impedance);
                muscle = calculator.muscle(measurement.weightKg, measurement.impedance);
            }
        }

        try {
            OpenScaleProvider.insertMeasurement(this, authority, userId, now,
                    measurement.weightKg, fat, water, muscle);
            lastInsertedAt = now;
            lastInsertedWeight = measurement.weightKg;
            EventLog.add(this, String.format(Locale.GERMANY,
                    "Messung an openScale übergeben: %.1f kg%s",
                    measurement.weightKg,
                    fat == null ? "" : String.format(Locale.GERMANY, " | Fett %.1f %% | Wasser %.1f %% | Muskel %.1f %%", fat, water, muscle)));
            updateMonitor("Messung an openScale übergeben");
        } catch (SecurityException e) {
            EventLog.add(this, "openScale-Zugriff verweigert – Berechtigung in ScaleLauncher erneut erteilen");
            updateMonitor("openScale-Berechtigung fehlt");
        } catch (RuntimeException e) {
            EventLog.add(this, "Übergabe an openScale fehlgeschlagen: " + e.getClass().getSimpleName() + " – " + e.getMessage());
            updateMonitor("Übergabe fehlgeschlagen");
        }
    }

    private Notification monitorNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ScaleScanService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ScaleLauncher aktiv")
                .setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateMonitor(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_MONITOR, monitorNotification(text));
    }

    private void createChannel() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_MONITOR, "Waagenüberwachung", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void stopScan() {
        if (scanner != null && callback != null && scanRunning
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try { scanner.stopScan(callback); } catch (RuntimeException ignored) {}
        }
        scanRunning = false;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopScan();
        EventLog.add(this, "Dienst gestoppt");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
