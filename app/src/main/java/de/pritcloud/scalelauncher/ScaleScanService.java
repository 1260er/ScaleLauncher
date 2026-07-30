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

    private static final String CHANNEL_MONITOR = "scale_monitor_v2";
    private static final String CHANNEL_DETECTED = "scale_detected_v2";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final int NOTIFICATION_DETECTED = 11;
    private static final long WATCHDOG_MS = 1_000L;
    private static final long PACKET_WINDOW_MS = 1_500L;
    private static final int REQUIRED_PACKETS = 2;
    private static final long SCAN_RESTART_MS = 10 * 60_000L;

    private enum State {
        WAITING_FOR_ABSENCE,
        ARMED,
        ACTIVE
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private ScanCallback scanCallback;
    private State state = State.WAITING_FOR_ABSENCE;
    private long serviceStartedAt = -1L;
    private long lastSeenAt = -1L;
    private long firstPacketAt = -1L;
    private long scanStartedAt = -1L;
    private int packetCount = 0;
    private boolean scanRunning = false;
    private boolean waitingPresenceLogged = false;

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            final long now = SystemClock.elapsedRealtime();
            final long absenceMs = getAbsenceMs();

            long reference = lastSeenAt > 0 ? lastSeenAt : serviceStartedAt;
            if (reference > 0 && now - reference >= absenceMs && state != State.ARMED) {
                state = State.ARMED;
                packetCount = 0;
                firstPacketAt = -1L;
                waitingPresenceLogged = false;
                EventLog.add(ScaleScanService.this, "Waage ist aus – bereit für die nächste Messung");
                updateMonitorNotification("Bereit – warte auf die Waage");
            }

            if (scanRunning && scanStartedAt > 0 && now - scanStartedAt >= SCAN_RESTART_MS) {
                EventLog.add(ScaleScanService.this, "BLE-Scan wird vorsorglich neu gestartet");
                restartScan(750L);
            }

            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        serviceStartedAt = SystemClock.elapsedRealtime();
        createNotificationChannels();
        startForeground(NOTIFICATION_MONITOR, buildMonitorNotification("Initialisierung …"));
        EventLog.add(this, "Dienst gestartet");
        EventLog.add(this, "Warte zunächst, bis die Waage vollständig ausgeschaltet ist");
        startScan();
        handler.post(watchdog);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_TEST_OPEN.equals(intent.getAction())) {
            launchOpenScale(false);
        }
        return START_STICKY;
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "Bluetooth-Scan-Berechtigung fehlt");
            updateMonitorNotification("Berechtigung fehlt");
            stopSelf();
            return;
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            EventLog.add(this, "Bluetooth ist ausgeschaltet oder nicht verfügbar");
            updateMonitorNotification("Bluetooth ist ausgeschaltet");
            stopSelf();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        if (mac == null || mac.isBlank()) {
            EventLog.add(this, "Keine Waage ausgewählt");
            updateMonitorNotification("Keine Waage ausgewählt");
            stopSelf();
            return;
        }

        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            EventLog.add(this, "Bluetooth-Scanner nicht verfügbar");
            updateMonitorNotification("Bluetooth-Scanner nicht verfügbar");
            stopSelf();
            return;
        }

        final String targetMac = mac;
        scanCallback = new ScanCallback() {
            @Override public void onScanResult(int callbackType, ScanResult result) {
                if (result.getDevice() != null
                        && targetMac.equalsIgnoreCase(result.getDevice().getAddress())) {
                    onTargetSeen(result.getRssi());
                }
            }

            @Override public void onBatchScanResults(java.util.List<ScanResult> results) {
                for (ScanResult result : results) {
                    onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result);
                }
            }

            @Override public void onScanFailed(int errorCode) {
                scanRunning = false;
                EventLog.add(ScaleScanService.this, "BLE-Scan fehlgeschlagen (Code " + errorCode + ") – Neustart folgt");
                restartScan(2_000L);
            }
        };

        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                .build();

        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
            scanRunning = true;
            scanStartedAt = SystemClock.elapsedRealtime();
            EventLog.add(this, "BLE-Scan aktiv für " + mac);
            updateMonitorNotification("Warte, bis die Waage ausgeschaltet ist");
        } catch (SecurityException | IllegalStateException e) {
            EventLog.add(this, "BLE-Scan konnte nicht gestartet werden: " + e.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void onTargetSeen(int rssi) {
        long now = SystemClock.elapsedRealtime();
        lastSeenAt = now;

        if (state == State.WAITING_FOR_ABSENCE) {
            if (!waitingPresenceLogged) {
                waitingPresenceLogged = true;
                EventLog.add(this, "Waage ist beim Dienststart noch aktiv (RSSI " + rssi + ") – warte auf Ausschalten");
                updateMonitorNotification("Waage noch aktiv – warte auf Ausschalten");
            }
            return;
        }

        if (state == State.ACTIVE) {
            return;
        }

        if (firstPacketAt < 0 || now - firstPacketAt > PACKET_WINDOW_MS) {
            firstPacketAt = now;
            packetCount = 1;
            return;
        }

        packetCount++;
        if (packetCount < REQUIRED_PACKETS) {
            return;
        }

        state = State.ACTIVE;
        packetCount = 0;
        firstPacketAt = -1L;
        EventLog.add(this, "Neue Waagenaktivität erkannt (RSSI " + rssi + ")");
        updateMonitorNotification("Waage erkannt – openScale wird geöffnet");
        launchOpenScale(true);
    }

    private void launchOpenScale(boolean measurementTrigger) {
        Intent launch = findOpenScaleLaunchIntent();
        if (launch == null) {
            EventLog.add(this, "openScale wurde nicht gefunden");
            showDetectedNotification("openScale wurde nicht gefunden.", null);
            return;
        }

        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            startActivity(launch);
            EventLog.add(this, measurementTrigger
                    ? "openScale-Start angefordert"
                    : "Teststart von openScale angefordert");
            // Android kann einen Hintergrundstart ohne Exception ablehnen. Darum gibt es
            // zusätzlich eine stille, antippbare Ersatzmeldung.
            if (measurementTrigger) {
                handler.postDelayed(() -> showDetectedNotification(
                        "Falls openScale nicht geöffnet wurde: hier tippen.", launch), 1_500L);
            }
        } catch (RuntimeException e) {
            EventLog.add(this, "Direktstart nicht möglich: " + e.getClass().getSimpleName());
            showDetectedNotification("Waage erkannt – tippen, um openScale zu öffnen.", launch);
        }
    }

    private Intent findOpenScaleLaunchIntent() {
        String[] packages = {
                "com.health.openscale.oss",
                "com.health.openscale.beta",
                "com.health.openscale"
        };
        for (String packageName : packages) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                return intent;
            }
        }
        return null;
    }

    private long getAbsenceMs() {
        int seconds = getSharedPreferences("prefs", MODE_PRIVATE).getInt("absence_seconds", 8);
        return Math.max(4, Math.min(seconds, 30)) * 1_000L;
    }

    private void restartScan(long delayMs) {
        stopScan();
        handler.postDelayed(this::startScan, delayMs);
    }

    private void stopScan() {
        if (scanner != null && scanCallback != null && scanRunning
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            try {
                scanner.stopScan(scanCallback);
            } catch (RuntimeException ignored) {
                // Scanner can already be shutting down.
            }
        }
        scanRunning = false;
        scanCallback = null;
    }

    private Notification buildMonitorNotification(String text) {
        PendingIntent openApp = PendingIntent.getActivity(
                this,
                1,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(
                this,
                2,
                new Intent(this, ScaleScanService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ScaleLauncher aktiv")
                .setContentText(text)
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build();
    }

    private void updateMonitorNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_MONITOR, buildMonitorNotification(text));
    }

    private void showDetectedNotification(String text, Intent launchIntent) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_DETECTED)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("Xiaomi-Waage erkannt")
                .setContentText(text)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER);

        if (launchIntent != null) {
            PendingIntent openScale = PendingIntent.getActivity(
                    this,
                    3,
                    launchIntent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            builder.setContentIntent(openScale);
        }
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_DETECTED, builder.build());
    }

    private void createNotificationChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                "Waagenüberwachung",
                NotificationManager.IMPORTANCE_LOW);
        monitor.setDescription("Erforderliche stille Anzeige des laufenden Bluetooth-Dienstes.");
        monitor.setShowBadge(false);
        monitor.enableVibration(false);
        monitor.setSound(null, null);
        manager.createNotificationChannel(monitor);

        NotificationChannel detected = new NotificationChannel(
                CHANNEL_DETECTED,
                "Waage erkannt",
                NotificationManager.IMPORTANCE_DEFAULT);
        detected.setDescription("Antippbarer Ersatz, wenn Android openScale nicht direkt öffnet.");
        manager.createNotificationChannel(detected);
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        EventLog.add(this, "App-Oberfläche geschlossen – Dienst läuft weiter");
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopScan();
        EventLog.add(this, "Dienst gestoppt");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
