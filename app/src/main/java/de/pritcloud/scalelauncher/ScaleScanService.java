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
import java.util.List;
import java.util.Locale;

public final class ScaleScanService extends Service {
    public static final String ACTION_STOP = "de.pritcloud.scalelauncher.STOP";
    public static final String ACTION_ASSIGN_PENDING = "de.pritcloud.scalelauncher.ASSIGN_PENDING";
    public static final String ACTION_REFRESH_PENDING = "de.pritcloud.scalelauncher.REFRESH_PENDING";
    public static final String EXTRA_PENDING_ID = "pending_id";
    public static final String EXTRA_USER_ID = "user_id";

    private static final String CHANNEL_MONITOR = "scale_monitor_v10";
    private static final String CHANNEL_ASSIGNMENT = "scale_assignment_v1";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final int NOTIFICATION_ASSIGNMENT = 11;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final S400Aggregator aggregator = new S400Aggregator();
    private final Runnable timeoutRunnable = this::finalizeTimedOutSession;

    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanRunning;
    private boolean scaleSeenLogged;
    private String lastLoggedSignature;

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(
                NOTIFICATION_MONITOR,
                monitorNotification("BLE-Überwachung wird gestartet …"));
        EventLog.info(this, "Dienst gestartet – Mehrbenutzer-Zuordnung 3.0");
        startScan();
        updateAssignmentNotification();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_ASSIGN_PENDING.equals(intent.getAction())) {
            String pendingId = intent.getStringExtra(EXTRA_PENDING_ID);
            long userId = intent.getLongExtra(EXTRA_USER_ID, -1L);
            assignPending(pendingId, userId);
        } else if (intent != null && ACTION_REFRESH_PENDING.equals(intent.getAction())) {
            updateAssignmentNotification();
        }
        return START_STICKY;
    }

    private void startScan() {
        if (scanRunning) return;
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
            EventLog.error(this, "Bluetooth-Scan-Berechtigung fehlt");
            stopSelf();
            return;
        }

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        String bindKey = prefs.getString("bind_key", "");
        String authority = prefs.getString("openscale_authority", "");
        List<UserProfile> profiles = UserProfileStore.enabled(UserProfileStore.load(prefs));

        if (!S400Decryptor.isValidMacAddress(mac)) {
            EventLog.error(this, "Keine gültige Waagen-MAC gespeichert");
            stopSelf();
            return;
        }
        if (!S400Decryptor.isValidBindKey(bindKey)) {
            EventLog.error(this, "Kein gültiger S400 Bind-Key gespeichert");
            stopSelf();
            return;
        }
        if (authority == null || authority.isBlank()) {
            EventLog.error(this, "Keine openScale-Verbindung gespeichert");
            stopSelf();
            return;
        }
        if (profiles.isEmpty()) {
            EventLog.error(this, "Kein aktives Benutzerprofil eingerichtet");
            stopSelf();
            return;
        }
        long now = System.currentTimeMillis();
        for (UserProfile profile : profiles) {
            if (!profile.hasValidBodyData(now) || !profile.hasValidMatchingData()) {
                EventLog.error(this, "Benutzerprofil unvollständig: " + profile.name);
                stopSelf();
                return;
            }
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            EventLog.error(this, "Bluetooth nicht verfügbar oder ausgeschaltet");
            stopSelf();
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            EventLog.error(this, "Bluetooth-Scanner nicht verfügbar");
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
                EventLog.error(ScaleScanService.this, "BLE-Scan fehlgeschlagen: " + code);
                updateMonitor("BLE-Scan fehlgeschlagen");
            }
        };

        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanRunning = true;
            EventLog.info(this,
                    "Überwachung aktiv – " + profiles.size() + " Benutzerprofile bereit");
            EventLog.debug(this, "BLE-Scan aktiv für " + mac);
            updateMonitor("Warte auf S400-Messung");
        } catch (RuntimeException e) {
            EventLog.error(this,
                    "Scanstart fehlgeschlagen: " + e.getClass().getSimpleName());
            stopSelf();
        }
    }

    private void analyze(ScanResult result, String mac, String bindKey) {
        BlePacket packet = BlePacket.from(result);
        if (!scaleSeenLogged) {
            scaleSeenLogged = true;
            EventLog.info(this, "Waage erkannt – BLE-Empfang aktiv");
        }
        if (lastLoggedSignature == null) {
            lastLoggedSignature = packet.signature;
            EventLog.debug(this, "Erstes BLE-Muster " + packet.signature);
        } else if (!packet.signature.equals(lastLoggedSignature)) {
            lastLoggedSignature = packet.signature;
        }
        if (!packet.activityPacket || packet.activityData == null) return;

        S400Decryptor.Measurement decoded = S400Decryptor.decrypt(
                packet.activityData,
                mac,
                bindKey);
        if (decoded == null) {
            EventLog.debug(this, "S400-Aktivitätspaket konnte nicht entschlüsselt werden");
            return;
        }

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
            EventLog.debug(this, "Doppeltes Messpaket verworfen");
            updateMonitor("Warte auf nächste Messung");
            return;
        }
        if (outcome.finalized == null) return;

        S400Aggregator.Finalized value = outcome.finalized;
        EventLog.info(this, String.format(
                Locale.GERMANY,
                "Messung erkannt: %.1f kg",
                value.weightKg));
        EventLog.debug(this, String.format(
                Locale.GERMANY,
                "S400 entschlüsselt: %.1f kg | Impedanz %.1f/%.1f Ω",
                value.weightKg,
                value.impedanceHigh,
                value.impedanceLow == null ? value.impedanceHigh : value.impedanceLow));
        routeMeasurement(value);
    }

    private void routeMeasurement(S400Aggregator.Finalized measurement) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        List<UserProfile> profiles = UserProfileStore.enabled(UserProfileStore.load(prefs));
        UserMatcher.Result match = UserMatcher.match(profiles, measurement.weightKg);
        EventLog.debug(this, "Benutzerabgleich: " + UserMatcher.diagnosticSummary(match));

        if (match.status == UserMatcher.Status.MATCHED && match.profile != null) {
            EventLog.info(this, String.format(
                    Locale.GERMANY,
                    "Automatisch zugeordnet: %.1f kg → %s",
                    measurement.weightKg,
                    match.profile.name));
            updateMonitor("Messung für " + match.profile.name + " erkannt");
            processMeasurement(measurement, match.profile);
            return;
        }

        String reason = match.status == UserMatcher.Status.AMBIGUOUS
                ? "mehrere ähnlich passende Benutzer"
                : "kein Benutzer innerhalb der Gewichtstoleranz";
        PendingMeasurementStore.Item pending = PendingMeasurementStore.add(
                prefs,
                measurement,
                reason);
        EventLog.warning(this, String.format(
                Locale.GERMANY,
                "Messung %.1f kg nicht zugeordnet – %s",
                measurement.weightKg,
                reason));
        EventLog.debug(this, "Offene Messung gespeichert: " + pending.id);
        updateMonitor("Benutzerzuordnung erforderlich");
        updateAssignmentNotification();
    }

    private void assignPending(String pendingId, long userId) {
        if (pendingId == null || pendingId.isBlank() || userId < 0L) return;
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        PendingMeasurementStore.Item pending = PendingMeasurementStore.find(prefs, pendingId);
        UserProfile profile = UserProfileStore.find(UserProfileStore.load(prefs), userId);
        if (pending == null) {
            EventLog.warning(this, "Die offene Messung wurde nicht mehr gefunden");
            updateAssignmentNotification();
            return;
        }
        if (profile == null || !profile.enabled || !profile.hasValidBodyData(pending.timestampMs)) {
            EventLog.error(this, "Gewähltes Benutzerprofil ist nicht verfügbar oder unvollständig");
            return;
        }

        EventLog.info(this, String.format(
                Locale.GERMANY,
                "Manuell zugeordnet: %.1f kg → %s",
                pending.weightKg,
                profile.name));
        if (processMeasurement(pending.toMeasurement(), profile)) {
            PendingMeasurementStore.remove(prefs, pending.id);
            updateAssignmentNotification();
        }
    }

    private boolean processMeasurement(S400Aggregator.Finalized measurement,
                                       UserProfile profile) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String authority = prefs.getString("openscale_authority", "");
        LocalDate birthDate = BirthDateUtils.parseIso(profile.birthDateIso);
        long timestamp = measurement.timestampMs > 0L
                ? measurement.timestampMs
                : System.currentTimeMillis();
        int age = BirthDateUtils.ageOn(birthDate, timestamp);

        if (age < 18 || age > 120) {
            EventLog.error(this,
                    "Messung nicht übernommen: Geburtstag von " + profile.name + " prüfen");
            updateMonitor("Geburtstag prüfen");
            return false;
        }

        float lowImpedance = measurement.impedanceLow != null
                ? measurement.impedanceLow
                : measurement.impedanceHigh;
        S400BodyComposition.Result composition = S400BodyComposition.compute(
                new S400BodyComposition.Inputs(
                        age,
                        profile.male,
                        profile.heightCm,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        lowImpedance));

        if (measurement.timedOut) {
            EventLog.warning(this,
                    "Messung für " + profile.name
                            + " unvollständig: zweites Impedanzpaket fehlte");
        }
        if (composition.impedanceLabelsSwapped) {
            EventLog.debug(this, "Impedanzbänder waren vertauscht und wurden korrigiert");
        }
        EventLog.debug(this, buildCalculationLog(profile.name, age, measurement, composition));

        boolean openScaleStored;
        try {
            OpenScaleProvider.Meta meta = OpenScaleProvider.readMeta(this, authority);
            prefs.edit().putInt("openscale_api_version", meta.apiVersion).apply();
            OpenScaleProvider.InsertResult result = OpenScaleProvider.insertMeasurement(
                    this,
                    authority,
                    profile.userId,
                    timestamp,
                    meta.apiVersion,
                    measurement,
                    composition);
            openScaleStored = logProviderResult(result, profile.name);
            if (openScaleStored) {
                float average = OpenScaleProvider.readAverageRecentWeight(
                        this,
                        authority,
                        profile.userId,
                        5);
                if (average > 0f) {
                    UserProfileStore.updateReferenceWeight(prefs, profile.userId, average);
                    EventLog.debug(this, String.format(
                            Locale.GERMANY,
                            "Referenzgewicht %s aktualisiert: %.1f kg",
                            profile.name,
                            average));
                }
            }
        } catch (SecurityException e) {
            EventLog.error(this,
                    "openScale-Zugriff verweigert – Berechtigung erneut erteilen");
            updateMonitor("openScale-Berechtigung fehlt");
            return false;
        } catch (RuntimeException e) {
            EventLog.error(this,
                    "Übergabe an openScale fehlgeschlagen: "
                            + e.getClass().getSimpleName()
                            + " – "
                            + safeMessage(e));
            updateMonitor("openScale-Übergabe fehlgeschlagen");
            return false;
        }

        if (openScaleStored) {
            writeToHealthConnect(prefs, profile, timestamp, measurement, composition);
        }
        return openScaleStored;
    }

    private void writeToHealthConnect(SharedPreferences prefs,
                                      UserProfile profile,
                                      long timestamp,
                                      S400Aggregator.Finalized measurement,
                                      S400BodyComposition.Result composition) {
        if (!prefs.getBoolean("health_connect_enabled", false)) return;
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        if (profile.userId != healthUserId) {
            EventLog.debug(this,
                    "Health Connect übersprungen: " + profile.name + " ist nicht der Hauptbenutzer");
            return;
        }

        HealthConnectSelection selection = HealthConnectSelection.fromPreferences(prefs);
        if (selection.count() == 0) {
            EventLog.error(this,
                    "Health Connect ist aktiv, aber es wurden keine Werte ausgewählt");
            return;
        }

        String scaleMac = prefs.getString("mac", "");
        HealthConnectWriter.write(
                this,
                timestamp,
                scaleMac,
                profile.heightCm,
                measurement,
                composition,
                selection,
                new HealthConnectWriter.Callback() {
                    @Override public void onSuccess(int writtenRecordCount, String writtenValues) {
                        EventLog.info(
                                ScaleScanService.this,
                                "Health Connect: " + profile.name + " – "
                                        + writtenRecordCount + " Werte gespeichert");
                        EventLog.debug(
                                ScaleScanService.this,
                                "Health Connect geschrieben: " + writtenValues);
                    }

                    @Override public void onError(String message) {
                        EventLog.error(
                                ScaleScanService.this,
                                "Health Connect fehlgeschlagen: " + message);
                    }
                });
    }

    private boolean logProviderResult(OpenScaleProvider.InsertResult result, String userName) {
        if (!result.measurementVerified) {
            EventLog.error(this,
                    "openScale-Übergabe für " + userName
                            + " konnte nicht bestätigt werden");
            updateMonitor("Übergabe nicht bestätigt");
            return false;
        }

        if (result.apiVersion < 2) {
            EventLog.info(this,
                    "openScale: " + userName + " – 4 Grundwerte gespeichert");
            EventLog.debug(this,
                    "Provider-API 1 unterstützt extern nur Gewicht, Fett, Wasser und Muskel");
            updateMonitor("Messung für " + userName + " gespeichert");
            return true;
        }

        if (result.additionalValuesVerified) {
            EventLog.info(this,
                    "openScale: " + userName + " – vollständige Messung gespeichert ("
                            + result.storedValueCount + " Werte)");
            EventLog.debug(this,
                    "openScale Provider-API " + result.apiVersion + " erfolgreich geprüft");
            updateMonitor("Messung für " + userName + " gespeichert");
            return true;
        }

        EventLog.warning(this,
                "openScale: " + userName
                        + " – Grundmessung gespeichert, Zusatzwerte nicht bestätigt");
        updateMonitor("Grundmessung für " + userName + " gespeichert");
        return true;
    }

    private String buildCalculationLog(String userName,
                                       int age,
                                       S400Aggregator.Finalized measurement,
                                       S400BodyComposition.Result composition) {
        StringBuilder text = new StringBuilder();
        text.append(String.format(
                Locale.GERMANY,
                "S400 ausgewertet für %s (Alter %d): %.1f kg",
                userName,
                age,
                measurement.weightKg));
        appendValue(text, "BMI", composition.bmi, "");
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? "ohne Detailangabe" : message;
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

    private Notification assignmentNotification(PendingMeasurementStore.Item item, int count) {
        PendingIntent open = PendingIntent.getActivity(
                this,
                3,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = String.format(
                Locale.GERMANY,
                "%.1f kg – Benutzer auswählen%s",
                item.weightKg,
                count > 1 ? " (" + count + " offen)" : "");
        return new Notification.Builder(this, CHANNEL_ASSIGNMENT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Messung nicht eindeutig zugeordnet")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateAssignmentNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        List<PendingMeasurementStore.Item> pending = PendingMeasurementStore.load(
                getSharedPreferences("prefs", MODE_PRIVATE));
        if (pending.isEmpty()) {
            manager.cancel(NOTIFICATION_ASSIGNMENT);
        } else {
            manager.notify(
                    NOTIFICATION_ASSIGNMENT,
                    assignmentNotification(pending.get(0), pending.size()));
        }
    }

    private void updateMonitor(String text) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_MONITOR, monitorNotification(text));
    }

    private void createChannels() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel monitor = new NotificationChannel(
                CHANNEL_MONITOR,
                "Waagenüberwachung",
                NotificationManager.IMPORTANCE_LOW);
        monitor.setSound(null, null);
        monitor.enableVibration(false);
        monitor.setShowBadge(false);
        manager.createNotificationChannel(monitor);

        NotificationChannel assignment = new NotificationChannel(
                CHANNEL_ASSIGNMENT,
                "Benutzerzuordnung",
                NotificationManager.IMPORTANCE_LOW);
        assignment.setSound(null, null);
        assignment.enableVibration(false);
        assignment.setShowBadge(true);
        manager.createNotificationChannel(assignment);
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
        EventLog.info(this, "Dienst gestoppt");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
