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
    private static final String CHANNEL_RESULT = "scale_measurement_results_v1";
    private static final String LEGACY_CHANNEL_ASSIGNMENT = "scale_assignment_v1";
    private static final String LEGACY_CHANNEL_FAILURE = "scale_measurement_failure_v1";
    private static final int NOTIFICATION_MONITOR = 10;
    private static final int NOTIFICATION_ASSIGNMENT = 11;
    private static final int NOTIFICATION_RESULT = 12;
    private static final int LEGACY_NOTIFICATION_TRANSFER_FAILURE = 13;
    private static final long WATCHDOG_INTERVAL_MS = 15_000L;
    private static final long SCALE_STALE_AFTER_MS = 90_000L;
    private static final long SCAN_RESTART_DELAY_MS = 3_000L;
    private static final long USER_SYNC_INTERVAL_MS = 15 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final S400Aggregator aggregator = new S400Aggregator();
    private final Runnable timeoutRunnable = this::finalizeTimedOutSession;
    private final Runnable decryptionFailureRunnable = this::finalizeUndecryptableSession;
    private final Runnable watchdogRunnable = this::runWatchdog;
    private final Runnable userSyncRunnable = new Runnable() {
        @Override public void run() {
            synchronizeOpenScaleUsers();
            if (!explicitStop) {
                handler.postDelayed(this, USER_SYNC_INTERVAL_MS);
            }
        }
    };
    private final Runnable restartScanRunnable = () -> {
        restartScheduled = false;
        startScan();
    };

    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private boolean scanRunning;
    private boolean scaleSeenLogged;
    private String lastLoggedSignature;
    private boolean explicitStop;
    private boolean terminalError;
    private boolean restartScheduled;
    private boolean activeLogged;
    private long lastPacketAtMs;
    private long scanStartedAtMs;
    private long lastWatchdogWarningAtMs;
    private long undecipheredSessionStartedAtMs;
    private long lastUndecipheredFailureAtMs;
    private String monitorText = "BLE-Überwachung wird gestartet …";

    @Override public void onCreate() {
        super.onCreate();
        createChannels();
        ServiceState.starting(this, "BLE-Überwachung wird gestartet");
        startForeground(NOTIFICATION_MONITOR, monitorNotification(monitorText));
        EventLog.info(this, "Dienst gestartet – Stabilitätsmodus 3.2");
        updateAssignmentNotification();
        handler.post(userSyncRunnable);
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            explicitStop = true;
            ServiceState.stopped(this, "Vom Benutzer gestoppt");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_ASSIGN_PENDING.equals(intent.getAction())) {
            String pendingId = intent.getStringExtra(EXTRA_PENDING_ID);
            long userId = intent.getLongExtra(EXTRA_USER_ID, -1L);
            assignPending(pendingId, userId);
        } else if (intent != null && ACTION_REFRESH_PENDING.equals(intent.getAction())) {
            updateAssignmentNotification();
        } else {
            terminalError = false;
        }
        if (!scanRunning && !terminalError) startScan();
        return START_STICKY;
    }

    private void synchronizeOpenScaleUsers() {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String authority = prefs.getString("openscale_authority", "");
        if (authority == null || authority.isBlank()) return;

        try {
            int previousCount = UserProfileStore.load(prefs).size();
            List<OpenScaleProvider.User> currentUsers =
                    OpenScaleProvider.loadUsers(this, authority);
            List<UserProfile> synchronizedProfiles =
                    UserProfileStore.synchronize(prefs, currentUsers);

            if (previousCount != synchronizedProfiles.size()) {
                EventLog.info(this,
                        "openScale-Benutzer synchronisiert: "
                                + synchronizedProfiles.size() + " Benutzer");
            }
        } catch (SecurityException e) {
            EventLog.debug(this,
                    "Automatische Benutzersynchronisierung: Berechtigung fehlt");
        } catch (RuntimeException e) {
            EventLog.debug(this,
                    "Automatische Benutzersynchronisierung vorübergehend fehlgeschlagen");
        }
    }

    private void startScan() {
        if (scanRunning) return;
        handler.removeCallbacks(restartScanRunnable);
        restartScheduled = false;

        if (!PowerSettingsHelper.isBatteryOptimizationDisabled(this)) {
            enterTerminalError("Akkuoptimierung ist noch aktiv");
            return;
        }
        if (!PowerSettingsHelper.isUnusedAppManagementDisabled(this)) {
            enterTerminalError("Verwaltung bei Nichtnutzung ist noch aktiv");
            return;
        }
        if (!PowerSettingsHelper.areNotificationsUsable(this)) {
            enterTerminalError("Benachrichtigungen sind nicht vollständig erlaubt");
            return;
        }

        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            enterTerminalError("Bluetooth-Berechtigung fehlt");
            return;
        }

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        String mac = prefs.getString("mac", "");
        String bindKey = prefs.getString("bind_key", "");
        String authority = prefs.getString("openscale_authority", "");
        List<UserProfile> profiles = UserProfileStore.enabled(UserProfileStore.load(prefs));

        if (!S400Decryptor.isValidMacAddress(mac)) {
            enterTerminalError("Keine gültige Waagen-MAC gespeichert");
            return;
        }
        if (!S400Decryptor.isValidBindKey(bindKey)) {
            enterTerminalError("Kein gültiger S400 Bind-Key gespeichert");
            return;
        }
        if (authority == null || authority.isBlank()) {
            enterTerminalError("Keine openScale-Verbindung gespeichert");
            return;
        }
        try {
            OpenScaleProvider.Meta meta = OpenScaleProvider.readMeta(this, authority);
            if (!meta.supportsGenericValues()) {
                enterTerminalError("openScale Provider-API 2 wird benötigt");
                return;
            }
        } catch (SecurityException e) {
            enterTerminalError("openScale-Berechtigung fehlt");
            return;
        } catch (RuntimeException e) {
            enterTerminalError("openScale ist nicht erreichbar");
            return;
        }
        if (profiles.isEmpty()) {
            enterTerminalError("Kein aktives Benutzerprofil eingerichtet");
            return;
        }
        long now = System.currentTimeMillis();
        for (UserProfile profile : profiles) {
            if (!profile.hasValidBodyData(now) || !profile.hasValidMatchingData()) {
                enterTerminalError("Benutzerprofil unvollständig: " + profile.name);
                return;
            }
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            enterRecoverableError("Bluetooth ist ausgeschaltet");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            enterRecoverableError("Bluetooth-Scanner nicht verfügbar");
            scheduleScanRestart("Bluetooth-Scanner nicht verfügbar");
            return;
        }

        callback = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                if (result.getDevice() != null
                        && mac.equalsIgnoreCase(result.getDevice().getAddress())) {
                    long previousPacketAt = lastPacketAtMs;
                    lastPacketAtMs = System.currentTimeMillis();
                    ServiceState.scaleSeen(ScaleScanService.this);
                    if (!scaleSeenLogged
                            || previousPacketAt <= 0L
                            || lastPacketAtMs - previousPacketAt > SCALE_STALE_AFTER_MS) {
                        updateMonitor("Waage erreichbar – warte auf Messung");
                    }
                    analyze(result, mac, bindKey);
                }
            }

            @Override public void onScanFailed(int code) {
                scanRunning = false;
                EventLog.warning(ScaleScanService.this,
                        "BLE-Scan unterbrochen (Fehler " + code + ") – Neustart läuft");
                enterRecoverableError("BLE-Scan wird neu gestartet");
                scheduleScanRestart("BLE-Scanfehler " + code);
            }
        };

        ScanFilter filter = new ScanFilter.Builder().setDeviceAddress(mac).build();
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();
        try {
            scanner.startScan(Collections.singletonList(filter), settings, callback);
            scanRunning = true;
            terminalError = false;
            scanStartedAtMs = System.currentTimeMillis();
            monitorText = lastPacketAtMs > 0L
                    ? "Waage erreichbar – warte auf Messung"
                    : "Suche S400-Waage";
            ServiceState.running(this, monitorText, true);
            if (!activeLogged) {
                activeLogged = true;
                EventLog.info(this,
                        "Überwachung aktiv – " + profiles.size() + " Benutzerprofile bereit");
            } else {
                EventLog.debug(this, "BLE-Überwachung erfolgreich neu gestartet");
            }
            EventLog.debug(this, "BLE-Scan aktiv für " + mac);
            notifyMonitor();
        } catch (RuntimeException e) {
            scanRunning = false;
            EventLog.warning(this,
                    "Scanstart fehlgeschlagen: " + e.getClass().getSimpleName()
                            + " – automatischer Neustart");
            enterRecoverableError("BLE-Scan wird neu gestartet");
            scheduleScanRestart("Scanstart fehlgeschlagen");
        }
    }

    private void analyze(ScanResult result, String mac, String bindKey) {
        BlePacket packet = BlePacket.from(this, result);
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
            long now = System.currentTimeMillis();
            if (now - lastUndecipheredFailureAtMs >= S400Aggregator.SESSION_TIMEOUT_MS
                    && undecipheredSessionStartedAtMs == 0L) {
                undecipheredSessionStartedAtMs = now;
                handler.removeCallbacks(decryptionFailureRunnable);
                handler.postDelayed(
                        decryptionFailureRunnable,
                        S400Aggregator.SESSION_TIMEOUT_MS);
            }
            return;
        }

        undecipheredSessionStartedAtMs = 0L;
        handler.removeCallbacks(decryptionFailureRunnable);

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

    private void finalizeUndecryptableSession() {
        if (undecipheredSessionStartedAtMs == 0L) return;
        undecipheredSessionStartedAtMs = 0L;
        lastUndecipheredFailureAtMs = System.currentTimeMillis();
        handler.removeCallbacks(timeoutRunnable);
        aggregator.reset();
        rejectMeasurement("S400-Messpakete konnten nicht entschlüsselt werden");
    }

    private void handleOutcome(S400Aggregator.Outcome outcome) {
        if (outcome.status == S400Aggregator.Status.PENDING) return;
        if (outcome.status == S400Aggregator.Status.DUPLICATE) {
            EventLog.debug(this, "Doppeltes Messpaket verworfen");
            if (aggregator.hasPendingSession()) {
                handler.removeCallbacks(timeoutRunnable);
                handler.postDelayed(
                        timeoutRunnable,
                        aggregator.remainingTimeoutMs(System.currentTimeMillis()));
            } else {
                handler.removeCallbacks(timeoutRunnable);
            }
            updateMonitor("Warte auf nächste Messung");
            return;
        }
        handler.removeCallbacks(timeoutRunnable);
        if (outcome.status == S400Aggregator.Status.INCOMPLETE) {
            rejectMeasurement(getString(outcome.reasonResId));
            return;
        }
        if (outcome.finalized == null || !outcome.finalized.isComplete()) {
            rejectMeasurement("Paket A und Paket B waren nicht vollständig");
            return;
        }

        S400Aggregator.Finalized value = outcome.finalized;
        EventLog.info(this, String.format(
                Locale.GERMANY,
                "Vollständige Messung erkannt: %.1f kg",
                value.weightKg));
        EventLog.debug(this, String.format(
                Locale.GERMANY,
                "S400 vollständig entschlüsselt: %.1f kg | Impedanz %.1f/%.1f Ω",
                value.weightKg,
                value.impedanceHigh,
                value.impedanceLow));
        routeMeasurement(value);
    }

    private void routeMeasurement(S400Aggregator.Finalized measurement) {
        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        List<UserProfile> profiles = UserProfileStore.enabled(UserProfileStore.load(prefs));
        UserMatcher.Result match = UserMatcher.match(profiles, measurement.weightKg);
        EventLog.debug(this, getString(
                R.string.log_user_match,
                UserMatcher.diagnosticSummary(this, match)));

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
            rejectMeasurement("Geburtstag von " + profile.name + " ist ungültig");
            return false;
        }

        if (!measurement.isComplete() || measurement.impedanceLow == null) {
            rejectMeasurement("Paket A und Paket B waren nicht vollständig");
            return false;
        }
        S400BodyComposition.Result composition = S400BodyComposition.compute(
                new S400BodyComposition.Inputs(
                        age,
                        profile.male,
                        profile.heightCm,
                        measurement.weightKg,
                        measurement.impedanceHigh,
                        measurement.impedanceLow));

        String compositionError = validateCompleteComposition(composition);
        if (compositionError != null) {
            rejectMeasurement("Körperanalyse unvollständig: " + compositionError);
            return false;
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
            rejectMeasurement(
                    "openScale-Zugriff verweigert – Berechtigung erneut erteilen");
            return false;
        } catch (RuntimeException e) {
            rejectMeasurement(
                    "Übergabe an openScale fehlgeschlagen: "
                            + e.getClass().getSimpleName() + " – " + safeMessage(e));
            return false;
        }

        if (!openScaleStored) {
            rejectMeasurement("Speicherung in openScale konnte nicht vollständig bestätigt werden");
            return false;
        }

        boolean healthConnectStarted = writeToHealthConnect(
                prefs, profile, timestamp, measurement, composition);
        if (!healthConnectStarted) markMeasurementSuccess(profile.name);
        return true;
    }

    private boolean writeToHealthConnect(SharedPreferences prefs,
                                      UserProfile profile,
                                      long timestamp,
                                      S400Aggregator.Finalized measurement,
                                      S400BodyComposition.Result composition) {
        if (!prefs.getBoolean("health_connect_enabled", false)) return false;
        long healthUserId = prefs.getLong("health_connect_user_id", -1L);
        if (profile.userId != healthUserId) {
            EventLog.debug(this,
                    "Health Connect übersprungen: " + profile.name + " ist nicht der Hauptbenutzer");
            return false;
        }

        HealthConnectSelection selection = HealthConnectSelection.fromPreferences(prefs);
        if (selection.count() == 0) {
            EventLog.error(this,
                    "Health Connect ist aktiv, aber es wurden keine Werte ausgewählt");
            notifyTransferFailure(
                    "openScale wurde gespeichert, Health Connect ist jedoch falsch konfiguriert.");
            updateMonitor("openScale gespeichert – Health Connect falsch konfiguriert");
            return true;
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
                        markMeasurementSuccess(profile.name);
                    }

                    @Override public void onError(String message) {
                        EventLog.error(
                                ScaleScanService.this,
                                "Health Connect fehlgeschlagen: " + message);
                        notifyTransferFailure(
                                "openScale wurde gespeichert, Health Connect jedoch nicht. "
                                        + "Bitte Berechtigungen prüfen.");
                        updateMonitor("openScale gespeichert – Health Connect fehlgeschlagen");
                    }
                });
        return true;
    }

    private void rejectMeasurement(String reason) {
        handler.removeCallbacks(timeoutRunnable);
        handler.removeCallbacks(decryptionFailureRunnable);
        undecipheredSessionStartedAtMs = 0L;
        String detail = reason == null || reason.isBlank()
                ? "Messdaten waren unvollständig"
                : reason;
        EventLog.error(this, "Messung vollständig verworfen – " + detail);
        ServiceState.measurementFailed(this);
        updateMonitor("Letzte Messung fehlgeschlagen – bitte wiederholen");
        notifyMeasurementFailure(detail);
    }

    /**
     * Returns null only when every value expected from a complete S400 body analysis
     * is finite and usable. Approximate/partial calculations are deliberately rejected.
     */
    private String validateCompleteComposition(S400BodyComposition.Result value) {
        if (value == null) return "keine Berechnung erzeugt";
        if (value.reliability != S400BodyComposition.Reliability.OK) {
            return "Qualität " + value.reliability.name();
        }

        java.util.ArrayList<String> missing = new java.util.ArrayList<>();
        requirePositive(missing, "BMI", value.bmi);
        requirePositive(missing, "Körperwasser", value.totalBodyWaterKg);
        requirePercent(missing, "Körperwasser %", value.totalBodyWaterPercent);
        requirePositive(missing, "ECW", value.extracellularWaterKg);
        requirePercent(missing, "ECW %", value.extracellularWaterPercent);
        requirePositive(missing, "ICW", value.intracellularWaterKg);
        requirePercent(missing, "ICW %", value.intracellularWaterPercent);
        requirePositive(missing, "fettfreie Masse", value.fatFreeMassKg);
        requirePercent(missing, "fettfreie Masse %", value.fatFreeMassPercent);
        requirePositive(missing, "Körperfett", value.bodyFatKg);
        requirePercent(missing, "Körperfett %", value.bodyFatPercent);
        requirePositive(missing, "Muskelmasse", value.skeletalMuscleKg);
        requirePercent(missing, "Muskelmasse %", value.skeletalMusclePercent);
        requirePositive(missing, "Knochenmasse", value.boneKg);
        requirePositive(missing, "Viszeralfett", value.visceralFatIndex);
        requirePositive(missing, "Grundumsatz", value.basalMetabolicRateKcal);
        requirePositive(missing, "Körperzellmasse", value.bodyCellMassKg);
        requirePositive(missing, "Protein", value.proteinKg);
        requirePercent(missing, "Protein %", value.proteinPercent);
        requirePositive(missing, "weiche Magermasse", value.softLeanMassKg);
        return missing.isEmpty() ? null : String.join(", ", missing) + " fehlt/ungültig";
    }

    private static void requirePositive(List<String> missing, String name, Float value) {
        if (value == null || !Float.isFinite(value) || value <= 0f) missing.add(name);
    }

    private static void requirePositive(List<String> missing, String name, float value) {
        if (!Float.isFinite(value) || value <= 0f) missing.add(name);
    }

    private static void requirePercent(List<String> missing, String name, Float value) {
        if (value == null || !Float.isFinite(value) || value <= 0f || value > 100f) {
            missing.add(name);
        }
    }

    private void markMeasurementSuccess(String userName) {
        ServiceState.measurementSucceeded(this);
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);
        manager.notify(
                NOTIFICATION_RESULT,
                resultNotification(
                        "Messung erfolgreich an " + userName + " zugeordnet",
                        "Alle vollständigen Messwerte wurden gespeichert.",
                        false));
        updateMonitor("Letzte Messung für " + userName + " vollständig gespeichert");
    }

    private void notifyMeasurementFailure(String reason) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);
        manager.notify(
                NOTIFICATION_RESULT,
                resultNotification(
                        "Messung fehlgeschlagen, bitte wiederholen",
                        shorten(reason),
                        true));
    }

    private void notifyTransferFailure(String reason) {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(
                NOTIFICATION_RESULT,
                resultNotification("Übertragung unvollständig", shorten(reason), true));
    }

    private static String shorten(String value) {
        if (value == null || value.isBlank()) return "Unbekannter Fehler";
        String clean = value.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() <= 150 ? clean : clean.substring(0, 147) + "…";
    }

    private boolean logProviderResult(OpenScaleProvider.InsertResult result, String userName) {
        if (result.apiVersion < 2) {
            EventLog.error(this,
                    "openScale Provider-API 2 fehlt – vollständige Messung nicht möglich");
            return false;
        }

        if (result.measurementVerified && result.additionalValuesVerified) {
            EventLog.info(this,
                    "openScale: " + userName + " – vollständige Messung gespeichert ("
                            + result.storedValueCount + " Werte)");
            EventLog.debug(this,
                    "openScale Provider-API " + result.apiVersion + " erfolgreich geprüft");
            updateMonitor("Messung für " + userName + " gespeichert");
            return true;
        }

        String missing = result.missingValueKeys == null || result.missingValueKeys.isEmpty()
                ? "unbekannte Werte"
                : String.join(", ", result.missingValueKeys);
        EventLog.error(this,
                "openScale-Übergabe für " + userName + " unvollständig – fehlend: "
                        + missing + (result.rollbackPerformed
                        ? "; unvollständiger Eintrag wurde gelöscht"
                        : "; Löschung konnte nicht bestätigt werden"));
        return false;
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

    private void runWatchdog() {
        if (explicitStop) return;

        if (terminalError) {
            ServiceState.heartbeat(this, false, monitorText);
            handler.removeCallbacks(watchdogRunnable);
            handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
            return;
        }

        long now = System.currentTimeMillis();
        if (!PowerSettingsHelper.isBatteryOptimizationDisabled(this)) {
            enterTerminalError("Akkuoptimierung ist wieder aktiv");
        } else if (!PowerSettingsHelper.isUnusedAppManagementDisabled(this)) {
            enterTerminalError("Verwaltung bei Nichtnutzung ist wieder aktiv");
        } else if (!PowerSettingsHelper.areNotificationsUsable(this)) {
            enterTerminalError("Benachrichtigungen wurden deaktiviert");
        } else if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            enterTerminalError("Bluetooth-Berechtigung wurde entzogen");
        } else {
            BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                enterRecoverableError("Bluetooth ist ausgeschaltet");
                scheduleScanRestart("Bluetooth ist ausgeschaltet");
            } else if (!scanRunning) {
                scheduleScanRestart("BLE-Überwachung war nicht aktiv");
            } else {
                long reference = lastPacketAtMs > 0L ? lastPacketAtMs : scanStartedAtMs;
                if (reference > 0L && now - reference > SCALE_STALE_AFTER_MS) {
                    if (now - lastWatchdogWarningAtMs > 5 * 60_000L) {
                        EventLog.warning(this,
                                "Waage seit 90 Sekunden nicht erkannt – BLE-Scan wird vorsorglich neu gestartet");
                        lastWatchdogWarningAtMs = now;
                    }
                    scheduleScanRestart("Waage nicht erreichbar – BLE-Scan wird neu gestartet");
                } else {
                    ServiceState.heartbeat(this, true, monitorText);
                }
            }
        }

        handler.removeCallbacks(watchdogRunnable);
        handler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS);
    }

    private void scheduleScanRestart(String reason) {
        if (explicitStop || terminalError || restartScheduled) return;
        stopScan();
        restartScheduled = true;
        monitorText = reason + " – neuer Versuch in 3 Sekunden";
        ServiceState.error(this, monitorText);
        notifyMonitor();
        handler.postDelayed(restartScanRunnable, SCAN_RESTART_DELAY_MS);
    }

    private void enterTerminalError(String reason) {
        terminalError = true;
        handler.removeCallbacks(restartScanRunnable);
        restartScheduled = false;
        stopScan();
        monitorText = reason;
        ServiceState.error(this, reason);
        EventLog.error(this, "Überwachung angehalten – " + reason);
        notifyMonitor();
    }

    private void enterRecoverableError(String reason) {
        terminalError = false;
        monitorText = reason;
        ServiceState.error(this, reason);
        notifyMonitor();
    }

    private void notifyMonitor() {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIFICATION_MONITOR, monitorNotification(monitorText));
    }

    private Notification monitorNotification(String text) {
        ServiceState.Snapshot state = ServiceState.read(this);
        String title;
        switch (state.mode) {
            case ERROR:
                title = "ScaleLauncher – Fehler";
                break;
            case STARTING:
                title = "ScaleLauncher startet";
                break;
            case RUNNING:
            default:
                title = "ScaleLauncher aktiv";
                break;
        }
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
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stoppen", stop).build())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private Notification resultNotification(String title, String text, boolean error) {
        PendingIntent open = PendingIntent.getActivity(
                this,
                4,
                new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(error
                        ? android.R.drawable.stat_notify_error
                        : android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
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
        return new Notification.Builder(this, CHANNEL_RESULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Messung kann nicht zugeordnet werden")
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(false)
                .setOngoing(false)
                .setOnlyAlertOnce(false)
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
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
        monitorText = text == null || text.isBlank() ? "Warte auf S400-Messung" : text;
        if (scanRunning && !terminalError) {
            ServiceState.running(this, monitorText, true);
        } else {
            ServiceState.heartbeat(this, false, monitorText);
        }
        notifyMonitor();
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

        // Version 3.2 uses one dedicated, visible result channel for every completed
        // weighing outcome. A new channel ID is intentional: Android keeps the old
        // sound/importance settings of an existing channel across app updates.
        manager.deleteNotificationChannel(LEGACY_CHANNEL_ASSIGNMENT);
        manager.deleteNotificationChannel(LEGACY_CHANNEL_FAILURE);
        manager.cancel(LEGACY_NOTIFICATION_TRANSFER_FAILURE);

        NotificationChannel result = new NotificationChannel(
                CHANNEL_RESULT,
                "Messergebnisse",
                NotificationManager.IMPORTANCE_DEFAULT);
        result.setDescription(
                "Erfolgreiche, fehlgeschlagene und nicht zuordenbare Messungen");
        result.enableVibration(true);
        result.setShowBadge(true);
        manager.createNotificationChannel(result);
    }

    private void stopScan() {
        BluetoothLeScanner oldScanner = scanner;
        ScanCallback oldCallback = callback;
        boolean wasRunning = scanRunning;
        scanRunning = false;
        scanner = null;
        callback = null;
        if (oldScanner != null
                && oldCallback != null
                && wasRunning
                && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED) {
            try {
                oldScanner.stopScan(oldCallback);
            } catch (RuntimeException ignored) {
            }
        }
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopScan();
        aggregator.reset();
        if (explicitStop) {
            ServiceState.stopped(this, "Vom Benutzer gestoppt");
            EventLog.info(this, "Dienst gestoppt");
        } else if (terminalError) {
            ServiceState.error(this, monitorText);
            EventLog.warning(this, "Dienst im Fehlerzustand beendet – " + monitorText);
        } else {
            ServiceState.starting(this, "Dienst wurde beendet – Neustart wird erwartet");
            EventLog.warning(this, "Dienst unerwartet beendet – automatischer Neustart wird erwartet");
        }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
