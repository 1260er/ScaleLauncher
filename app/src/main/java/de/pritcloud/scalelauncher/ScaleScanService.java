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

import java.time.LocalDate;
import java.util.Collections;
import java.util.Locale;

public final class ScaleScanService extends Service {
    public static final String ACTION_STOP = "de.pritcloud.scalelauncher.STOP";
    private static final String CHANNEL_MONITOR = "scale_monitor_v7";
    private static final int NOTIFICATION_MONITOR = 10;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final S400Aggregator aggregator = new S400Aggregator();
    private final Runnable timeoutRunnable = this::finalizeTimedOutSession;

    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanRunning;
    private String lastLoggedSignature;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(
                NOTIFICATION_MONITOR,
                monitorNotification("BLE-Überwachung wird gestartet …"));
        EventLog.add(this, "Dienst gestartet – S400-Direktübernahme 2.7");
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
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            EventLog.add(this, "Bluetooth-Scan-Berechtigung fehlt");
            stopSelf();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        String bindKey = prefs.getString("bind_key", "");
        long userId = prefs.getLong("openscale_user_id", -1L);
        String authority = prefs.getString("openscale_authority", "");
        LocalDate birthDate = BirthDateUtils.parseIso(prefs.getString("birth_date", ""));
        float height = prefs.getFloat("height_cm", 0f);

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
        int currentAge = BirthDateUtils.ageToday(birthDate);
        if (currentAge < 18 || currentAge > 120) {
            EventLog.add(this, "Kein gültiger Geburtstag gespeichert – bitte Konfiguration öffnen");
            stopSelf();
            return;
        }
        if (height < 100f || height > 230f) {
            EventLog.add(this, "Keine gültige Körpergröße gespeichert – bitte Konfiguration öffnen");
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
                if (result.getDevice() != null
                        && mac.equalsIgnoreCase(result.getDevice().getAddress())) {
                    analyze(result, mac, bindKey);
                }
            }

            @Override public void onScanFailed(int code) {
                EventLog.add(ScaleScanService.this, "BLE-Scan fehlgeschlagen: " + code);
            }
        };

        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
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

        S400Decryptor.Measurement decoded = S400Decryptor.decrypt(
                packet.activityData,
                mac,
                bindKey);
        if (decoded == null) return;

        long now = System.currentTimeMillis();
        S400Aggregator.Outcome outcome = aggregator.ingest(decoded, now);
        handleOutcome(outcome);
        if (outcome.status == S400Aggregator.Status.PENDING) {
            handler.removeCallbacks(timeoutRunnable);
            handler.postDelayed(timeoutRunnable, aggregator.remainingTimeoutMs(now));
        }
    }

    private void finalizeTimedOutSession() {
        S400Aggregator.Outcome outcome = aggregator.finalizeTimedOut(System.currentTimeMillis());
        handleOutcome(outcome);
    }

    private void handleOutcome(S400Aggregator.Outcome outcome) {
        if (outcome.status == S400Aggregator.Status.PENDING) return;
        handler.removeCallbacks(timeoutRunnable);
        if (outcome.status == S400Aggregator.Status.DUPLICATE) {
            updateMonitor("Warte auf nächste Messung");
            return;
        }
        if (outcome.finalized != null) {
            S400Aggregator.Finalized value = outcome.finalized;
            EventLog.add(this, String.format(
                    Locale.GERMANY,
                    "S400-Messung entschlüsselt: %.1f kg | Impedanz %.1f/%.1f Ω%s",
                    value.weightKg,
                    value.impedanceHigh,
                    value.impedanceLow == null ? value.impedanceHigh : value.impedanceLow,
                    value.heartRate == null ? "" : " | Puls " + value.heartRate));
            updateMonitor(String.format(Locale.GERMANY, "Messung erkannt: %.1f kg", value.weightKg));
            importMeasurement(value);
        }
    }

    private void importMeasurement(S400Aggregator.Finalized measurement) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String authority = prefs.getString("openscale_authority", "");
        long userId = prefs.getLong("openscale_user_id", -1L);
        LocalDate birthDate = BirthDateUtils.parseIso(prefs.getString("birth_date", ""));
        float height = prefs.getFloat("height_cm", 0f);
        boolean male = prefs.getInt("sex", 0) == 1;
        long timestamp = measurement.timestampMs > 0L
                ? measurement.timestampMs
                : System.currentTimeMillis();
        int age = BirthDateUtils.ageOn(birthDate, timestamp);

        if (age < 18 || age > 120) {
            EventLog.add(this,
                    "Messung nicht übernommen: Alter konnte aus dem Geburtstag nicht gültig berechnet werden");
            updateMonitor("Geburtstag prüfen");
            return;
        }

        float lowImpedance = measurement.impedanceLow != null
                ? measurement.impedanceLow
                : measurement.impedanceHigh;
        S400BodyComposition.Result composition = S400BodyComposition.compute(
                new S400BodyComposition.Inputs(
                        age,
                        male,
                        height,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        lowImpedance));

        if (measurement.timedOut) {
            EventLog.add(this,
                    "Paket B fehlte – Einband-Auswertung mit hoher Impedanz als Ersatz");
        }
        if (composition.impedanceLabelsSwapped) {
            EventLog.add(this, "Impedanzbänder waren vertauscht und wurden korrigiert");
        }

        EventLog.add(this, buildCalculationLog(age, measurement, composition));

        try {
            OpenScaleProvider.Meta meta = OpenScaleProvider.readMeta(this, authority);
            prefs.edit().putInt("openscale_api_version", meta.apiVersion).apply();
            OpenScaleProvider.InsertResult result = OpenScaleProvider.insertMeasurement(
                    this,
                    authority,
                    userId,
                    timestamp,
                    meta.apiVersion,
                    measurement,
                    composition);
            logProviderResult(result);
        } catch (SecurityException e) {
            EventLog.add(this,
                    "openScale-Zugriff verweigert – Berechtigung in ScaleLauncher erneut erteilen");
            updateMonitor("openScale-Berechtigung fehlt");
        } catch (RuntimeException e) {
            EventLog.add(this,
                    "Übergabe an openScale fehlgeschlagen: "
                            + e.getClass().getSimpleName()
                            + " – "
                            + e.getMessage());
            updateMonitor("openScale-Übergabe fehlgeschlagen");
        }

        writeToHealthConnect(prefs, timestamp, measurement, composition);
    }

    private void writeToHealthConnect(SharedPreferences prefs,
                                      long timestamp,
                                      S400Aggregator.Finalized measurement,
                                      S400BodyComposition.Result composition) {
        if (!prefs.getBoolean("health_connect_enabled", false)) return;

        String scaleMac = prefs.getString("mac", "");
        HealthConnectWriter.write(
                this,
                timestamp,
                scaleMac,
                measurement,
                composition,
                new HealthConnectWriter.Callback() {
                    @Override public void onSuccess(int writtenRecordCount) {
                        EventLog.add(
                                ScaleScanService.this,
                                "Health Connect: "
                                        + writtenRecordCount
                                        + " Werte gespeichert");
                    }

                    @Override public void onError(String message) {
                        EventLog.add(
                                ScaleScanService.this,
                                "Health Connect fehlgeschlagen: " + message);
                    }
                });
    }

    private void logProviderResult(OpenScaleProvider.InsertResult result) {
        if (!result.measurementVerified) {
            EventLog.add(this,
                    "openScale-Übergabe konnte nach dem Schreiben nicht bestätigt werden");
            updateMonitor("Übergabe nicht bestätigt");
            return;
        }

        if (result.apiVersion < 2) {
            EventLog.add(this,
                    "openScale gespeichert und geprüft – Provider-API 1: "
                            + "Gewicht, Fett, Wasser und Muskel. "
                            + "Zusätzliche S400-Werte kann diese externe Schnittstelle nicht speichern.");
            updateMonitor("Messung gespeichert – 4 Grundwerte");
            return;
        }

        if (result.additionalValuesVerified) {
            EventLog.add(this,
                    "openScale vollständig gespeichert und geprüft – Provider-API "
                            + result.apiVersion
                            + ", "
                            + result.storedValueCount
                            + " Werte sichtbar");
            updateMonitor("Vollständige Messung gespeichert");
        } else {
            EventLog.add(this,
                    "openScale-Grundmessung gespeichert, zusätzliche Werte aber nicht bestätigt – "
                            + "Provider-API "
                            + result.apiVersion);
            updateMonitor("Grundmessung gespeichert");
        }
    }

    private String buildCalculationLog(int age,
                                       S400Aggregator.Finalized measurement,
                                       S400BodyComposition.Result composition) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(
                Locale.GERMANY,
                "S400 ausgewertet (Alter %d): %.1f kg",
                age,
                measurement.weightKg));
        appendPercent(text, "Fett", composition.bodyFatPercent);
        appendPercent(text, "Wasser", composition.totalBodyWaterPercent);
        appendPercent(text, "Muskel", composition.skeletalMusclePercent);
        appendKg(text, "Knochen", composition.boneKg);
        appendKg(text, "LBM", composition.fatFreeMassKg);
        appendValue(text, "Viszeralfett", composition.visceralFatIndex, "");
        appendValue(text, "BMR", composition.basalMetabolicRateKcal, " kcal");
        appendPercent(text, "Protein", composition.proteinPercent);
        appendPercent(text, "ECW", composition.extracellularWaterPercent);
        appendPercent(text, "ICW", composition.intracellularWaterPercent);
        appendKg(text, "BCM", composition.bodyCellMassKg);
        if (measurement.heartRate != null) {
            text.append(" | Puls ").append(measurement.heartRate);
        }
        text.append(" | Qualität ").append(composition.reliability.name());
        return text.toString();
    }

    private static void appendPercent(StringBuilder text, String label, Float value) {
        if (value != null) {
            text.append(String.format(Locale.GERMANY, " | %s %.1f %%", label, value));
        }
    }

    private static void appendKg(StringBuilder text, String label, Float value) {
        if (value != null) {
            text.append(String.format(Locale.GERMANY, " | %s %.1f kg", label, value));
        }
    }

    private static void appendValue(StringBuilder text,
                                    String label,
                                    Float value,
                                    String suffix) {
        if (value != null) {
            text.append(String.format(Locale.GERMANY, " | %s %.1f%s", label, value, suffix));
        }
    }

    private Notification monitorNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
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
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateMonitor(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_MONITOR, monitorNotification(text));
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_MONITOR,
                "Waagenüberwachung",
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void stopScan() {
        if (scanner != null
                && callback != null
                && scanRunning
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                scanner.stopScan(callback);
            } catch (RuntimeException ignored) {
            }
        }
        scanRunning = false;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopScan();
        aggregator.reset();
        EventLog.add(this, "Dienst gestoppt");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
